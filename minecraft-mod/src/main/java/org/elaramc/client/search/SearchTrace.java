package org.elaramc.client.search;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import org.elaramc.client.world.WorldSnapshot;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded inspection history and the states drawn in the world. */
public final class SearchTrace {
    public enum Mark { FRONTIER, EXPANDED, PATH }
    static final int STORED_SKIPS_PER_STEP = 32;
    private final Map<GridPosition, Tile> tiles = new HashMap<>();
    private final Map<Long, TileSection> sections = new HashMap<>();
    private final Collection<TileSection> view = Collections.unmodifiableCollection(sections.values());
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

    public static final class TileSection {
        private final List<Tile> tiles = new ArrayList<>();
        private final List<Tile> view = Collections.unmodifiableList(tiles);
        public final Box bounds;
        private boolean hasPath;

        private TileSection(int x, int y, int z) {
            bounds = new Box(x * 16.0, y * 16.0, z * 16.0, x * 16.0 + 16, y * 16.0 + 16.08, z * 16.0 + 16);
        }

        public boolean mayBeVisible(double x, double y, double z, int distanceSquared, boolean showExploration) {
            if (!showExploration && !hasPath) {
                return false;
            }
            double dx = outside(x, bounds.minX, bounds.maxX);
            double dy = outside(y, bounds.minY, bounds.maxY);
            double dz = outside(z, bounds.minZ, bounds.maxZ);
            return dx * dx + dy * dy + dz * dz <= distanceSquared;
        }

        private static double outside(double coordinate, double min, double max) {
            return Math.max(0, Math.max(min - coordinate, coordinate - max));
        }

        public List<Tile> tiles() { return view; }
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
                GridPosition position = state.state();
                Tile tile = new Tile(position, snapshot.surface(position));
                tiles.put(position, tile);
                sections.computeIfAbsent(sectionKey(position), key ->
                    new TileSection(position.x() >> 4, position.y() >> 4, position.z() >> 4)).tiles.add(tile);
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
            } else if (event instanceof SearchEvent.SkipsOmitted summary) {
                totalSkipped += summary.count();
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
            GridPosition position = path.get(shownPath++);
            tiles.get(position).mark = Mark.PATH;
            sections.get(sectionKey(position)).hasPath = true;
        }
    }

    private static long sectionKey(GridPosition position) {
        return ChunkSectionPos.asLong(position.x() >> 4, position.y() >> 4, position.z() >> 4);
    }

    void clear() {
        tiles.clear();
        sections.clear();
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

    public Collection<TileSection> sections() {
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
