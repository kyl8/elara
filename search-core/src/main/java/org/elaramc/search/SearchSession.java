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
    private final int skipDetailLimit;
    private final Deque<GridPosition> queue = new ArrayDeque<>();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final Map<GridPosition, GridPosition> predecessors = new LinkedHashMap<>();

    private SearchStatus status;
    private GridPosition current;
    private int expanded;
    private List<GridPosition> path;

    public SearchSession(GridGraph graph, GridPosition start, GridPosition goal, SearchAlgorithm algorithm) {
        this(graph, start, goal, algorithm, Integer.MAX_VALUE);
    }

    public SearchSession(GridGraph graph, GridPosition start, GridPosition goal, SearchAlgorithm algorithm, int skipDetailLimit) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.start = requireEndpoint(graph, start, "Origin");
        this.goal = requireEndpoint(graph, goal, "Goal");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        if (skipDetailLimit < 0) {
            throw new IllegalArgumentException("Skip detail limit must be nonnegative");
        }
        this.skipDetailLimit = skipDetailLimit;
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
        var checks = new ExpansionChecks();
        var events = checks.events;
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
                for (Direction direction : graph.directions()) {
                    tryDiscover(current, direction, checks);
                }
            } else {
                scheduleNextDepthFirst(checks);
            }
            if (checks.omitted > 0) {
                events.add(new SearchEvent.SkipsOmitted(checks.omitted));
            }
            if (predecessors.size() == expanded) {
                status = SearchStatus.EXHAUSTED;
                events.add(new SearchEvent.Exhausted());
            }
        }
        return List.copyOf(events);
    }

    private void scheduleNextDepthFirst(ExpansionChecks checks) {
        // Keep each parent's next direction so siblings are considered after the child branch.
        List<Direction> directions = graph.directions();
        while (!stack.isEmpty()) {
            Frame frame = stack.getFirst();
            if (checks.limitReached() && frame.nextDirection < directions.size()) {
                // Direction bits follow traversal order, so a run of closed edges needs only a count.
                int open = graph.openDirections(frame.state) >>> frame.nextDirection;
                int next = open == 0 ? directions.size() : frame.nextDirection + Integer.numberOfTrailingZeros(open);
                checks.omitted += next - frame.nextDirection;
                frame.nextDirection = next;
            }
            if (frame.nextDirection == directions.size()) {
                stack.removeFirst();
            } else {
                if (tryDiscover(frame.state, directions.get(frame.nextDirection++), checks)) {
                    return;
                }
            }
        }
    }

    private boolean tryDiscover(GridPosition source, Direction direction, ExpansionChecks checks) {
        if ((graph.openDirections(source) & direction.bit()) != 0) {
            GridPosition target = direction.from(source);
            if (!predecessors.containsKey(target)) {
                predecessors.put(target, source);
                enqueue(target);
                checks.events.add(new SearchEvent.Discovered(target, source));
                return true;
            }
        }
        checks.skip(source, direction);
        return false;
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

    private final class ExpansionChecks {
        private final List<SearchEvent> events = new ArrayList<>();
        private int retained;
        private int omitted;

        boolean limitReached() {
            return retained == skipDetailLimit;
        }

        void skip(GridPosition source, Direction direction) {
            if (limitReached()) {
                omitted++;
            } else {
                events.add(new SearchEvent.Skipped(source, graph.transition(source, direction)));
                retained++;
            }
        }
    }

    private static final class Frame {
        private final GridPosition state;
        private int nextDirection;

        private Frame(GridPosition state) {
            this.state = state;
        }
    }
}
