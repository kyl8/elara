package org.elaramc.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import org.elaramc.client.config.ElaraSettings;
import org.elaramc.client.config.ElaraSettings.Palette;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.input.PathWalker;
import org.elaramc.client.search.WorldSearch;

public final class SearchHud {
    private static final int WIDTH = 144;
    private static final int HEIGHT = 49;

    private final WorldSearch search;
    private final SettingsStore settings;
    private final PathWalker walker;

    public SearchHud(WorldSearch search, SettingsStore settings, PathWalker walker) {
        this.search = search;
        this.settings = settings;
        this.walker = walker;
    }

    public void render(DrawContext draw, float tickDelta) {
        var client = MinecraftClient.getInstance();
        var options = settings.current();
        if (!options.hudVisible || client.options.hudHidden || client.world == null
            || client.currentScreen instanceof InspectorScreen || client.currentScreen instanceof ChatScreen
            || (search.start() == null && search.goal() == null)) {
            return;
        }

        var colors = settings.palette();
        int width = draw.getScaledWindowWidth();
        int height = draw.getScaledWindowHeight();
        boolean travel = walker.hasTravelTime();
        int panelHeight = travel ? HEIGHT + 13 : HEIGHT;
        int marginX = Math.min(options.hudMarginX, width / 8);
        int marginY = Math.min(options.hudMarginY, height / 8);
        double scale = Math.min(options.hudScale, Math.min((width - 2.0 * marginX) / WIDTH,
            (height - 2.0 * marginY) / panelHeight));
        boolean right = options.hudCorner == ElaraSettings.Corner.TOP_RIGHT
            || options.hudCorner == ElaraSettings.Corner.BOTTOM_RIGHT;
        boolean bottom = options.hudCorner == ElaraSettings.Corner.BOTTOM_LEFT
            || options.hudCorner == ElaraSettings.Corner.BOTTOM_RIGHT;
        var matrix = draw.getMatrices();
        matrix.push();
        matrix.translate(right ? width - marginX - WIDTH * scale : marginX,
            bottom ? height - marginY - panelHeight * scale : marginY, 0);
        matrix.scale((float) scale, (float) scale, 1);

        var font = client.textRenderer;
        int accent = phaseColor(colors, search.phase());
        if (walker.walking()) {
            accent = colors.iceBlue();
        }
        draw.fill(0, 0, WIDTH, panelHeight, Palette.alpha(colors.obsidian(), 247));
        draw.fill(0, 0, 2, panelHeight, accent);
        draw.drawText(font, "Elara", 9, 8, colors.pearl(), false);
        String algorithm = search.algorithm().name();
        draw.drawText(font, algorithm, WIDTH - 9 - font.getWidth(algorithm), 8, colors.lavender(), false);

        String status = walker.walking() ? "Caminhando" : walker.arrived() ? "Destino alcançado" : routeState(search);
        draw.drawText(font, status, 9, 21, accent, false);
        var metrics = search.metrics();
        if (metrics == null) {
            draw.drawText(font, "F6 para abrir a rota", 9, 34, colors.pearl(), false);
        } else {
            String count = metrics.pathLength().isPresent()
                ? metrics.pathLength().getAsInt() + " passos" : metrics.expanded() + " expandidos";
            draw.drawText(font, count, 9, 34, colors.pearl(), false);
            String time = search.elapsedLabel();
            draw.drawText(font, time, WIDTH - 9 - font.getWidth(time), 34, colors.pearl(), false);
        }
        if (travel) {
            String time = walker.travelLabel();
            draw.drawText(font, "Caminhada", 9, 47, accent, false);
            draw.drawText(font, time, WIDTH - 9 - font.getWidth(time), 47, colors.pearl(), false);
        }
        matrix.pop();
    }

    static String routeState(WorldSearch search) {
        return switch (search.phase()) {
            case "SCANNING" -> "Analisando região";
            case "RUNNING" -> "Buscando rota";
            case "PAUSED" -> "Busca pausada";
            case "FOUND" -> "Rota encontrada";
            case "NO PATH" -> "Sem rota na região";
            case "ERROR" -> "Confira a seleção";
            default -> search.start() == null ? "Marque o início"
                : search.goal() == null ? "Marque a chegada" : "Pronto para buscar";
        };
    }

    static int phaseColor(Palette colors, String phase) {
        return switch (phase) {
            case "SCANNING", "RUNNING" -> colors.iceBlue();
            case "PAUSED", "NO PATH", "ERROR" -> colors.champagne();
            case "FOUND" -> colors.sage();
            default -> colors.lavender();
        };
    }
}
