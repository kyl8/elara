package org.elaramc.search;

import java.util.OptionalInt;

/** Current is null before the first expansion. Path length counts moves. */
public record SearchMetrics(
    int expanded,
    int discovered,
    int frontierSize,
    GridPosition current,
    GridPosition goal,
    OptionalInt pathLength
) {
}
