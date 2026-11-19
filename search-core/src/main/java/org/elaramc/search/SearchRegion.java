package org.elaramc.search;

import java.util.Objects;

/** Inclusive horizontal bounds for a single floor. */
public record SearchRegion(int minX, int minZ, int maxX, int maxZ, int y) {
    public SearchRegion {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Region minimum must not exceed its maximum");
        }
    }

    public long width() {
        return (long) maxX - minX + 1;
    }

    public long depth() {
        return (long) maxZ - minZ + 1;
    }

    public boolean contains(GridPosition state) {
        Objects.requireNonNull(state, "state");
        return state.y() == y
            && state.x() >= minX && state.x() <= maxX
            && state.z() >= minZ && state.z() <= maxZ;
    }
}
