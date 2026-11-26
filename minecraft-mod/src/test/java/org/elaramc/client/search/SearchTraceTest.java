package org.elaramc.client.search;

import org.elaramc.client.world.WorldSnapshot;
import org.elaramc.search.GridGraph;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchEvent;
import org.elaramc.search.SearchRegion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTraceTest {
    @Test
    void cullsDistantAndUnrevealedSectionsWhileKeepingTheRoute() {
        var near = new GridPosition(-1, 79, -1);
        var next = new GridPosition(0, 79, -1);
        var above = new GridPosition(-1, 96, -1);
        var graph = GridGraph.builder(new SearchRegion(-1, -1, 0, -1, 79, 96), 2, 3)
            .addState(near).addState(next).addState(above).connect(near, next).build();
        var snapshot = new WorldSnapshot(graph, Map.of(near, 79.0625));
        var trace = new SearchTrace();
        trace.accept(List.of(new SearchEvent.Discovered(near, null), new SearchEvent.Expanded(near, 1),
            new SearchEvent.Discovered(next, near), new SearchEvent.Discovered(above, near),
            new SearchEvent.SkipsOmitted(12)), snapshot, 8, false);

        assertEquals(3, trace.sections().size());
        assertEquals(12, trace.historyAt(0).totalSkipped());
        assertTrue(trace.historyAt(0).skipped().isEmpty());
        assertEquals(2, trace.sections().stream().filter(section -> section.mayBeVisible(-0.5, 79.0625, -0.5, 64, true)).count());
        assertFalse(trace.sections().stream().anyMatch(section -> section.mayBeVisible(-0.5, 79.0625, -0.5, 64, false)));
        trace.accept(List.of(new SearchEvent.PathReconstructed(List.of(near, next))), snapshot, 8, false);
        trace.tickPath(false);
        assertEquals(2, trace.sections().stream().filter(section -> section.mayBeVisible(-0.5, 79.0625, -0.5, 64, false)).count());
        trace.clear();
        assertTrue(trace.sections().isEmpty());
    }
}
