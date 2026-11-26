package org.elaramc.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.elaramc.client.config.ElaraSettings.Palette;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.search.SearchTrace.Mark;
import org.elaramc.client.search.WorldSearch;
import org.elaramc.search.GridPosition;
import org.joml.Matrix4f;

public final class WorldOverlay {
    private final WorldSearch search;
    private final SettingsStore settings;
    private final BufferBuilder geometry = new BufferBuilder(4096);
    private final VertexConsumerProvider.Immediate labels = VertexConsumerProvider.immediate(new BufferBuilder(256));
    private Vec3d cameraOrigin;

    public WorldOverlay(WorldSearch search, SettingsStore settings) {
        this.search = search;
        this.settings = settings;
    }

    public void render(WorldRenderContext context) {
        var options = settings.current();
        if (!options.overlaysVisible || search.start() == null && search.goal() == null) {
            return;
        }
        Vec3d camera = context.camera().getPos();
        cameraOrigin = camera;
        Frustum frustum = context.frustum();
        int limit = options.overlayDistance * options.overlayDistance;
        var matrices = context.matrixStack();
        var colors = settings.palette();
        matrices.push();
        try {
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            boolean showExploration = search.showExploration();
            geometry.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            for (var section : search.trace().sections()) {
                if (!section.mayBeVisible(camera.x, camera.y, camera.z, limit, showExploration)
                    || frustum != null && !frustum.isVisible(section.bounds)) {
                    continue;
                }
                for (var tile : section.tiles()) {
                    if ((!showExploration && tile.mark != Mark.PATH) || !visible(tile.position, tile.floor, camera, limit)
                        || frustum != null && !frustum.isVisible(tile.bounds)) {
                        continue;
                    }
                    int color = switch (tile.mark) {
                        case FRONTIER -> Palette.alpha(colors.lavender(), 109);
                        case EXPANDED -> Palette.alpha(colors.iceBlue(), 96);
                        case PATH -> Palette.alpha(colors.champagne(), 166);
                    };
                    double inset = tile.mark == Mark.PATH ? 0.09 : 0.15;
                    square(matrix, tile.position, tile.floor + 0.016, inset, color);
                }
            }
            var session = search.session();
            if (showExploration && session != null) {
                marker(matrix, session.current(), camera, limit, colors.iceBlue(), false, 0.12, 0.024);
            }
            marker(matrix, search.start(), camera, limit, colors.pearl(), false, 0.10, 0.030);
            marker(matrix, search.goal(), camera, limit, colors.sage(), search.trace().reachedGoal(), 0.06, 0.036);
            var built = geometry.endNullable();
            if (built != null) {
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                try {
                    RenderSystem.setShader(GameRenderer::getPositionColorProgram);
                    BufferRenderer.drawWithGlobalProgram(built);
                } finally {
                    RenderSystem.enableCull();
                    RenderSystem.depthMask(true);
                    RenderSystem.disableBlend();
                }
            }
            if (search.start() != null && search.start().equals(search.goal())) {
                label(context, search.start(), "INÍCIO E CHEGADA", colors.sage(), camera, limit);
            } else {
                label(context, search.start(), "INÍCIO", colors.pearl(), camera, limit);
                label(context, search.goal(), "CHEGADA", colors.sage(), camera, limit);
            }
            labels.draw();
        } finally {
            matrices.pop();
        }
    }

    private void marker(Matrix4f matrix, GridPosition state, Vec3d camera, int limit, int color,
                        boolean filled, double inset, double offset) {
        if (state == null) {
            return;
        }
        double floor = search.surface(state);
        if (!visible(state, floor, camera, limit)) {
            return;
        }
        double y = floor + offset;
        if (filled) {
            square(matrix, state, y - 0.002, inset, Palette.alpha(color, 154));
        }
        double x0 = state.x() + inset;
        double z0 = state.z() + inset;
        double x1 = state.x() + 1.0 - inset;
        double z1 = state.z() + 1.0 - inset;
        double weight = 0.038;
        int outline = Palette.alpha(color, 215);
        quad(matrix, x0, x1, y, z0, z0 + weight, outline);
        quad(matrix, x0, x1, y, z1 - weight, z1, outline);
        quad(matrix, x0, x0 + weight, y, z0 + weight, z1 - weight, outline);
        quad(matrix, x1 - weight, x1, y, z0 + weight, z1 - weight, outline);
    }

    private void label(WorldRenderContext context, GridPosition position, String text, int color, Vec3d camera, int limit) {
        if (position == null) {
            return;
        }
        double y = search.surface(position) + 0.38;
        double distance = camera.squaredDistanceTo(position.x() + 0.5, y, position.z() + 0.5);
        if (distance < 2.2 || distance > Math.min(limit, 18 * 18)) {
            return;
        }
        var matrices = context.matrixStack();
        matrices.push();
        matrices.translate(position.x() + 0.5 - camera.x, y - camera.y, position.z() + 0.5 - camera.z);
        matrices.multiply(context.camera().getRotation());
        matrices.scale(-0.020f, -0.020f, 0.020f);
        var renderer = MinecraftClient.getInstance().textRenderer;
        renderer.draw(text, -renderer.getWidth(text) / 2.0f, -5, color, false, matrices.peek().getPositionMatrix(),
            labels, TextRenderer.TextLayerType.NORMAL, Palette.alpha(settings.palette().obsidian(), 218),
            LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
    }

    private static boolean visible(GridPosition state, double floor, Vec3d camera, int limit) {
        return camera.squaredDistanceTo(state.x() + 0.5, floor, state.z() + 0.5) <= limit;
    }

    private void square(Matrix4f matrix, GridPosition state, double y, double inset, int color) {
        quad(matrix, state.x() + inset, state.x() + 1.0 - inset, y,
            state.z() + inset, state.z() + 1.0 - inset, color);
    }

    private void quad(Matrix4f matrix, double x0, double x1, double y, double z0, double z1, int color) {
        vertex(matrix, x0, y, z0, color);
        vertex(matrix, x0, y, z1, color);
        vertex(matrix, x1, y, z1, color);
        vertex(matrix, x1, y, z0, color);
    }

    private void vertex(Matrix4f matrix, double x, double y, double z, int color) {
        geometry.vertex(matrix, (float) (x - cameraOrigin.x), (float) (y - cameraOrigin.y), (float) (z - cameraOrigin.z))
            .color(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24).next();
    }
}
