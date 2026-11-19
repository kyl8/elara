package org.elaramc.client.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.CollisionView;
import net.minecraft.world.border.WorldBorder;
import org.elaramc.search.Direction;
import org.elaramc.search.GridGraph;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchRegion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Takes a flat snapshot from already loaded block collision data. */
public final class WorldScanner {
    public static final int MAX_SIDE = 64;
    public static final int MAX_STATES = MAX_SIDE * MAX_SIDE;

    private static final double HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double CARPET_HEIGHT = 1.0 / 16;
    private static final double EPSILON = 1.0e-6;
    private static final List<Direction> FORWARD_EDGES = List.of(Direction.EAST, Direction.SOUTH);

    public static final class UnloadedRegionException extends IllegalArgumentException {
        private UnloadedRegionException(int x, int z) {
            super("Chunk (" + x + ", " + z + ") descarregado. Aproxime-se ou reduza a região.");
        }
    }

    public WorldSnapshot scan(CollisionView world, SearchRegion region, GridPosition start, GridPosition goal,
                              int maxSide, int maxStates) {
        if (maxSide < 1 || maxSide > MAX_SIDE || maxStates < 1 || maxStates > MAX_STATES) {
            throw new IllegalArgumentException("Os limites da região estão fora da faixa permitida.");
        }
        if (region.width() > maxSide || region.depth() > maxSide) {
            throw new IllegalArgumentException("A região excede " + maxSide + " blocos por lado.");
        }
        var graph = GridGraph.builder(region, maxSide, maxStates);
        requireInRegion(region, start, "Início");
        requireInRegion(region, goal, "Meta");
        requireAvailableRegion(world, region);

        var heights = new HashMap<GridPosition, Double>();
        for (int dz = 0; dz < region.depth(); dz++) {
            for (int dx = 0; dx < region.width(); dx++) {
                var position = new GridPosition(region.minX() + dx, region.y(), region.minZ() + dz);
                double x = position.x() + 0.5;
                double z = position.z() + 0.5;
                double surface = findSurface(world, x, z, position.y());
                if (!Double.isNaN(surface) && isClear(world, bodyAt(x, z, surface))) {
                    if (heights.size() == maxStates) {
                        throw new IllegalArgumentException("Limite de estados transitáveis excedido (" + maxStates + ").");
                    }
                    graph.addState(position);
                    heights.put(position, surface);
                }
            }
        }
        if (!heights.containsKey(start)) {
            throw new IllegalArgumentException("Início em posição bloqueada ou sem apoio em " + start.coordinates());
        }
        if (!heights.containsKey(goal)) {
            throw new IllegalArgumentException("Meta em posição bloqueada ou sem apoio em " + goal.coordinates());
        }

        for (var entry : heights.entrySet()) {
            GridPosition from = entry.getKey();
            for (Direction direction : FORWARD_EDGES) {
                GridPosition to = direction.from(from);
                Double targetHeight = heights.get(to);
                if (targetHeight != null && canCross(world, from, to, Math.max(entry.getValue(), targetHeight))) {
                    graph.connect(from, to);
                }
            }
        }

        return new WorldSnapshot(graph.build(), heights);
    }

    private static void requireInRegion(SearchRegion region, GridPosition position, String label) {
        if (!region.contains(position)) {
            throw new IllegalArgumentException(label + " fora do piso ou dos limites da região em "
                + position.coordinates());
        }
    }

    private static void requireAvailableRegion(CollisionView world, SearchRegion region) {
        if (region.y() <= world.getBottomY() || region.y() > world.getTopY() - 2) {
            throw new IllegalArgumentException("O piso precisa de um bloco abaixo e dois blocos de altura livre.");
        }
        WorldBorder border = world.getWorldBorder();
        if (region.minX() + 0.5 - HALF_WIDTH < border.getBoundWest()
            || region.maxX() + 0.5 + HALF_WIDTH > border.getBoundEast()
            || region.minZ() + 0.5 - HALF_WIDTH < border.getBoundNorth()
            || region.maxZ() + 0.5 + HALF_WIDTH > border.getBoundSouth()) {
            throw new IllegalArgumentException("A região ultrapassa a borda do mundo.");
        }

        // Collision iteration can read one extra block on each side of the requested box.
        int firstX = (int) Math.floorDiv((long) region.minX() - 1, 16L);
        int lastX = (int) Math.floorDiv((long) region.maxX() + 1, 16L);
        int firstZ = (int) Math.floorDiv((long) region.minZ() - 1, 16L);
        int lastZ = (int) Math.floorDiv((long) region.maxZ() + 1, 16L);
        for (int z = firstZ; z <= lastZ; z++) {
            for (int x = firstX; x <= lastX; x++) {
                if (world.getChunkAsView(x, z) == null) {
                    throw new UnloadedRegionException(x, z);
                }
            }
        }
    }

    private static double findSurface(CollisionView world, double x, double z, int floor) {
        Box supportProbe = new Box(x - HALF_WIDTH, floor - EPSILON, z - HALF_WIDTH,
            x + HALF_WIDTH, floor + CARPET_HEIGHT + EPSILON, z + HALF_WIDTH);
        double highest = Double.NEGATIVE_INFINITY;
        for (var shape : world.getBlockCollisions(null, supportProbe)) {
            for (Box part : shape.getBoundingBoxes()) {
                if (isFloorTop(part, floor) && containsCoordinate(x, part.minX, part.maxX)
                    && containsCoordinate(z, part.minZ, part.maxZ)) {
                    highest = Math.max(highest, part.maxY);
                }
            }
        }
        return highest == Double.NEGATIVE_INFINITY ? Double.NaN : highest;
    }

    private static boolean canCross(CollisionView world, GridPosition from, GridPosition to, double surface) {
        double fromX = from.x() + 0.5;
        double fromZ = from.z() + 0.5;
        double toX = to.x() + 0.5;
        double toZ = to.z() + 0.5;
        Box sweep = bodyAt(fromX, fromZ, surface).union(bodyAt(toX, toZ, surface));
        if (!isClear(world, sweep)) {
            return false;
        }

        int floor = from.y();
        Box supportProbe = new Box(sweep.minX, floor - EPSILON, sweep.minZ, sweep.maxX,
            floor + CARPET_HEIGHT + EPSILON, sweep.maxZ);
        boolean alongX = from.x() != to.x();
        double fixed = alongX ? fromZ : fromX;
        double covered = alongX ? fromX : fromZ;
        double end = alongX ? toX : toZ;
        var intervals = new ArrayList<Box>();
        for (var shape : world.getBlockCollisions(null, supportProbe)) {
            for (Box part : shape.getBoundingBoxes()) {
                if (isFloorTop(part, floor) && (alongX ? containsCoordinate(fixed, part.minZ, part.maxZ)
                    : containsCoordinate(fixed, part.minX, part.maxX))) {
                    intervals.add(part);
                }
            }
        }
        intervals.sort(Comparator.comparingDouble(part -> alongX ? part.minX : part.minZ));
        for (Box interval : intervals) {
            if ((alongX ? interval.minX : interval.minZ) > covered + EPSILON) {
                return false;
            }
            covered = Math.max(covered, alongX ? interval.maxX : interval.maxZ);
            if (covered >= end - EPSILON) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFloorTop(Box part, int floor) {
        return containsCoordinate(part.maxY, floor, floor + CARPET_HEIGHT);
    }

    private static boolean containsCoordinate(double value, double min, double max) {
        return value >= min - EPSILON && value <= max + EPSILON;
    }

    private static Box bodyAt(double x, double z, double surface) {
        return new Box(x - HALF_WIDTH, surface + EPSILON, z - HALF_WIDTH,
            x + HALF_WIDTH, surface + PLAYER_HEIGHT, z + HALF_WIDTH);
    }

    private static boolean isClear(CollisionView world, Box body) {
        if (world.getBlockCollisions(null, body).iterator().hasNext()) {
            return false;
        }
        for (BlockPos position : BlockPos.iterate(
            MathHelper.floor(body.minX), MathHelper.floor(body.minY), MathHelper.floor(body.minZ),
            MathHelper.floor(body.maxX - EPSILON), MathHelper.floor(body.maxY - EPSILON),
            MathHelper.floor(body.maxZ - EPSILON))) {
            if (!world.getFluidState(position).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
