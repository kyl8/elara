package org.elaramc.search;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridPositionTest {
    @Test
    void usesAllThreeCoordinatesForStateIdentity() {
        Set<GridPosition> discovered = Set.of(new GridPosition(-7, 64, 12));

        assertTrue(discovered.contains(new GridPosition(-7, 64, 12)));
        assertFalse(discovered.contains(new GridPosition(-8, 64, 12)));
        assertFalse(discovered.contains(new GridPosition(-7, 65, 12)));
        assertFalse(discovered.contains(new GridPosition(-7, 64, 13)));
    }

    @Test
    void displaysSignedCoordinatesInWorldOrder() {
        assertEquals("(-7, 64, 12)", new GridPosition(-7, 64, 12).coordinates());
    }
}
