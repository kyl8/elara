package org.elaramc.client.search;

import net.minecraft.util.math.Box;
import org.elaramc.client.world.WorldSnapshot;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded inspection history and the states drawn in the world. */
public final class SearchTrace {
    public enum Mark { FRONTIER, EXPANDED, PATH }
    private static final int STORED_SKIPS_PER_STEP = 32;
    private final Map<GridPosition, Tile> tiles = new LinkedHashMap<>();
    private final Collection<Tile> view = Collections.unmodifiableCollection(tiles.values());
    private final ArrayDeque<Expansion> history = new ArrayDeque<>();
    private List<GridPosition> path = List.of();
    private int shownPath;
    private boolean reachedGoal;
    private int revision;
    private int generation;

    public static final class Tile {
        public final GridPosition position;
        public final double floor;
        public final Box bounds;
        public Mark mark;

        private Tile(GridPosition position, double floor) {
            this.position = position;
            this.floor = floor;
            this.bounds = new Box(position.x(), floor, position.z(), position.x() + 1.0, floor + 0.08,
                position.z() + 1.0);
            mark = Mark.FRONTIER;
        }
    }

    public record Expansion(SearchEvent.Expanded state, List<SearchEvent.Discovered> discovered,
                            List<SearchEvent.Skipped> skipped, int totalSkipped) {}

    void accept(List<SearchEvent> events, WorldSnapshot snapshot, int historySize, boolean immediatePath) {
        if (events.isEmpty()) {
            return;
        }
        var discovered = new ArrayList<SearchEvent.Discovered>();
        var skipped = new ArrayList<SearchEvent.Skipped>();
        int totalSkipped = 0;
        SearchEvent.Expanded expansion = null;
        for (SearchEvent event : events) {
            if (event instanceof SearchEvent.Discovered state) {
                tiles.put(state.state(), new Tile(state.state(), snapshot.surface(state.state())));
                if (state.predecessor() != null) {
                    discovered.add(state);
                }
            } else if (event instanceof SearchEvent.Expanded state) {
                expansion = state;
                tiles.get(state.state()).mark = Mark.EXPANDED;
            } else if (event instanceof SearchEvent.Skipped state) {
                totalSkipped++;
                if (skipped.size() < STORED_SKIPS_PER_STEP) {
                    skipped.add(state);
                }
            } else if (event instanceof SearchEvent.GoalFound) {
                reachedGoal = true;
            } else if (event instanceof SearchEvent.PathReconstructed result) {
                path = result.path();
                if (immediatePath) {
                    reveal(path.size());
                }
            }
        }
        if (expansion != null) {
            history.addLast(new Expansion(expansion, List.copyOf(discovered), List.copyOf(skipped), totalSkipped));
            trim(historySize);
        }
        revision++;
    }

    void tickPath(boolean immediate) {
        if (shownPath < path.size()) {
            reveal(immediate ? path.size() : Math.max(2, (path.size() + 59) / 60));
        }
    }

    private void reveal(int count) {
        int end = Math.min(path.size(), shownPath + count);
        while (shownPath < end) {
            tiles.get(path.get(shownPath++)).mark = Mark.PATH;
        }
    }

    void clear() {
        tiles.clear();
        history.clear();
        path = List.of();
        shownPath = 0;
        reachedGoal = false;
        revision++;
        generation++;
    }

    void trim(int max) {
        while (history.size() > max) {
            history.removeFirst();
        }
    }

    public Collection<Tile> tiles() {
        return view;
    }

    public boolean reachedGoal() {
        return reachedGoal;
    }

    public int revision() {
        return revision;
    }

    public int generation() {
        return generation;
    }

    public int historySize() {
        return history.size();
    }

    public Expansion historyAt(int fromNewest) {
        var iterator = history.descendingIterator();
        for (int index = 0; index < fromNewest && iterator.hasNext(); index++) {
            iterator.next();
        }
        return iterator.hasNext() ? iterator.next() : null;
    }
}
