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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void rejectsBadBoundsAndStopsAtTheStateLimit() {
        var world = new Fixture();
        world.floor(0, 1);
        assertThrows(IllegalArgumentException.class, () -> scan(world, 1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(world,
            new SearchRegion(0, 0, 64, 0, 64), cell(0), cell(1), WorldScanner.MAX_SIDE, 2));
        assertEquals(0, world.blockReads);

        var error = assertThrows(IllegalArgumentException.class, () ->
            scanner.scan(world, new SearchRegion(0, 0, 1, 0, 64), cell(0), cell(1), 2, 1));
        assertTrue(error.getMessage().contains("Limite de estados transitáveis excedido (1)"));
    }

    private GridGraph scan(Fixture world, int maxX, int origin, int goal) {
        return scanner.scan(world, new SearchRegion(0, 0, maxX, 0, 64), cell(origin), cell(goal), 64, 1024).graph();
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
            blocks.put(new BlockPos(x, y, 0), state);
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
