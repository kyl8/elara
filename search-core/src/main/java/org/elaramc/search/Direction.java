package org.elaramc.search;

import java.util.List;

/** Cardinal movement in deterministic search order. */
public enum Direction {
    NORTH(0, -1),
    SOUTH(0, 1),
    EAST(1, 0),
    WEST(-1, 0);

    static final List<Direction> ORDER = List.of(values());

    private final int dx;
    private final int dz;

    Direction(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    /** Returns the adjacent position, or null if the coordinate would overflow. */
    public GridPosition from(GridPosition state) {
        long x = (long) state.x() + dx;
        long z = (long) state.z() + dz;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            return null;
        }
        return new GridPosition((int) x, state.y(), (int) z);
    }

    Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }

    int bit() {
        return 1 << ordinal();
    }
}
