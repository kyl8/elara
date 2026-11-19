package org.elaramc.client.world;

import org.elaramc.search.GridGraph;
import org.elaramc.search.GridPosition;

import java.util.Map;

public record WorldSnapshot(GridGraph graph, Map<GridPosition, Double> surfaces) {
    public WorldSnapshot {
        surfaces = Map.copyOf(surfaces);
    }

    public double surface(GridPosition position) {
        return surfaces.getOrDefault(position, (double) position.y());
    }
}
