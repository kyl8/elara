package org.elaramc.search;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/** A single-threaded search over one immutable graph snapshot. */
public final class SearchSession {
    private final GridGraph graph;
    private final GridPosition start;
    private final GridPosition goal;
    private final SearchAlgorithm algorithm;
    private final Deque<GridPosition> queue = new ArrayDeque<>();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final Map<GridPosition, GridPosition> predecessors = new LinkedHashMap<>();

    private SearchStatus status;
    private GridPosition current;
    private int expanded;
    private List<GridPosition> path;

    public SearchSession(GridGraph graph, GridPosition start, GridPosition goal, SearchAlgorithm algorithm) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.start = requireEndpoint(graph, start, "Origin");
        this.goal = requireEndpoint(graph, goal, "Goal");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        initialize();
    }

    private static GridPosition requireEndpoint(GridGraph graph, GridPosition state, String label) {
        Objects.requireNonNull(state, label);
        if (!graph.contains(state)) {
            throw new IllegalArgumentException(label + " is not a traversable state: " + state.coordinates());
        }
        return state;
    }

    private void initialize() {
        status = SearchStatus.READY;
        current = null;
        expanded = 0;
        path = List.of();
        predecessors.clear();
        queue.clear();
        stack.clear();
        predecessors.put(start, null);
        enqueue(start);
    }

    public SearchStatus status() {
        return status;
    }

    public GridPosition current() {
        return current;
    }

    public void resume() {
        if (status == SearchStatus.READY || status == SearchStatus.PAUSED) {
            status = SearchStatus.RUNNING;
        }
    }

    public void pause() {
        if (status == SearchStatus.RUNNING) {
            status = SearchStatus.PAUSED;
        }
    }

    /** Expands one state manually, leaving a nonterminal search paused. */
    public List<SearchEvent> step() {
        if (status == SearchStatus.FOUND || status == SearchStatus.EXHAUSTED) {
            return List.of();
        }
        status = SearchStatus.PAUSED;
        return expand();
    }

    /** Expands one state only when the session is running. */
    public List<SearchEvent> advance() {
        return status == SearchStatus.RUNNING ? expand() : List.of();
    }

    /** Resumes and emits events in expansion order without retaining the entire history. */
    public SearchStatus runToCompletion(Consumer<? super SearchEvent> observer) {
        Objects.requireNonNull(observer, "observer");
        resume();
        while (status == SearchStatus.RUNNING) {
            advance().forEach(observer);
        }
        return status;
    }

    public SearchEvent.Reset reset() {
        initialize();
        return new SearchEvent.Reset();
    }

    public SearchMetrics metrics() {
        var pathLength = path.isEmpty() ? OptionalInt.empty() : OptionalInt.of(path.size() - 1);
        return new SearchMetrics(expanded, predecessors.size(), predecessors.size() - expanded,
            current, goal, pathLength);
    }

    public List<GridPosition> path() {
        return path;
    }

    /** Snapshot in discovery order, including the origin with a null predecessor. */
    public Map<GridPosition, GridPosition> predecessors() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(predecessors));
    }

    /** Queue head first for BFS, or DFS frames from the top of the stack down. */
    public List<GridPosition> agendaPage(int offset, int limit) {
        Iterator<GridPosition> states = algorithm == SearchAlgorithm.BFS
            ? queue.iterator()
            : stack.stream().map(frame -> frame.state).iterator();
        return page(states, offset, limit);
    }

    public int agendaSize() {
        return algorithm == SearchAlgorithm.BFS ? queue.size() : stack.size();
    }

    public List<GridPosition> discoveredPage(int offset, int limit) {
        return page(predecessors.keySet().iterator(), offset, limit);
    }

    public GridPosition predecessor(GridPosition state) {
        return predecessors.get(state);
    }

    private static List<GridPosition> page(Iterator<GridPosition> states, int offset, int limit) {
        if (offset < 0 || limit < 0) {
            throw new IllegalArgumentException("Page offset and limit must be nonnegative");
        }
        var page = new ArrayList<GridPosition>();
        for (int index = 0; index < offset && states.hasNext(); index++) {
            states.next();
        }
        while (page.size() < limit && states.hasNext()) {
            page.add(states.next());
        }
        return List.copyOf(page);
    }

    private List<SearchEvent> expand() {
        var events = new ArrayList<SearchEvent>();
        if (expanded == 0) {
            events.add(new SearchEvent.Started(algorithm, start, goal));
            events.add(new SearchEvent.Discovered(start, null));
        }
        current = algorithm == SearchAlgorithm.BFS ? queue.removeFirst() : stack.getFirst().state;
        events.add(new SearchEvent.Expanded(current, ++expanded));

        if (current.equals(goal)) {
            status = SearchStatus.FOUND;
            path = reconstructPath();
            events.add(new SearchEvent.GoalFound(goal));
            events.add(new SearchEvent.PathReconstructed(path));
        } else {
            if (algorithm == SearchAlgorithm.BFS) {
                for (Direction direction : Direction.ORDER) {
                    tryDiscover(current, direction, events);
                }
            } else {
                scheduleNextDepthFirst(events);
            }
            if (predecessors.size() == expanded) {
                status = SearchStatus.EXHAUSTED;
                events.add(new SearchEvent.Exhausted());
            }
        }
        return List.copyOf(events);
    }

    private void scheduleNextDepthFirst(List<SearchEvent> events) {
        // Keep each parent's next direction so siblings are considered after the child branch.
        while (!stack.isEmpty()) {
            Frame frame = stack.getFirst();
            if (frame.nextDirection == Direction.ORDER.size()) {
                stack.removeFirst();
            } else {
                Direction direction = Direction.ORDER.get(frame.nextDirection++);
                if (tryDiscover(frame.state, direction, events)) {
                    return;
                }
            }
        }
    }

    private boolean tryDiscover(GridPosition source, Direction direction, List<SearchEvent> events) {
        var transition = graph.transition(source, direction);
        GridPosition target = transition.target();
        if (transition.status() != GridGraph.MoveStatus.OPEN || predecessors.containsKey(target)) {
            events.add(new SearchEvent.Skipped(source, transition));
            return false;
        }
        predecessors.put(target, source);
        enqueue(target);
        events.add(new SearchEvent.Discovered(target, source));
        return true;
    }

    private void enqueue(GridPosition state) {
        if (algorithm == SearchAlgorithm.BFS) {
            queue.addLast(state);
        } else {
            stack.addFirst(new Frame(state));
        }
    }

    private List<GridPosition> reconstructPath() {
        var reversed = new ArrayDeque<GridPosition>();
        for (GridPosition node = goal; node != null; node = predecessors.get(node)) {
            reversed.addFirst(node);
        }
        return List.copyOf(reversed);
    }

    private static final class Frame {
        private final GridPosition state;
        private int nextDirection;

        private Frame(GridPosition state) {
            this.state = state;
        }
    }
}
