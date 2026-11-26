package org.elaramc.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;

import static org.elaramc.search.SearchAlgorithm.BFS;
import static org.elaramc.search.SearchAlgorithm.DFS;
import static org.elaramc.search.SearchStatus.EXHAUSTED;
import static org.elaramc.search.SearchStatus.FOUND;
import static org.elaramc.search.SearchStatus.PAUSED;
import static org.elaramc.search.SearchStatus.READY;
import static org.elaramc.search.SearchStatus.RUNNING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchSessionTest {
    private static final GridPosition START = cell(0, 1);
    private static final GridPosition A = cell(0, 0);
    private static final GridPosition B = cell(1, 0);
    private static final GridPosition GOAL = cell(1, 1);
    private static final GridPosition ISOLATED = cell(2, 1);

    @Test
    void bfsFindsMinimumPathWhileDfsFollowsTheLongerBranch() {
        GridGraph graph = loop();
        var bfs = new SearchSession(graph, START, GOAL, BFS);
        var dfs = new SearchSession(graph, START, GOAL, DFS);

        assertEquals(List.of(START, A, GOAL), finish(bfs, FOUND));
        assertEquals(List.of(START, A, B, GOAL), finish(dfs, FOUND));
        assertEquals(List.of(START, GOAL), bfs.path());
        assertEquals(List.of(START, A, B, GOAL), dfs.path());
        assertEquals(new SearchMetrics(3, 4, 1, GOAL, GOAL, OptionalInt.of(1)), bfs.metrics());
        assertEquals(new SearchMetrics(4, 4, 0, GOAL, GOAL, OptionalInt.of(3)), dfs.metrics());
        assertValidPath(graph, bfs.path());
        assertValidPath(graph, dfs.path());
        assertEquals(START, bfs.predecessors().get(GOAL));
        assertEquals(B, dfs.predecessors().get(GOAL));
    }

    @ParameterizedTest
    @EnumSource(SearchAlgorithm.class)
    void eachExpansionUsesNorthSouthEastWestRegardlessOfEdgeInsertion(SearchAlgorithm algorithm) {
        var center = cell(1, 1);
        var north = cell(1, 0);
        var south = cell(1, 2);
        var east = cell(2, 1);
        var west = cell(0, 1);
        var isolated = cell(2, 2);
        var graph = GridGraph.builder(new SearchRegion(0, 0, 2, 2, 64), 3, 9)
            .addState(center).addState(west).addState(east).addState(south).addState(north).addState(isolated)
            .connect(center, west).connect(center, east).connect(center, south).connect(center, north)
            .build();
        var session = new SearchSession(graph, center, isolated, algorithm);

        assertEquals(List.of(center, north, south, east, west), finish(session, EXHAUSTED));
    }

    @ParameterizedTest
    @EnumSource(SearchAlgorithm.class)
    void testsTheGoalOnExpansionAndHandlesStartEqualToGoal(SearchAlgorithm algorithm) {
        var session = new SearchSession(loop(), START, START, algorithm);

        assertEquals(List.of(
            new SearchEvent.Started(algorithm, START, START),
            new SearchEvent.Discovered(START, null),
            new SearchEvent.Expanded(START, 1),
            new SearchEvent.GoalFound(START),
            new SearchEvent.PathReconstructed(List.of(START))
        ), session.step());
        assertEquals(new SearchMetrics(1, 1, 0, START, START, OptionalInt.of(0)), session.metrics());
        assertEquals(FOUND, session.status());
        assertTerminalStable(session);
    }

    @ParameterizedTest
    @EnumSource(SearchAlgorithm.class)
    void exhaustsTheReachableComponentExactlyOnceEvenWithCycles(SearchAlgorithm algorithm) {
        var session = new SearchSession(loop(), START, ISOLATED, algorithm);
        var events = new ArrayList<SearchEvent>();

        assertEquals(EXHAUSTED, session.runToCompletion(events::add));
        List<GridPosition> order = expandedStates(events);
        assertEquals(4, order.size());
        assertEquals(new HashSet<>(List.of(START, A, B, GOAL)), new HashSet<>(order));
        assertEquals(4, events.stream().filter(SearchEvent.Discovered.class::isInstance).count());
        assertTrue(events.stream().anyMatch(event -> event instanceof SearchEvent.Skipped skip && skip.alreadyDiscovered()));
        assertInstanceOf(SearchEvent.Exhausted.class, events.get(events.size() - 1));
        assertEquals(new SearchMetrics(4, 4, 0, order.get(3), ISOLATED, OptionalInt.empty()), session.metrics());
        assertTrue(session.path().isEmpty());
        assertTrue(session.agendaPage(0, 10).isEmpty());
        assertTerminalStable(session);
    }

    @ParameterizedTest
    @EnumSource(SearchAlgorithm.class)
    void omittingSkipDetailsKeepsExpansionOrderAndPath(SearchAlgorithm algorithm) {
        GridGraph graph = loop();
        var detailed = new SearchSession(graph, START, GOAL, algorithm);
        var compact = new SearchSession(graph, START, GOAL, algorithm, 0);
        while (detailed.status() != FOUND) {
            List<SearchEvent> full = detailed.step();
            List<SearchEvent> summary = compact.step();
            assertEquals(expandedStates(full), expandedStates(summary));
            assertEquals(full.stream().filter(SearchEvent.Skipped.class::isInstance).count(), skipCount(summary));
            assertEquals(detailed.metrics(), compact.metrics());
        }
        assertEquals(detailed.path(), compact.path());
    }

    @Test
    void longDepthFirstBacktrackKeepsOnlyTheRequestedSkipDetails() {
        int length = 8_192;
        var goal = cell(length, 0);
        var builder = GridGraph.builder(new SearchRegion(0, 0, length, 0, 64, 65), length + 1, length + 1);
        builder.addState(goal).addState(cell(0, 0));
        for (int x = 1; x < length; x++) {
            builder.addState(cell(x, 0)).connect(cell(x - 1, 0), cell(x, 0));
        }
        var session = new SearchSession(builder.build(), cell(0, 0), goal, DFS, 32);
        for (int x = 1; x < length; x++) session.step();
        List<SearchEvent> last = session.step();

        assertEquals(EXHAUSTED, session.status());
        assertEquals(length, session.metrics().expanded());
        assertEquals(32, last.stream().filter(SearchEvent.Skipped.class::isInstance).count());
        assertEquals(14 + 11 * (length - 1), skipCount(last));
        assertEquals(35, last.size());
    }

    private static int skipCount(List<SearchEvent> events) {
        return events.stream().mapToInt(event -> event instanceof SearchEvent.Skipped ? 1
            : event instanceof SearchEvent.SkipsOmitted omitted ? omitted.count() : 0).sum();
    }

    @ParameterizedTest
    @EnumSource(SearchAlgorithm.class)
    void aSingleStepExpandsOneStateAndPausingStopsAutomaticAdvancement(SearchAlgorithm algorithm) {
        var session = new SearchSession(loop(), START, GOAL, algorithm);
        assertEquals(READY, session.status());
        assertEquals(new SearchMetrics(0, 1, 1, null, GOAL, OptionalInt.empty()), session.metrics());
        assertTrue(session.advance().isEmpty());
        assertEquals(List.of(START), expandedStates(session.step()));
        assertEquals(PAUSED, session.status());
        assertEquals(1, session.metrics().expanded());

        session.resume();
        assertEquals(RUNNING, session.status());
        assertEquals(List.of(A), expandedStates(session.advance()));
        session.pause();
        SearchMetrics paused = session.metrics();
        assertEquals(PAUSED, session.status());
        assertTrue(session.advance().isEmpty());
        assertEquals(paused, session.metrics());
        assertEquals(1, expandedStates(session.step()).size());
        assertEquals(3, session.metrics().expanded());
    }

    @Test
    void bfsPublishesOrderedEventsAndReportsWhyTransitionsAreSkipped() {
        var session = new SearchSession(loop(), START, GOAL, BFS);

        assertEquals(List.of(
            new SearchEvent.Started(BFS, START, GOAL),
            new SearchEvent.Discovered(START, null),
            new SearchEvent.Expanded(START, 1),
            new SearchEvent.Discovered(A, START),
            new SearchEvent.Skipped(START, new GridGraph.Transition(
                Direction.SOUTH, cell(0, 2), GridGraph.MoveStatus.OUTSIDE_REGION)),
            new SearchEvent.Discovered(GOAL, START),
            new SearchEvent.Skipped(START, new GridGraph.Transition(
                Direction.WEST, cell(-1, 1), GridGraph.MoveStatus.OUTSIDE_REGION))
        ), session.step());
        assertEquals(List.of(A, GOAL), session.agendaPage(0, 2));
        assertEquals(List.of(GOAL), session.agendaPage(1, 1));
        assertTrue(session.agendaPage(3, 2).isEmpty());

        var secondStep = session.step();
        assertEquals(new SearchEvent.Expanded(A, 2), secondStep.get(0));
        var skipped = assertInstanceOf(SearchEvent.Skipped.class, secondStep.get(1));
        assertFalse(skipped.alreadyDiscovered());
        assertTrue(secondStep.contains(new SearchEvent.Skipped(A,
            new GridGraph.Transition(Direction.SOUTH, START, GridGraph.MoveStatus.OPEN))));
        assertEquals(List.of(
            new SearchEvent.Expanded(GOAL, 3),
            new SearchEvent.GoalFound(GOAL),
            new SearchEvent.PathReconstructed(List.of(START, GOAL))
        ), session.step());
    }

    @Test
    void dfsStackRetainsParentFramesAndBacktracksToAnUnexploredSibling() {
        GridPosition westGoal = cell(-1, 1);
        var graph = GridGraph.builder(new SearchRegion(-1, 0, 0, 1, 64), 2, 4)
            .addState(START).addState(A).addState(westGoal)
            .connect(START, westGoal).connect(START, A)
            .build();
        var session = new SearchSession(graph, START, westGoal, DFS);

        session.step();
        assertEquals(List.of(A, START), session.agendaPage(0, 10));
        assertEquals(1, session.metrics().frontierSize());
        var secondStep = session.step();
        assertEquals(List.of(A), expandedStates(secondStep));
        assertEquals(new SearchEvent.Discovered(westGoal, START), secondStep.get(secondStep.size() - 1));
        assertEquals(List.of(westGoal, START), session.agendaPage(0, 10));
        assertEquals(List.of(westGoal), finish(session, FOUND));
        assertEquals(List.of(START, westGoal), session.path());
    }

    @ParameterizedTest
    @EnumSource(SearchAlgorithm.class)
    void resetDropsPreviousStateAndReplayStartsFromTheSameOrigin(SearchAlgorithm algorithm) {
        var session = new SearchSession(loop(), START, GOAL, algorithm);
        List<GridPosition> order = finish(session, FOUND);
        List<GridPosition> path = session.path();
        var predecessors = session.predecessors();

        assertEquals(new SearchEvent.Reset(), session.reset());
        assertEquals(READY, session.status());
        assertEquals(new SearchMetrics(0, 1, 1, null, GOAL, OptionalInt.empty()), session.metrics());
        assertTrue(session.path().isEmpty());
        assertEquals(List.of(START), session.agendaPage(0, 10));
        assertEquals(List.of(START), new ArrayList<>(session.predecessors().keySet()));
        assertNull(session.predecessors().get(START));
        assertEquals(4, predecessors.size());
        assertThrows(UnsupportedOperationException.class, () -> predecessors.clear());
        assertThrows(UnsupportedOperationException.class, () -> path.add(START));
        assertThrows(IllegalArgumentException.class, () -> session.agendaPage(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> session.agendaPage(0, -1));
        assertEquals(order, finish(session, FOUND));
        assertEquals(path, session.path());
    }

    @Test
    void rejectsInvalidOriginAndGoalBeforeExecution() {
        GridGraph graph = loop();
        GridPosition blocked = cell(2, 0);
        GridPosition outside = cell(3, 1);

        assertThrows(IllegalArgumentException.class, () -> new SearchSession(graph, outside, GOAL, BFS));
        assertThrows(IllegalArgumentException.class, () -> new SearchSession(graph, blocked, GOAL, BFS));
        assertThrows(IllegalArgumentException.class, () -> new SearchSession(graph, START, outside, DFS));
        assertThrows(IllegalArgumentException.class, () -> new SearchSession(graph, START, blocked, DFS));
    }

    @Test
    void depthFirstCanBacktrackThroughADeepComponent() {
        int length = 8192;
        var builder = GridGraph.builder(new SearchRegion(0, 0, length, 0, 64), length + 1, length + 1);
        for (int x = 0; x <= length; x++) {
            builder.addState(cell(x, 0));
            if (x > 0 && x < length) {
                builder.connect(cell(x - 1, 0), cell(x, 0));
            }
        }
        var session = new SearchSession(builder.build(), cell(0, 0), cell(length, 0), DFS);

        for (int x = 0; x < length; x++) {
            assertEquals(List.of(cell(x, 0)), expandedStates(session.step()));
        }
        assertEquals(EXHAUSTED, session.status());
        assertEquals(0, session.metrics().frontierSize());
        assertEquals(length, session.metrics().discovered());
    }

    private static GridGraph loop() {
        return GridGraph.builder(new SearchRegion(0, 0, 2, 1, 64), 3, 6)
            .addState(START).addState(A).addState(B).addState(GOAL).addState(ISOLATED)
            .connect(START, GOAL).connect(START, A).connect(A, B).connect(B, GOAL)
            .build();
    }

    private static GridPosition cell(int x, int z) {
        return new GridPosition(x, 64, z);
    }

    private static List<GridPosition> finish(SearchSession session, SearchStatus expected) {
        var events = new ArrayList<SearchEvent>();
        assertEquals(expected, session.runToCompletion(events::add));
        return expandedStates(events);
    }

    private static List<GridPosition> expandedStates(List<SearchEvent> events) {
        return events.stream()
            .filter(SearchEvent.Expanded.class::isInstance)
            .map(SearchEvent.Expanded.class::cast)
            .map(SearchEvent.Expanded::state)
            .toList();
    }

    private static void assertValidPath(GridGraph graph, List<GridPosition> path) {
        for (int index = 1; index < path.size(); index++) {
            GridPosition from = path.get(index - 1);
            GridPosition to = path.get(index);
            assertTrue(Direction.ORDER.stream()
                .map(direction -> graph.transition(from, direction))
                .anyMatch(move -> to.equals(move.target()) && move.status() == GridGraph.MoveStatus.OPEN));
        }
    }

    private static void assertTerminalStable(SearchSession session) {
        SearchMetrics metrics = session.metrics();
        SearchStatus status = session.status();
        session.resume();
        session.pause();
        assertTrue(session.advance().isEmpty());
        assertTrue(session.step().isEmpty());
        assertEquals(status, session.runToCompletion(event -> {
            throw new AssertionError("Unexpected event after completion");
        }));
        assertEquals(metrics, session.metrics());
    }
}
