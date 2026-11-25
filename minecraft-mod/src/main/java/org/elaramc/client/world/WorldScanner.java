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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Builds a graph from loaded block collision data on the client thread. */
public final class WorldScanner {
    public static final int MAX_SIDE = 128;
    public static final int MAX_STATES = 32_768;
    private static final int MAX_SCAN_VOLUME = 262_144;
    private static final int HEIGHT_MARGIN = 6;
    private static final int FIRST_MARGIN = 4;
    private static final int MAX_MARGIN_STEP = 16;
    private static final int WEST = 1, EAST = 2, NORTH = 4, SOUTH = 8;
    private static final int ALL_SIDES = WEST | EAST | NORTH | SOUTH;
    public static final double STEP_HEIGHT = 0.6;
    private static final double JUMP_RISE = 1.25;

    private static final double HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double EPSILON = 1.0e-6;

    public static final class UnloadedRegionException extends IllegalArgumentException {
        private UnloadedRegionException(int x, int z) {
            super("Chunk (" + x + ", " + z + ") descarregado. Aproxime-se da região ou aumente a distância de renderização.");
        }
    }

    public Scan begin(CollisionView world, SearchRegion requested, GridPosition start, GridPosition goal,
                      int maxSide, int maxStates) {
        if (maxSide < 1 || maxSide > MAX_SIDE || maxStates < 1 || maxStates > MAX_STATES) {
            throw new IllegalArgumentException("Os limites da região estão fora da faixa permitida.");
        }
        return new Scan(world, requested, start, goal, maxSide, maxStates);
    }

    private static SearchRegion automaticRegion(CollisionView world, GridPosition start, GridPosition goal, int maxSide) {
        var minimum = new SearchRegion(Math.min(start.x(), goal.x()), Math.min(start.z(), goal.z()),
            Math.max(start.x(), goal.x()), Math.max(start.z(), goal.z()),
            Math.min(start.y(), goal.y()), Math.max(start.y(), goal.y()));
        requireSize(minimum, maxSide);
        requireAvailableRegion(world, minimum);

        for (int margin = HEIGHT_MARGIN; margin > 0; margin--) {
            int low = Math.max(world.getBottomY() + 1, minimum.y() - margin);
            int high = Math.min(world.getTopY() - 2, minimum.maxY() + margin);
            var candidate = new SearchRegion(minimum.minX(), minimum.minZ(), minimum.maxX(), minimum.maxZ(), low, high);
            if (volume(candidate) <= MAX_SCAN_VOLUME) {
                minimum = candidate;
                break;
            }
        }
        return grow(world, minimum, maxSide, FIRST_MARGIN, ALL_SIDES);
    }

    private static SearchRegion grow(CollisionView world, SearchRegion region, int maxSide, int margin, int sides) {
        if ((sides & WEST) != 0) region = extend(world, region, -1, 0, 0, 0, maxSide, margin);
        if ((sides & EAST) != 0) region = extend(world, region, 0, 0, 1, 0, maxSide, margin);
        if ((sides & NORTH) != 0) region = extend(world, region, 0, -1, 0, 0, maxSide, margin);
        if ((sides & SOUTH) != 0) region = extend(world, region, 0, 0, 0, 1, maxSide, margin);
        return region;
    }

    private static SearchRegion extend(CollisionView world, SearchRegion region,
                                       int west, int north, int east, int south, int maxSide, int margin) {
        // Adding a strip is monotone. Keep the largest strip that fits the bounds and loaded chunks.
        int low = 0;
        int high = margin;
        SearchRegion best = region;
        while (low < high) {
            int step = (low + high + 1) / 2;
            var candidate = new SearchRegion(region.minX() + step * west, region.minZ() + step * north,
                region.maxX() + step * east, region.maxZ() + step * south, region.y(), region.maxY());
            if (candidate.width() <= maxSide && candidate.depth() <= maxSide && volume(candidate) <= MAX_SCAN_VOLUME
                && available(world, candidate)) {
                low = step;
                best = candidate;
            } else {
                high = step - 1;
            }
        }
        return best;
    }

    private static boolean available(CollisionView world, SearchRegion region) {
        try {
            requireAvailableRegion(world, region);
            return true;
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
    }

    private enum Phase { COLUMNS, REACHABILITY, STATES, EDGES, COMPLETE }

    private static final class Cell {
        final GridPosition position;
        final double surface;
        final boolean ladder;
        int checked;
        int open;
        int visitedAttempt;

        Cell(GridPosition position, double surface, boolean ladder) {
            this.position = position;
            this.surface = surface;
            this.ladder = ladder;
        }
    }

    /** A budgeted job. World access stays on the thread that owns the client world. */
    public static final class Scan {
        private final CollisionView world;
        private final GridPosition start;
        private final GridPosition goal;
        private final int maxSide;
        private final int maxStates;
        private final boolean automatic;
        private final double[] surfaces;
        private final BlockPos.Mutable block = new BlockPos.Mutable();
        private final Map<GridPosition, Cell> cells = new HashMap<>();
        private final ArrayDeque<Cell> frontier = new ArrayDeque<>();
        private final long started = System.nanoTime();
        private SearchRegion region;
        private SearchRegion previous;
        private List<Direction> directions;
        private Phase phase = Phase.COLUMNS;
        private Iterator<Cell> iterator;
        private GridGraph.Builder builder;
        private Map<GridPosition, Double> heights;
        private WorldSnapshot result;
        private int column;
        private int scannedColumns;
        private int attempts = 1;
        private int margin = FIRST_MARGIN;
        private int reachedSides;
        private boolean boundaryLimited;

        private Scan(CollisionView world, SearchRegion requested, GridPosition start, GridPosition goal,
                     int maxSide, int maxStates) {
            this.world = world;
            this.start = start;
            this.goal = goal;
            this.maxSide = maxSide;
            this.maxStates = maxStates;
            automatic = requested == null;
            region = automatic ? automaticRegion(world, start, goal, maxSide) : requested;
            requireSize(region, maxSide);
            requireInRegion(region, start, "Início");
            requireInRegion(region, goal, "Meta");
            requireAvailableRegion(world, region);
            directions = GridGraph.directions(region);
            surfaces = new double[(int) region.height()];
        }

        /** Returns true once a complete, immutable snapshot is available. */
        public boolean advance(int maxWork, long budgetNanos) {
            if (maxWork < 1 || budgetNanos < 1) {
                throw new IllegalArgumentException("Scan budget must be positive");
            }
            if (phase == Phase.COMPLETE) {
                return true;
            }
            long begin = System.nanoTime();
            requireAvailableRegion(world, region);
            for (int work = 0; work < maxWork; work++) {
                switch (phase) {
                    case COLUMNS -> nextColumn();
                    case REACHABILITY -> probe();
                    case STATES -> addState();
                    case EDGES -> addEdges();
                    case COMPLETE -> { return true; }
                }
                if (System.nanoTime() - begin >= budgetNanos) {
                    break;
                }
            }
            return phase == Phase.COMPLETE;
        }

        private void nextColumn() {
            int width = (int) region.width();
            if (column == width * region.depth()) {
                Cell origin = requireState(start, "Início");
                requireState(goal, "Meta");
                if (automatic) {
                    origin.visitedAttempt = attempts;
                    frontier.add(origin);
                    phase = Phase.REACHABILITY;
                } else {
                    startGraph();
                }
                return;
            }
            int x = region.minX() + column % width;
            int z = region.minZ() + column++ / width;
            if (previous != null && x >= previous.minX() && x <= previous.maxX()
                && z >= previous.minZ() && z <= previous.maxZ()) {
                return;
            }
            scannedColumns++;
            columnSurfaces(world, x + 0.5, z + 0.5, region, surfaces);
            for (int index = 0; index < surfaces.length; index++) {
                int y = region.y() + index;
                boolean ladder = surfaces.length > 1 && world.getBlockState(block.set(x, y, z)).getBlock() instanceof LadderBlock;
                double surface = surfaces[index];
                if (ladder && Double.isNaN(surface)) {
                    surface = y;
                }
                if (!Double.isNaN(surface) && isClear(world, bodyAt(x + 0.5, z + 0.5, surface))) {
                    if (cells.size() == maxStates) {
                        String suggestion = maxStates < MAX_STATES ? " Aumente o segundo valor de /elara limits ou delimite"
                            : " Delimite";
                        throw new IllegalArgumentException("Limite de estados transitáveis excedido (" + maxStates + ") em "
                            + dimensions(region) + "." + suggestion + " a região e os andares com /elara region.");
                    }
                    var position = new GridPosition(x, y, z);
                    cells.put(position, new Cell(position, surface, ladder));
                }
            }
        }

        private Cell requireState(GridPosition position, String label) {
            Cell cell = cells.get(position);
            if (cell == null) {
                throw new IllegalArgumentException(label + " em posição bloqueada ou sem apoio em " + position.coordinates() + ".");
            }
            return cell;
        }

        private void probe() {
            Cell from = frontier.poll();
            if (from == null) {
                SearchRegion next = grow(world, region, maxSide, margin, reachedSides);
                if (next.equals(region)) {
                    boundaryLimited = reachedSides != 0;
                    startGraph();
                } else {
                    previous = region;
                    region = next;
                    margin = Math.min(MAX_MARGIN_STEP, margin * 2);
                    column = 0;
                    reachedSides = 0;
                    attempts++;
                    phase = Phase.COLUMNS;
                }
                return;
            }
            GridPosition p = from.position;
            if (p.equals(goal)) {
                startGraph();
                return;
            }
            if (p.x() == region.minX()) reachedSides |= WEST;
            if (p.x() == region.maxX()) reachedSides |= EAST;
            if (p.z() == region.minZ()) reachedSides |= NORTH;
            if (p.z() == region.maxZ()) reachedSides |= SOUTH;
            for (Direction direction : directions) {
                Cell to = cells.get(direction.from(p));
                if (to != null && to.visitedAttempt != attempts && open(from, to, direction)) {
                    to.visitedAttempt = attempts;
                    frontier.add(to);
                }
            }
        }

        private boolean open(Cell from, Cell to, Direction direction) {
            int bit = 1 << direction.ordinal();
            if ((from.checked & bit) == 0) {
                from.checked |= bit;
                if (canCross(world, direction, from.position, to.position, from.surface, to.surface, from.ladder || to.ladder)) {
                    from.open |= bit;
                }
            }
            return (from.open & bit) != 0;
        }

        private void startGraph() {
            frontier.clear();
            builder = GridGraph.builder(region, maxSide, maxStates);
            heights = new HashMap<>(cells.size());
            iterator = cells.values().iterator();
            phase = Phase.STATES;
        }

        private void addState() {
            if (!iterator.hasNext()) {
                iterator = cells.values().iterator();
                phase = Phase.EDGES;
                return;
            }
            Cell cell = iterator.next();
            builder.addState(cell.position);
            heights.put(cell.position, cell.surface);
        }

        private void addEdges() {
            if (!iterator.hasNext()) {
                result = new WorldSnapshot(builder.build(), heights);
                iterator = null;
                cells.clear();
                heights = null;
                builder = null;
                phase = Phase.COMPLETE;
                return;
            }
            Cell from = iterator.next();
            for (Direction direction : directions) {
                Cell to = cells.get(direction.from(from.position));
                if (to != null && open(from, to, direction)) {
                    builder.connectFrom(from.position, direction);
                }
            }
        }

        public WorldSnapshot result() { return result; }
        public int columns() { return scannedColumns; }
        public int attempts() { return attempts; }
        public long elapsedMillis() { return (System.nanoTime() - started) / 1_000_000; }
        public boolean boundaryLimited() { return boundaryLimited; }
    }

    public static String dimensions(SearchRegion region) {
        return region.width() + " × " + region.depth() + " × " + region.height() + " (X × Z × Y)";
    }

    private static long volume(SearchRegion region) {
        return region.width() * region.depth() * region.height();
    }

    private static void requireSize(SearchRegion region, int maxSide) {
        if (region.width() > maxSide || region.depth() > maxSide) {
            throw new IllegalArgumentException("A região pede " + region.width() + " × " + region.depth()
                + " blocos em X/Z. O limite é " + maxSide + " por lado (máximo " + MAX_SIDE
                + "). Ajuste /elara limits ou divida a rota.");
        }
        if (volume(region) > MAX_SCAN_VOLUME) {
            throw new IllegalArgumentException("A região " + dimensions(region) + " ultrapassa o volume de "
                + MAX_SCAN_VOLUME + " posições. Delimite os andares ou divida a rota.");
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
