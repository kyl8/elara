package org.elaramc.search;

import java.util.List;

public sealed interface SearchEvent {
    record Started(SearchAlgorithm algorithm, GridPosition start, GridPosition goal) implements SearchEvent {
    }

    /** The origin is discovered without a predecessor. */
    record Discovered(GridPosition state, GridPosition predecessor) implements SearchEvent {
    }

    record Expanded(GridPosition state, int number) implements SearchEvent {
    }

    record Skipped(GridPosition source, GridGraph.Transition transition) implements SearchEvent {
        public boolean alreadyDiscovered() {
            return transition.status() == GridGraph.MoveStatus.OPEN;
        }
    }

    record GoalFound(GridPosition goal) implements SearchEvent {
    }

    record PathReconstructed(List<GridPosition> path) implements SearchEvent {
        public PathReconstructed {
            path = List.copyOf(path);
        }
    }

    record Exhausted() implements SearchEvent {
    }

    record Reset() implements SearchEvent {
    }
}
