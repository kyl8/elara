package org.elaramc.search;

import java.util.HashMap;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable snapshot of standable cells and traversable moves between them. */
public final class GridGraph {
    private final SearchRegion region;
    private final Map<GridPosition, Integer> edges;

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

    public boolean contains(GridPosition state) {
        return edges.containsKey(state);
    }

    /** Stable over the region, standable cells and their actual transitions. */
    public String fingerprint() {
        try {
            MessageDigest checksum = MessageDigest.getInstance("SHA-256");
            ByteBuffer bounds = ByteBuffer.allocate(20);
            bounds.putInt(region.minX()).putInt(region.minZ()).putInt(region.maxX()).putInt(region.maxZ())
                .putInt(region.y());
            checksum.update(bounds.array());
            ByteBuffer cell = ByteBuffer.allocate(9);
            edges.keySet().stream().sorted(Comparator.comparingInt(GridPosition::z).thenComparingInt(GridPosition::x))
                .forEach(position -> {
                    cell.clear();
                    cell.putInt(position.x()).putInt(position.z()).put(edges.get(position).byteValue());
                    checksum.update(cell.array());
                });
            return HexFormat.of().formatHex(checksum.digest());
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
            Integer firstMask = edges.get(first);
            Integer secondMask = edges.get(second);
            if (firstMask == null || secondMask == null) {
                throw new IllegalArgumentException("Both endpoints must be graph states");
            }
            for (Direction direction : Direction.ORDER) {
                if (second.equals(direction.from(first))) {
                    edges.put(first, firstMask | direction.bit());
                    edges.put(second, secondMask | direction.opposite().bit());
                    return this;
                }
            }
            throw new IllegalArgumentException("Endpoints must be cardinal neighbors");
        }

        public GridGraph build() {
            return new GridGraph(region, edges);
        }
    }
}
