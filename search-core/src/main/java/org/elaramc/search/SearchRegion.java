package org.elaramc.search;

import java.util.Objects;

/** Inclusive bounds. The original y field remains the lower bound for saved scenarios. */
public record SearchRegion(int minX, int minZ, int maxX, int maxZ, int y, Integer maxY) {
    public SearchRegion {
        maxY = maxY == null ? y : maxY;
        if (minX > maxX || minZ > maxZ || y > maxY) {
            throw new IllegalArgumentException("Region minimum must not exceed its maximum");
        }
    }

    public SearchRegion(int minX, int minZ, int maxX, int maxZ, int y) {
        this(minX, minZ, maxX, maxZ, y, y);
    }

    public long width() {
        return (long) maxX - minX + 1;
    }

    public long depth() {
        return (long) maxZ - minZ + 1;
    }

    public long height() {
        return (long) maxY - y + 1;
    }

    public boolean contains(GridPosition state) {
        Objects.requireNonNull(state, "state");
        return state.y() >= y && state.y() <= maxY
            && state.x() >= minX && state.x() <= maxX
            && state.z() >= minZ && state.z() <= maxZ;
    }
}
