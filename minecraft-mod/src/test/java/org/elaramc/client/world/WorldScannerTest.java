package org.elaramc.client.world;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.CollisionView;
import net.minecraft.world.border.WorldBorder;
import org.elaramc.search.Direction;
import org.elaramc.search.GridGraph;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchAlgorithm;
import org.elaramc.search.SearchRegion;
import org.elaramc.search.SearchSession;
import org.elaramc.search.SearchStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldScannerTest {
    private final WorldScanner scanner = new WorldScanner();

    @BeforeAll
    static void loadVanillaBlocks() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void allowsCarpetAndNoncollidingDecorationAndRejectsHeadObstructionsWaterAndGaps() {
        var world = new Fixture();
        world.floor(0, 1, 2, 3, 5);
        world.put(1, 64, Blocks.YELLOW_CARPET.getDefaultState());
        world.put(2, 65, Blocks.STONE.getDefaultState());
        world.put(3, 64, Blocks.WATER.getDefaultState());
        world.put(5, 64, Blocks.OAK_PRESSURE_PLATE.getDefaultState());

        GridGraph snapshot = scan(world, 5, 0, 1);

        assertEquals(3, snapshot.size());
        assertEquals(GridGraph.MoveStatus.OPEN, snapshot.transition(cell(0), Direction.EAST).status());
        assertEquals(GridGraph.MoveStatus.OPEN, snapshot.transition(cell(1), Direction.WEST).status());
        assertFalse(snapshot.contains(cell(2)));
        assertFalse(snapshot.contains(cell(3)));
        assertFalse(snapshot.contains(cell(4)));
        assertTrue(snapshot.contains(cell(5)));
        assertThrows(IllegalArgumentException.class, () -> scan(world, 5, 0, 3));

        world.put(1, 64, Blocks.STONE.getDefaultState());
        assertTrue(snapshot.contains(cell(1)));
        assertThrows(IllegalArgumentException.class, () -> scan(world, 5, 0, 1));
    }

    @Test
    void sweptBodyCanBlockAnEdgeBetweenTwoStandableCells() {
        var world = new Fixture();
        world.floor(0, 1);
        world.put(1, 64, Blocks.OAK_TRAPDOOR.getDefaultState().with(TrapdoorBlock.OPEN, true)
            .with(TrapdoorBlock.FACING, net.minecraft.util.math.Direction.EAST));

        GridGraph graph = scan(world, 1, 0, 1);

        assertEquals(2, graph.size());
        assertEquals(GridGraph.MoveStatus.BLOCKED_TRANSITION, graph.transition(cell(0), Direction.EAST).status());
        var session = new SearchSession(graph, cell(0), cell(1), SearchAlgorithm.BFS);
        assertEquals(SearchStatus.EXHAUSTED, session.runToCompletion(event -> {}));
    }

    @Test
    void rejectsAConnectionAcrossAGapInSupport() {
        var world = new Fixture();
        world.put(0, 63, Blocks.OAK_STAIRS.getDefaultState()
            .with(StairsBlock.FACING, net.minecraft.util.math.Direction.WEST));
        world.put(1, 63, Blocks.OAK_STAIRS.getDefaultState()
            .with(StairsBlock.FACING, net.minecraft.util.math.Direction.EAST));

        GridGraph graph = scan(world, 1, 0, 1);

        assertEquals(2, graph.size());
        assertEquals(GridGraph.MoveStatus.BLOCKED_TRANSITION, graph.transition(cell(0), Direction.EAST).status());
    }

    @Test
    void rejectsUnloadedCollisionPaddingBeforeReadingAnyBlocks() {
        var world = new Fixture();
        world.floor(0, 1);
        world.missingWest = true;

        var error = assertThrows(IllegalArgumentException.class, () -> scan(world, 1, 0, 1));

        assertTrue(error.getMessage().contains("descarregado"));
        assertEquals(0, world.blockReads);
    }

    @Test
    void automaticRegionGrowsTowardAReachableDetourWithoutScanningColumnsTwice() {
        var world = new Fixture();
        world.missingWest = true;
        var start = new GridPosition(1, 64, 0);
        var goal = new GridPosition(3, 64, 0);
        for (int z = 0; z <= 5; z++) {
            world.put(1, 63, z, Blocks.STONE.getDefaultState());
            world.put(3, 63, z, Blocks.STONE.getDefaultState());
        }
        world.put(2, 63, 5, Blocks.STONE.getDefaultState());
        var job = scanner.begin(world, null, start, goal, 16, 16);
        assertEquals(0, world.blockReads);
        var graph = finish(job).graph();
        var region = graph.region();
        assertEquals(2, job.attempts());
        assertEquals(1, region.minX());
        assertTrue(region.maxZ() >= 5);
        assertEquals(region.width() * region.depth(), job.columns());
        assertTrue(job.columns() > 16);
        assertEquals(13, graph.size());
        var search = new SearchSession(graph, start, goal, SearchAlgorithm.BFS);
        assertEquals(SearchStatus.FOUND, search.runToCompletion(event -> {}));
        assertEquals(12, search.metrics().pathLength().orElseThrow());
    }

    @Test
    void bothSearchesTraverseAnAscentAndDescentInTheAutomaticRegion() {
        var world = new Fixture();
        world.floor(0, 2);
        world.put(1, 64, Blocks.STONE.getDefaultState());
        world.put(3, 64, Blocks.STONE.getDefaultState());
        var origin = cell(0);
        var goal = new GridPosition(3, 65, 0);
        var graph = finish(scanner.begin(world, null, origin, goal, 8, 64)).graph();
        var expected = List.of(origin, new GridPosition(1, 65, 0), cell(2), goal);
        for (var algorithm : SearchAlgorithm.values()) {
            var session = new SearchSession(graph, origin, goal, algorithm);
            assertEquals(SearchStatus.FOUND, session.runToCompletion(event -> {}));
            assertEquals(expected, session.path());
        }
    }

    @Test
    void overheadBlockCanPreventAJumpWhileAllowingTheReverseDrop() {
        var world = new Fixture();
        world.floor(0);
        world.put(1, 64, Blocks.STONE.getDefaultState());
        world.put(0, 67, Blocks.STONE.getDefaultState());
        var lower = cell(0);
        var upper = new GridPosition(1, 65, 0);
        var graph = finish(scanner.begin(world, new SearchRegion(0, 0, 1, 0, 64, 65), lower, upper, 8, 64)).graph();
        assertEquals(GridGraph.MoveStatus.BLOCKED_TRANSITION, graph.transition(lower, Direction.EAST_UP).status());
        assertEquals(GridGraph.MoveStatus.OPEN, graph.transition(upper, Direction.WEST_DOWN).status());
    }

    @Test
    void rejectsBadBoundsAndStopsAtTheStateLimit() {
        var world = new Fixture();
        world.floor(0, 1);
        assertThrows(IllegalArgumentException.class, () -> scan(world, 1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> scanner.begin(world,
            new SearchRegion(0, 0, 64, 0, 64), cell(0), cell(1), 64, 2));
        var volume = assertThrows(IllegalArgumentException.class, () -> scanner.begin(world,
            new SearchRegion(0, 0, 127, 127, 64, 80), cell(0), cell(1), WorldScanner.MAX_SIDE, WorldScanner.MAX_STATES));
        assertTrue(volume.getMessage().contains("volume"));
        assertEquals(0, world.blockReads);

        var error = assertThrows(IllegalArgumentException.class, () ->
            finish(scanner.begin(world, new SearchRegion(0, 0, 1, 0, 64), cell(0), cell(1), 2, 1)));
        assertTrue(error.getMessage().contains("Limite de estados transitáveis excedido (1)"));
    }

    @Test
    void distantEndpointsFitInALongCorridorWithoutScanningAMaximumSquare() {
        var world = new Fixture();
        for (int x = 0; x < 100; x++) world.put(x, 63, Blocks.STONE.getDefaultState());
        var goal = cell(99);
        var rejected = assertThrows(IllegalArgumentException.class,
            () -> scanner.begin(world, null, cell(0), goal, 64, 4096));
        assertTrue(rejected.getMessage().contains("100 × 1"));
        var job = scanner.begin(world, null, cell(0), goal, 128, 100);
        assertFalse(job.advance(1, Long.MAX_VALUE));
        assertEquals(1, job.columns());
        assertNull(job.result());
        var graph = finish(job).graph();
        assertEquals(100, graph.size());
        assertEquals(1, job.attempts());
        assertTrue(job.columns() < 1_000);
        assertEquals(99, complete(graph, cell(0), goal, SearchAlgorithm.BFS).metrics().pathLength().orElseThrow());
    }

    @Test
    void twoFloorsCanFillTheIndependentStateBudget() {
        var world = new Fixture();
        for (int z = 0; z < 128; z++) {
            for (int x = 0; x < 128; x++) {
                world.put(x, 63, z, Blocks.STONE.getDefaultState());
                world.put(x, 67, z, Blocks.STONE.getDefaultState());
            }
        }
        var goal = new GridPosition(127, 64, 127);
        var region = new SearchRegion(0, 0, 127, 127, 64, 68);
        var job = scanner.begin(world, region, cell(0), goal, WorldScanner.MAX_SIDE, WorldScanner.MAX_STATES);
        var graph = assertTimeout(Duration.ofSeconds(20), () -> finish(job).graph());
        assertEquals(32_768, graph.size());
        assertEquals(128L * 128, job.columns());
        assertTrue(graph.contains(new GridPosition(127, 68, 127)));
        assertFalse(graph.contains(new GridPosition(127, 65, 127)));
        assertEquals(254, complete(graph, cell(0), goal, SearchAlgorithm.BFS).metrics().pathLength().orElseThrow());
    }

    @Test
    void snapshotPreservesAlternativeRoutesAndMatchesAFixedRescan() {
        var world = new Fixture();
        world.floor(0, 1, 2, 3);
        for (int z = -1; z >= -3; z--) {
            world.put(0, 63, z, Blocks.STONE.getDefaultState());
            world.put(3, 63, z, Blocks.STONE.getDefaultState());
        }
        world.put(1, 63, -3, Blocks.STONE.getDefaultState());
        world.put(2, 63, -3, Blocks.STONE.getDefaultState());
        var graph = finish(scanner.begin(world, null, cell(0), cell(3), 32, 64)).graph();
        var fixed = finish(scanner.begin(world, graph.region(), cell(0), cell(3), 32, 64)).graph();
        assertEquals(fixed.fingerprint(), graph.fingerprint());
        assertEquals(3, complete(graph, cell(0), cell(3), SearchAlgorithm.BFS).metrics().pathLength().orElseThrow());
        assertEquals(9, complete(graph, cell(0), cell(3), SearchAlgorithm.DFS).metrics().pathLength().orElseThrow());
    }

    @Test
    void closedComponentFinishesWithoutGrowingAndKeepsAnUnreachableGoal() {
        var world = new Fixture();
        world.floor(0, 3);
        var job = scanner.begin(world, null, cell(0), cell(3), 128, 4096);
        var graph = finish(job).graph();
        assertEquals(1, job.attempts());
        assertFalse(job.boundaryLimited());
        assertEquals(2, graph.size());
        for (var algorithm : SearchAlgorithm.values()) {
            assertEquals(SearchStatus.EXHAUSTED, complete(graph, cell(0), cell(3), algorithm).status());
        }
    }

    @Test
    void exhaustedAtTheConfiguredBoundaryIsReportedAsBounded() {
        var world = new Fixture();
        world.floor(0, 2);
        var job = scanner.begin(world, null, cell(0), cell(2), 3, 2);
        var graph = finish(job).graph();
        assertTrue(job.boundaryLimited());
        assertEquals(2, graph.size());
        assertEquals(SearchStatus.EXHAUSTED, complete(graph, cell(0), cell(2), SearchAlgorithm.BFS).status());
    }

    @Test
    void missingChunkDuringScanStopsTheJobBeforeTheNextBatch() {
        var world = new Fixture();
        world.floor(0, 1);
        var job = scanner.begin(world, new SearchRegion(0, 0, 1, 0, 64), cell(0), cell(1), 2, 2);
        assertFalse(job.advance(1, Long.MAX_VALUE));
        int reads = world.blockReads;
        world.missingWest = true;
        assertThrows(WorldScanner.UnloadedRegionException.class, () -> job.advance(10, Long.MAX_VALUE));
        assertEquals(reads, world.blockReads);
        assertNull(job.result());
    }

    private static SearchSession complete(GridGraph graph, GridPosition start, GridPosition goal, SearchAlgorithm algorithm) {
        var session = new SearchSession(graph, start, goal, algorithm);
        session.runToCompletion(event -> {});
        return session;
    }

    private static WorldSnapshot finish(WorldScanner.Scan job) {
        for (int batch = 0; batch < 2_000; batch++) {
            if (job.advance(512, Long.MAX_VALUE)) return job.result();
        }
        throw new AssertionError("Scan did not finish within the fixture's work budget");
    }

    private GridGraph scan(Fixture world, int maxX, int origin, int goal) {
        return finish(scanner.begin(world, new SearchRegion(0, 0, maxX, 0, 64), cell(origin), cell(goal), 64, 1024)).graph();
    }

    private static GridPosition cell(int x) {
        return new GridPosition(x, 64, 0);
    }

    private static final class Fixture implements CollisionView {
        private final WorldBorder border = new WorldBorder();
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private boolean missingWest;
        private int blockReads;

        void floor(int... positions) {
            for (int x : positions) {
                put(x, 63, Blocks.STONE.getDefaultState());
            }
        }

        void put(int x, int y, BlockState state) {
            put(x, y, 0, state);
        }

        void put(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
        }

        @Override
        public WorldBorder getWorldBorder() {
            return border;
        }

        @Override
        public BlockView getChunkAsView(int x, int z) {
            return missingWest && x == -1 ? null : this;
        }

        @Override
        public List<VoxelShape> getEntityCollisions(Entity entity, Box box) {
            return List.of();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            blockReads++;
            return blocks.getOrDefault(position, Blocks.AIR.getDefaultState());
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return getBlockState(position).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getBottomY() {
            return -64;
        }
    }
}
