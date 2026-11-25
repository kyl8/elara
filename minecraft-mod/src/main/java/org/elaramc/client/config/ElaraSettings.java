package org.elaramc.client.config;

import org.elaramc.client.world.WorldScanner;
import org.elaramc.search.SearchAlgorithm;

public final class ElaraSettings {
    public static final int MAX_SPEED = 240;
    public enum Corner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }

    public SearchAlgorithm algorithm = SearchAlgorithm.BFS;
    public PlayMode mode = PlayMode.ANIMATED;
    public int expansionsPerSecond = 24;
    public int maxSide = WorldScanner.MAX_SIDE;
    public int maxStates = WorldScanner.MAX_STATES;
    public int historySize = 96;
    public boolean hudVisible = true;
    public boolean overlaysVisible = true;
    public boolean reducedMotion = false;
    public Corner hudCorner = Corner.TOP_RIGHT;
    public double hudScale = 1.0;
    public int hudMarginX = 8;
    public int hudMarginY = 8;
    public int overlayDistance = 36;
    public int syncPort = 0;
    public int replayDelaySeconds = 0;
    public String startupScenario = "";
    public String startupWorldFolder = "";
    public Colors colors = new Colors();

    public static final class Colors {
        public String obsidian = "#14171E";
        public String lavender = "#BAA7E4";
        public String iceBlue = "#83A9CD";
        public String champagne = "#D9BD85";
        public String sage = "#85BDA0";
        public String pearl = "#F2EDF4";

        Palette palette() {
            return new Palette(rgb(obsidian), rgb(lavender), rgb(iceBlue), rgb(champagne), rgb(sage), rgb(pearl));
        }
    }

    public record Palette(int obsidian, int lavender, int iceBlue, int champagne, int sage, int pearl) {
        public static int alpha(int rgb, int alpha) {
            return (alpha << 24) | (rgb & 0xffffff);
        }
    }

    public Palette validate() {
        if (algorithm == null || mode == null || hudCorner == null || colors == null || startupScenario == null
            || startupWorldFolder == null) {
            throw new IllegalArgumentException("Defina algoritmo, modo, posição do HUD, cores e cenário inicial.");
        }
        range("expansionsPerSecond", expansionsPerSecond, 1, MAX_SPEED);
        range("maxSide", maxSide, 1, WorldScanner.MAX_SIDE);
        range("maxStates", maxStates, 1, WorldScanner.MAX_STATES);
        range("historySize", historySize, 8, 256);
        range("hudScale", hudScale, 0.75, 1.5);
        range("hudMarginX", hudMarginX, 0, 64);
        range("hudMarginY", hudMarginY, 0, 64);
        range("overlayDistance", overlayDistance, 8, 64);
        range("replayDelaySeconds", replayDelaySeconds, 0, 120);
        if (syncPort != 0) {
            range("syncPort", syncPort, 1024, 65535);
        }
        if (!startupScenario.isEmpty() && !validScenarioName(startupScenario)) {
            throw new IllegalArgumentException("Nome inválido para o cenário inicial.");
        }
        if (!startupWorldFolder.isEmpty() && (!startupWorldFolder.matches("[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}")
            || startupWorldFolder.endsWith(".") || startupWorldFolder.endsWith(" "))) {
            throw new IllegalArgumentException("Informe uma pasta de mundo em startupWorldFolder.");
        }
        return colors.palette();
    }

    public static boolean validScenarioName(String name) {
        return name != null && name.matches("[a-z0-9][a-z0-9_-]{0,39}");
    }

    private static void range(String name, double value, double low, double high) {
        if (!Double.isFinite(value) || value < low || value > high) {
            throw new IllegalArgumentException(name + " precisa estar entre " + low + " e " + high + ".");
        }
    }

    private static int rgb(String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Use #RRGGBB para as cores.");
        }
        return Integer.parseInt(hex.substring(1), 16) | 0xff000000;
    }
}
