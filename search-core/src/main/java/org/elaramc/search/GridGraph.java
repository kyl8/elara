package org.elaramc.search;

import java.util.HashMap;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable snapshot of standable cells and traversable moves between them. */
public final class GridGraph {
    private final SearchRegion region;
    private final Map<GridPosition, Integer> edges;
    private volatile String fingerprint;

    private GridGraph(SearchRegion region, Map<GridPosition, Integer> edges) {
        this.region = region;
        this.edges = Map.copyOf(edges);
    }

    public static Builder builder(SearchRegion region, int maxDimension, int maxStates) {
        return new Builder(region, maxDimension, maxStates);
    }

    public SearchRegion region() {
        return region;
    }

    public int size() {
        return edges.size();
    }

    public List<Direction> directions() {
        return directions(region);
    }

    public static List<Direction> directions(SearchRegion region) {
        return region.height() == 1 ? Direction.HORIZONTAL : Direction.ORDER;
    }

    public boolean contains(GridPosition state) {
        return edges.containsKey(state);
    }

    int openDirections(GridPosition state) {
        return edges.get(state);
    }

    /** Stable over the region, standable cells and their actual transitions. */
    public String fingerprint() {
        String cached = fingerprint;
        if (cached != null) {
            return cached;
        }
        try {
            MessageDigest checksum = MessageDigest.getInstance("SHA-256");
            ByteBuffer bounds = ByteBuffer.allocate(24);
            bounds.putInt(region.minX()).putInt(region.minZ()).putInt(region.maxX()).putInt(region.maxZ())
                .putInt(region.y()).putInt(region.maxY());
            checksum.update(bounds.array());
            ByteBuffer cell = ByteBuffer.allocate(16);
            edges.keySet().stream().sorted(Comparator.comparingInt(GridPosition::y)
                    .thenComparingInt(GridPosition::z).thenComparingInt(GridPosition::x))
                .forEach(position -> {
                    cell.clear();
                    cell.putInt(position.x()).putInt(position.y()).putInt(position.z()).putInt(edges.get(position));
                    checksum.update(cell.array());
                });
            cached = HexFormat.of().formatHex(checksum.digest());
            fingerprint = cached;
            return cached;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public Transition transition(GridPosition from, Direction direction) {
        Integer mask = edges.get(from);
        if (mask == null) {
            throw new IllegalArgumentException("Source is not a graph state: " + from.coordinates());
        }
        GridPosition target = direction.from(from);
        if (target == null || !region.contains(target)) {
            return new Transition(direction, target, MoveStatus.OUTSIDE_REGION);
        }
        if ((mask & direction.bit()) != 0) {
            return new Transition(direction, target, MoveStatus.OPEN);
        }
        var status = edges.containsKey(target) ? MoveStatus.BLOCKED_TRANSITION : MoveStatus.BLOCKED_CELL;
        return new Transition(direction, target, status);
    }

    public enum MoveStatus {
        OPEN,
        OUTSIDE_REGION,
        BLOCKED_CELL,
        BLOCKED_TRANSITION
    }

    /** Target is null only when stepping beyond the integer coordinate range. */
    public record Transition(Direction direction, GridPosition target, MoveStatus status) {
    }

    public static final class Builder {
        private final SearchRegion region;
        private final int maxStates;
        private final Map<GridPosition, Integer> edges = new HashMap<>();

        private Builder(SearchRegion region, int maxDimension, int maxStates) {
            this.region = Objects.requireNonNull(region, "region");
            if (maxDimension < 1 || maxStates < 1) {
                throw new IllegalArgumentException("Graph limits must be positive");
            }
            if (region.width() > maxDimension || region.depth() > maxDimension) {
                throw new IllegalArgumentException("Region exceeds the maximum side length of " + maxDimension);
            }
            this.maxStates = maxStates;
        }

        public Builder addState(GridPosition state) {
            if (!region.contains(state)) {
                throw new IllegalArgumentException("State lies outside the region: " + state.coordinates());
            }
            if (!edges.containsKey(state)) {
                if (edges.size() == maxStates) {
                    throw new IllegalArgumentException("Graph exceeds the maximum of " + maxStates + " states");
                }
                edges.put(state, 0);
            }
            return this;
        }

        /** Adds a unit-cost edge in both directions after both states have been added. */
        public Builder connect(GridPosition first, GridPosition second) {
            if (!edges.containsKey(first) || !edges.containsKey(second)) {
                throw new IllegalArgumentException("Both endpoints must be graph states");
            }
            for (Direction direction : directions(region)) {
                if (second.equals(direction.from(first))) {
                    connectFrom(first, direction);
                    for (Direction reverse : directions(region)) {
                        if (first.equals(reverse.from(second))) {
                            connectFrom(second, reverse);
                            break;
                        }
                    }
                    return this;
                }
            }
            throw new IllegalArgumentException("Endpoints must be cardinal neighbors");
        }

        /** Adds a directed movement without assuming its reverse is physically possible. */
        public Builder connectFrom(GridPosition from, Direction direction) {
            var mask = edges.get(from);
            if (mask == null || !edges.containsKey(direction.from(from))) {
                throw new IllegalArgumentException("Both endpoints must be graph states");
            }
            edges.put(from, mask | direction.bit());
            return this;
        }

        public GridGraph build() {
            return new GridGraph(region, edges);
        }
    }
}
