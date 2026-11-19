package org.elaramc.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.elaramc.client.config.ElaraSettings;
import org.elaramc.client.config.ElaraSettings.Palette;
import org.elaramc.client.config.PlayMode;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.search.WorldSearch;
import org.elaramc.search.SearchAlgorithm;

public final class SearchHud {
    private static final int WIDTH = 198;
    private static final int HEIGHT = 144;
    private static final Text WORDMARK = Text.literal("elara.").formatted(Formatting.ITALIC);
    private static final String DASH = "–";
    private final WorldSearch search;
    private final SettingsStore settings;
    private String phase = "";
    private SearchAlgorithm algorithm;
    private ElaraSettings lastOptions;
    private int revision = -1;
    private long phaseStarted;
    private String method;
    private String expanded;
    private String discovered;
    private String frontier;
    private String current;
    private String goal;
    private String path;
    private String pace;
    private long elapsed = -1;
    private String time = DASH;

    public SearchHud(WorldSearch search, SettingsStore settings) {
        this.search = search;
        this.settings = settings;
    }

    public void render(DrawContext draw, float tickDelta) {
        var client = MinecraftClient.getInstance();
        var options = settings.current();
        if (!options.hudVisible || client.options.hudHidden || client.world == null
            || client.currentScreen instanceof InspectorScreen || client.currentScreen instanceof ChatScreen) {
            return;
        }
        String actualPhase = search.phase();
        if (!actualPhase.equals(phase)) {
            phase = actualPhase;
            phaseStarted = System.nanoTime();
        }
        if (revision != search.trace().revision() || algorithm != search.algorithm() || lastOptions != options) {
            update(options);
        }
        long currentTime = search.session() == null ? -1 : search.elapsedTenths();
        if (elapsed != currentTime) {
            elapsed = currentTime;
            time = elapsed < 0 ? DASH : search.elapsedLabel();
        }
        var colors = settings.palette();
        int width = draw.getScaledWindowWidth();
        int height = draw.getScaledWindowHeight();
        int marginX = Math.min(options.hudMarginX, width / 8);
        int marginY = Math.min(options.hudMarginY, height / 8);
        double scale = Math.min(options.hudScale, Math.min((width - 2.0 * marginX) / WIDTH,
            (height - 2.0 * marginY) / HEIGHT));
        boolean right = options.hudCorner == ElaraSettings.Corner.TOP_RIGHT
            || options.hudCorner == ElaraSettings.Corner.BOTTOM_RIGHT;
        boolean bottom = options.hudCorner == ElaraSettings.Corner.BOTTOM_RIGHT
            || options.hudCorner == ElaraSettings.Corner.BOTTOM_LEFT;
        double x = right ? width - marginX - WIDTH * scale : marginX;
        double y = bottom ? height - marginY - HEIGHT * scale : marginY;
        var matrix = draw.getMatrices();
        matrix.push();
        matrix.translate(x, y, 0);
        matrix.scale((float) scale, (float) scale, 1);
        var font = client.textRenderer;
        int accent = phaseColor(colors, phase);
        int rule = Palette.alpha(colors.pearl(), 62);
        float transition = options.reducedMotion ? 1
            : Math.min(1, (System.nanoTime() - phaseStarted) / 140_000_000.0f);
        draw.fill(0, 0, WIDTH, HEIGHT, Palette.alpha(colors.obsidian(), 248));
        draw.fill(0, 0, WIDTH, 1, Palette.alpha(accent, 144 + Math.round(74 * transition)));
        matrix.push();
        matrix.translate(10, 9, 0);
        matrix.scale(1.35f, 1.35f, 1);
        draw.drawText(font, WORDMARK, 0, 0, colors.pearl(), false);
        matrix.pop();
        String phaseLabel = search.phaseLabel();
        int phaseX = WIDTH - 10 - font.getWidth(phaseLabel);
        draw.fill(phaseX - 7, 13, phaseX - 3, 17, accent);
        draw.drawText(font, phaseLabel, phaseX, 11, accent, false);
        draw.drawText(font, "INSPETOR DE BUSCA", 10, 25, colors.lavender(), false);
        draw.fill(10, 38, WIDTH - 10, 39, rule);
        matrix.push();
        matrix.translate(10, 44, 0);
        float methodScale = Math.min(1.0f, (WIDTH - 20.0f) / font.getWidth(method));
        matrix.scale(methodScale, methodScale, 1);
        draw.drawText(font, method, 0, 0, colors.pearl(), false);
        matrix.pop();
        draw.drawText(font, "Expandidos", 10, 59, colors.iceBlue(), false);
        draw.drawText(font, "Descobertos", 74, 59, colors.lavender(), false);
        draw.drawText(font, "Fronteira", 144, 59, colors.lavender(), false);
        draw.drawText(font, expanded, 10, 72, colors.pearl(), false);
        draw.drawText(font, discovered, 74, 72, colors.pearl(), false);
        draw.drawText(font, frontier, 144, 72, colors.pearl(), false);
        draw.fill(10, 85, WIDTH - 10, 86, rule);
        row(draw, "Atual", current, 92, colors.iceBlue(), colors.pearl());
        row(draw, "Meta", goal, 104, colors.sage(), colors.pearl());
        row(draw, "Caminho", path, 116, colors.champagne(), colors.pearl());
        row(draw, "Tempo", time, 128, colors.iceBlue(), colors.pearl());
        draw.drawText(font, pace, WIDTH - 10 - font.getWidth(pace), 128, colors.lavender(), false);
        matrix.pop();
    }

    private void update(ElaraSettings options) {
        revision = search.trace().revision();
        algorithm = search.algorithm();
        lastOptions = options;
        var metrics = search.metrics();
        method = algorithm == SearchAlgorithm.BFS ? "BFS · Busca em largura · Fila FIFO" : "DFS · Busca em profundidade · Pilha LIFO";
        expanded = metrics == null ? DASH : Integer.toString(metrics.expanded());
        discovered = metrics == null ? DASH : Integer.toString(metrics.discovered());
        frontier = metrics == null ? DASH : Integer.toString(metrics.frontierSize());
        current = metrics == null || metrics.current() == null ? DASH : metrics.current().coordinates();
        goal = search.goal() == null ? DASH : search.goal().coordinates();
        path = metrics == null || metrics.pathLength().isEmpty() ? DASH : metrics.pathLength().getAsInt() + " passos";
        pace = options.mode == PlayMode.ANIMATED ? options.expansionsPerSecond + " exp/s"
            : options.mode.label();
    }

    private static void row(DrawContext draw, String label, String value, int y, int accent, int color) {
        var font = MinecraftClient.getInstance().textRenderer;
        draw.drawText(font, label, 10, y, accent, false);
        draw.drawText(font, font.trimToWidth(value, WIDTH - 68), 58, y, color, false);
    }

    static int phaseColor(Palette colors, String phase) {
        return switch (phase) {
            case "RUNNING" -> colors.iceBlue();
            case "PAUSED", "NO PATH", "ERROR" -> colors.champagne();
            case "FOUND" -> colors.sage();
            default -> colors.lavender();
        };
    }
}
