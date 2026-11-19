package org.elaramc.search;

/** A position in the search grid, expressed in integer world coordinates. */
public record GridPosition(int x, int y, int z) {
    public String coordinates() {
        return "(%d, %d, %d)".formatted(x, y, z);
    }
}
