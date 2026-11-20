package org.elaramc.client.world;

import net.minecraft.block.LadderBlock;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

/** Takes a snapshot from already loaded block collision data. */
public final class WorldScanner {
    public static final int MAX_SIDE = 64;
    public static final int MAX_STATES = MAX_SIDE * MAX_SIDE;
    private static final int HEIGHT_MARGIN = 6;
    private static final int MAX_SCAN_VOLUME = 196_608;
    public static final double STEP_HEIGHT = 0.6;
    private static final double JUMP_RISE = 1.25;

    private static final double HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double EPSILON = 1.0e-6;

    public static final class UnloadedRegionException extends IllegalArgumentException {
        private UnloadedRegionException(int x, int z) {
            super("Chunk (" + x + ", " + z + ") descarregado. Aproxime-se ou reduza a região.");
        }
    }

    /** Grow around both markers without asking the client to load any extra chunks. */
    public SearchRegion automaticRegion(CollisionView world, GridPosition start, GridPosition goal,
                                        int maxSide, int maxStates) {
        var region = new SearchRegion(Math.min(start.x(), goal.x()), Math.min(start.z(), goal.z()),
            Math.max(start.x(), goal.x()), Math.max(start.z(), goal.z()),
            Math.min(start.y(), goal.y()), Math.max(start.y(), goal.y()));
        requireAvailableRegion(world, region);
        requireSize(region, maxSide);
        int low = Math.max(world.getBottomY() + 1, region.y() - HEIGHT_MARGIN);
        int high = Math.min(world.getTopY() - 3, region.maxY() + HEIGHT_MARGIN);
        if (region.width() * region.depth() * (high - low + 1L) <= MAX_SCAN_VOLUME) {
            region = new SearchRegion(region.minX(), region.minZ(), region.maxX(), region.maxZ(), low, high);
        }
        while (true) {
            SearchRegion previous = region;
            region = extend(world, region, -1, 0, 0, 0, maxSide, maxStates);
            region = extend(world, region, 0, 0, 1, 0, maxSide, maxStates);
            region = extend(world, region, 0, -1, 0, 0, maxSide, maxStates);
            region = extend(world, region, 0, 0, 0, 1, maxSide, maxStates);
            if (region == previous) {
                return region;
            }
        }
    }

    private static SearchRegion extend(CollisionView world, SearchRegion region,
                                       int west, int north, int east, int south, int maxSide, int maxStates) {
        var candidate = new SearchRegion(region.minX() + west, region.minZ() + north, region.maxX() + east,
            region.maxZ() + south, region.y(), region.maxY());
        if (candidate.width() > maxSide || candidate.depth() > maxSide
            || candidate.width() * candidate.depth() > maxStates
            || candidate.width() * candidate.depth() * candidate.height() > MAX_SCAN_VOLUME) {
            return region;
        }
        try {
            requireAvailableRegion(world, candidate);
            return candidate;
        } catch (IllegalArgumentException unavailable) {
            return region;
        }
    }

    public WorldSnapshot scan(CollisionView world, SearchRegion region, GridPosition start, GridPosition goal,
                              int maxSide, int maxStates) {
        if (maxSide < 1 || maxSide > MAX_SIDE || maxStates < 1 || maxStates > MAX_STATES) {
            throw new IllegalArgumentException("Os limites da região estão fora da faixa permitida.");
        }
        requireSize(region, maxSide);
        var graph = GridGraph.builder(region, maxSide, maxStates);
        requireInRegion(region, start, "Início");
        requireInRegion(region, goal, "Meta");
        requireAvailableRegion(world, region);

        var heights = new HashMap<GridPosition, Double>();
        var ladders = new HashSet<GridPosition>();
        double[] surfaces = new double[(int) region.height()];
        for (int dz = 0; dz < region.depth(); dz++) {
            for (int dx = 0; dx < region.width(); dx++) {
                int x = region.minX() + dx;
                int z = region.minZ() + dz;
                columnSurfaces(world, x + 0.5, z + 0.5, region, surfaces);
                for (int index = 0; index < surfaces.length; index++) {
                    int y = region.y() + index;
                    boolean ladder = region.height() > 1 && world.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof LadderBlock;
                    double surface = surfaces[index];
                    if (ladder && Double.isNaN(surface)) {
                        surface = y;
                    }
                    if (!Double.isNaN(surface) && isClear(world, bodyAt(x + 0.5, z + 0.5, surface))) {
                        if (heights.size() == maxStates) {
                            throw new IllegalArgumentException("Limite de estados transitáveis excedido (" + maxStates
                                + "). Reduza a região ou use /elara limits.");
                        }
                        var position = new GridPosition(x, y, z);
                        graph.addState(position);
                        heights.put(position, surface);
                        if (ladder) {
                            ladders.add(position);
                        }
                    }
                }
            }
        }
        if (!heights.containsKey(start)) {
            throw new IllegalArgumentException("Início em posição bloqueada ou sem apoio em " + start.coordinates());
        }
        if (!heights.containsKey(goal)) {
            throw new IllegalArgumentException("Meta em posição bloqueada ou sem apoio em " + goal.coordinates());
        }

        var directions = GridGraph.directions(region);
        for (var entry : heights.entrySet()) {
            GridPosition from = entry.getKey();
            for (Direction direction : directions) {
                GridPosition to = direction.from(from);
                Double targetHeight = heights.get(to);
                if (targetHeight != null && canCross(world, direction, from, to, entry.getValue(),
                    targetHeight, ladders.contains(from) || ladders.contains(to))) {
                    graph.connectFrom(from, direction);
                }
            }
        }

        return new WorldSnapshot(graph.build(), heights);
    }

    private static void requireSize(SearchRegion region, int maxSide) {
        if (region.width() > maxSide || region.depth() > maxSide) {
            throw new IllegalArgumentException("Os pontos precisam caber em uma região de " + maxSide + " blocos por lado.");
        }
        if (region.width() * region.depth() * region.height() > MAX_SCAN_VOLUME) {
            throw new IllegalArgumentException("A região entre os pontos é grande demais. Divida o caminho em trechos.");
        }
    }

    private static void requireInRegion(SearchRegion region, GridPosition position, String label) {
        if (!region.contains(position)) {
            throw new IllegalArgumentException(label + " fora do piso ou dos limites da região em "
                + position.coordinates());
        }
    }

    private static void requireAvailableRegion(CollisionView world, SearchRegion region) {
        if (region.y() <= world.getBottomY() || region.maxY() > world.getTopY() - 2) {
            throw new IllegalArgumentException("Escolha pontos com apoio e espaço para o jogador.");
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

    private static void columnSurfaces(CollisionView world, double x, double z, SearchRegion region, double[] surfaces) {
        Arrays.fill(surfaces, Double.NaN);
        Box supportProbe = new Box(x - HALF_WIDTH, region.y() - EPSILON, z - HALF_WIDTH,
            x + HALF_WIDTH, region.maxY() + 1.0, z + HALF_WIDTH);
        for (var shape : world.getBlockCollisions(null, supportProbe)) {
            for (Box part : shape.getBoundingBoxes()) {
                int index = MathHelper.floor(part.maxY + EPSILON) - region.y();
                if (index >= 0 && index < surfaces.length && containsCoordinate(x, part.minX, part.maxX)
                    && containsCoordinate(z, part.minZ, part.maxZ)
                    && (Double.isNaN(surfaces[index]) || part.maxY > surfaces[index])) {
                    surfaces[index] = part.maxY;
                }
            }
        }
    }

    private static boolean canCross(CollisionView world, Direction direction, GridPosition from, GridPosition to,
                                    double fromHeight, double toHeight, boolean ladder) {
        double fromX = from.x() + 0.5;
        double fromZ = from.z() + 0.5;
        double toX = to.x() + 0.5;
        double toZ = to.z() + 0.5;
        double higher = Math.max(fromHeight, toHeight);
        Box sweep = bodyAt(fromX, fromZ, higher).union(bodyAt(toX, toZ, higher));
        if (direction.vertical()) {
            return ladder && isClear(world, bodyAt(fromX, fromZ, fromHeight).union(bodyAt(toX, toZ, toHeight)));
        }
        if (!isClear(world, sweep)) {
            return false;
        }
        double rise = toHeight - fromHeight;
        if (rise > STEP_HEIGHT) {
            if (rise > 1.0 + EPSILON) {
                return false;
            }
            // Reserve space for the jump above the lower block and through the upper landing.
            double ceiling = fromHeight + JUMP_RISE + PLAYER_HEIGHT;
            return isClear(world, new Box(sweep.minX, sweep.minY, sweep.minZ, sweep.maxX, ceiling, sweep.maxZ));
        }
        if (rise < -STEP_HEIGHT || ladder) {
            return rise >= -1.0 - EPSILON;
        }

        double lower = Math.min(fromHeight, toHeight);
        Box supportProbe = new Box(sweep.minX, lower - EPSILON, sweep.minZ, sweep.maxX,
            higher + EPSILON, sweep.maxZ);
        boolean alongX = from.x() != to.x();
        double fixed = alongX ? fromZ : fromX;
        double covered = alongX ? Math.min(fromX, toX) : Math.min(fromZ, toZ);
        double end = alongX ? Math.max(fromX, toX) : Math.max(fromZ, toZ);
        var intervals = new ArrayList<Box>();
        for (var shape : world.getBlockCollisions(null, supportProbe)) {
            for (Box part : shape.getBoundingBoxes()) {
                if (containsCoordinate(part.maxY, lower, higher) && (alongX ? containsCoordinate(fixed, part.minZ, part.maxZ)
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
