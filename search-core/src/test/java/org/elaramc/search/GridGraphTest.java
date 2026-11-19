package org.elaramc.search;

import org.junit.jupiter.api.Test;

import static org.elaramc.search.GridGraph.MoveStatus.BLOCKED_CELL;
import static org.elaramc.search.GridGraph.MoveStatus.BLOCKED_TRANSITION;
import static org.elaramc.search.GridGraph.MoveStatus.OPEN;
import static org.elaramc.search.GridGraph.MoveStatus.OUTSIDE_REGION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GridGraphTest {
    private static final SearchRegion REGION = new SearchRegion(0, 0, 2, 2, 64);
    private static final GridPosition CENTER = cell(1, 1);
    private static final GridPosition NORTH = cell(1, 0);
    private static final GridPosition EAST = cell(2, 1);

    @Test
    void distinguishesOpenMovesBlockedCellsBlockedTransitionsAndBounds() {
        GridGraph graph = GridGraph.builder(REGION, 3, 9)
            .addState(CENTER)
            .addState(NORTH)
            .addState(EAST)
            .connect(CENTER, NORTH)
            .build();

        assertEquals(new GridGraph.Transition(Direction.NORTH, NORTH, OPEN), graph.transition(CENTER, Direction.NORTH));
        assertEquals(OPEN, graph.transition(NORTH, Direction.SOUTH).status());
        assertEquals(BLOCKED_TRANSITION, graph.transition(CENTER, Direction.EAST).status());
        assertEquals(BLOCKED_CELL, graph.transition(CENTER, Direction.SOUTH).status());
        assertEquals(OUTSIDE_REGION, graph.transition(NORTH, Direction.NORTH).status());
        assertThrows(IllegalArgumentException.class, () -> graph.transition(cell(0, 0), Direction.NORTH));
    }

    @Test
    void rejectsInvalidBoundsLimitsAndStates() {
        assertThrows(IllegalArgumentException.class, () -> new SearchRegion(2, 0, 1, 2, 64));
        assertThrows(IllegalArgumentException.class, () -> new SearchRegion(0, 2, 2, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> GridGraph.builder(REGION, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> GridGraph.builder(REGION, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> GridGraph.builder(REGION, 2, 9));
        assertThrows(IllegalArgumentException.class,
            () -> GridGraph.builder(new SearchRegion(0, 0, 0, 2, 64), 2, 9));

        var builder = GridGraph.builder(REGION, 3, 1).addState(CENTER);
        assertThrows(IllegalArgumentException.class, () -> builder.addState(cell(3, 1)));
        assertThrows(IllegalArgumentException.class, () -> builder.addState(new GridPosition(1, 65, 1)));
        assertThrows(IllegalArgumentException.class, () -> builder.addState(NORTH));
        assertEquals(1, builder.addState(CENTER).build().size());
    }

    @Test
    void rejectsMissingEndpointsAndNonCardinalEdges() {
        GridPosition diagonal = cell(0, 0);
        GridPosition distant = cell(2, 0);
        var builder = GridGraph.builder(REGION, 3, 9)
            .addState(CENTER)
            .addState(diagonal)
            .addState(distant)
            .addState(cell(1, 2))
            .addState(cell(1, 0));

        assertThrows(IllegalArgumentException.class, () -> builder.connect(CENTER, EAST));
        assertThrows(IllegalArgumentException.class, () -> builder.connect(CENTER, diagonal));
        assertThrows(IllegalArgumentException.class, () -> builder.connect(cell(1, 0), cell(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> builder.connect(CENTER, CENTER));
        assertEquals(BLOCKED_TRANSITION, builder.build().transition(CENTER, Direction.NORTH).status());
    }

    @Test
    void buildingKeepsAnIndependentSnapshot() {
        var builder = GridGraph.builder(REGION, 3, 9).addState(CENTER).addState(NORTH);
        GridGraph snapshot = builder.build();
        GridGraph updated = builder.addState(EAST).connect(CENTER, NORTH).build();

        assertEquals(2, snapshot.size());
        assertEquals(BLOCKED_TRANSITION, snapshot.transition(CENTER, Direction.NORTH).status());
        assertEquals(3, updated.size());
        assertEquals(OPEN, updated.transition(CENTER, Direction.NORTH).status());
    }

    @Test
    void graphFingerprintTracksTransitionsAndIgnoresBuilderOrder() {
        var first = GridGraph.builder(REGION, 3, 9).addState(CENTER).addState(NORTH);
        var second = GridGraph.builder(REGION, 3, 9).addState(NORTH).addState(CENTER);
        assertEquals(first.build().fingerprint(), second.build().fingerprint());
        assertNotEquals(first.build().fingerprint(), second.connect(NORTH, CENTER).build().fingerprint());
        assertEquals(first.connect(CENTER, NORTH).build().fingerprint(), second.build().fingerprint());
    }

    @Test
    void coordinateExtremesCannotWrapOrBypassSideLimits() {
        var enormous = new SearchRegion(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0, 64);
        assertEquals(4_294_967_296L, enormous.width());
        assertThrows(IllegalArgumentException.class, () -> GridGraph.builder(enormous, Integer.MAX_VALUE, 1));

        GridPosition edge = cell(Integer.MAX_VALUE, Integer.MIN_VALUE);
        var region = new SearchRegion(edge.x(), edge.z(), edge.x(), edge.z(), 64);
        GridGraph graph = GridGraph.builder(region, 1, 1).addState(edge).build();
        assertEquals(OUTSIDE_REGION, graph.transition(edge, Direction.EAST).status());
        assertNull(graph.transition(edge, Direction.EAST).target());
        assertEquals(OUTSIDE_REGION, graph.transition(edge, Direction.NORTH).status());
        assertNull(graph.transition(edge, Direction.NORTH).target());
    }

    private static GridPosition cell(int x, int z) {
        return new GridPosition(x, 64, z);
    }
}
