package org.elaramc.search;

import java.util.List;

/** Horizontal moves, steps between neighboring heights, and vertical climbing. */
public enum Direction {
    NORTH(0, 0, -1, "Norte"),
    SOUTH(0, 0, 1, "Sul"),
    EAST(1, 0, 0, "Leste"),
    WEST(-1, 0, 0, "Oeste"),
    NORTH_UP(0, 1, -1, "Subir ao norte"),
    SOUTH_UP(0, 1, 1, "Subir ao sul"),
    EAST_UP(1, 1, 0, "Subir a leste"),
    WEST_UP(-1, 1, 0, "Subir a oeste"),
    NORTH_DOWN(0, -1, -1, "Descer ao norte"),
    SOUTH_DOWN(0, -1, 1, "Descer ao sul"),
    EAST_DOWN(1, -1, 0, "Descer a leste"),
    WEST_DOWN(-1, -1, 0, "Descer a oeste"),
    UP(0, 1, 0, "Subir escada"),
    DOWN(0, -1, 0, "Descer escada");

    static final List<Direction> ORDER = List.of(values());
    static final List<Direction> HORIZONTAL = List.of(NORTH, SOUTH, EAST, WEST);

    private final int dx;
    private final int dy;
    private final int dz;
    private final String label;

    Direction(int dx, int dy, int dz, String label) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.label = label;
    }

    /** Returns the adjacent position, or null if the coordinate would overflow. */
    public GridPosition from(GridPosition state) {
        long x = (long) state.x() + dx;
        long y = (long) state.y() + dy;
        long z = (long) state.z() + dz;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
            || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            return null;
        }
        return new GridPosition((int) x, (int) y, (int) z);
    }

    public boolean vertical() {
        return dx == 0 && dz == 0;
    }

    public String label() {
        return label;
    }

    int bit() {
        return 1 << ordinal();
    }
}
