package org.elaramc.client.presentation;

import net.minecraft.util.Identifier;
import org.elaramc.client.config.ElaraSettings;
import org.elaramc.client.world.WorldScanner;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchAlgorithm;
import org.elaramc.search.SearchRegion;

public record Scenario(String world, String dimension, SearchRegion region, GridPosition start, GridPosition goal,
                       Camera camera, SearchAlgorithm algorithm, int expansionsPerSecond, Exit exit) {
    public record Camera(double x, double y, double z, float yaw, float pitch) {}
    public record Exit(GridPosition plate, GridPosition landing) {}

    public void validate(String name) {
        if (!ElaraSettings.validScenarioName(name) || world == null || world.isBlank() || world.length() > 255
            || dimension == null || Identifier.tryParse(dimension) == null || region == null || start == null
            || goal == null || camera == null || algorithm == null) {
            throw new IllegalArgumentException("Identificadores inválidos ou campos faltando no cenário " + name + ".");
        }
        if (region.width() > WorldScanner.MAX_SIDE || region.depth() > WorldScanner.MAX_SIDE
            || !region.contains(start) || !region.contains(goal)) {
            throw new IllegalArgumentException("Limites, início ou meta inválidos no cenário " + name + ".");
        }
        if (!Double.isFinite(camera.x) || !Double.isFinite(camera.y) || !Double.isFinite(camera.z)
            || !Float.isFinite(camera.yaw) || !Float.isFinite(camera.pitch) || Math.abs(camera.pitch) > 90
            || Math.abs(camera.x) >= 30_000_000 || Math.abs(camera.z) >= 30_000_000
            || camera.x < region.minX() - 32.0 || camera.x > region.maxX() + 33.0
            || camera.z < region.minZ() - 32.0 || camera.z > region.maxZ() + 33.0
            || expansionsPerSecond < 1 || expansionsPerSecond > ElaraSettings.MAX_SPEED) {
            throw new IllegalArgumentException("Câmera ou velocidade inválida no cenário " + name + ".");
        }
        if (exit != null) {
            if (exit.plate == null || exit.landing == null || (long) exit.plate.y() - goal.y() != 2) {
                throw new IllegalArgumentException("A saída precisa de uma placa elevada e uma posição de chegada.");
            }
            long dx = Math.abs((long) exit.plate.x() - goal.x());
            long dz = Math.abs((long) exit.plate.z() - goal.z());
            if (dx + dz < 2 || dx + dz > 6 || dx * dz != 0) {
                throw new IllegalArgumentException("A placa de saída precisa ficar no corredor elevado junto à escada.");
            }
        }
    }
}
