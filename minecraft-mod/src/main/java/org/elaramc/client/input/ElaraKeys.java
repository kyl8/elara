package org.elaramc.client.input;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.elaramc.client.ElaraChat;
import org.elaramc.client.config.ElaraSettings;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.hud.InspectorScreen;
import org.elaramc.client.presentation.ScenarioManager;
import org.elaramc.client.search.WorldSearch;
import org.elaramc.search.SearchAlgorithm;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ElaraKeys {
    private final List<Binding> bindings = new ArrayList<>();
    private final WorldSearch search;
    private final SettingsStore settings;

    public ElaraKeys(WorldSearch search, SettingsStore settings, ScenarioManager scenarios, PathWalker walker) {
        this.search = search;
        this.settings = settings;
        bind("inspect", GLFW.GLFW_KEY_F6, client -> {
            client.setScreen(new InspectorScreen(search, settings, scenarios, walker, false));
            return "";
        });
        bind("scenarios", GLFW.GLFW_KEY_M, client -> {
            client.setScreen(new InspectorScreen(search, settings, scenarios, walker, true));
            return "";
        });
        bind("pause", GLFW.GLFW_KEY_F7, client -> search.togglePause());
        bind("step", GLFW.GLFW_KEY_F8, client -> search.step());
        bind("run", GLFW.GLFW_KEY_F9, client -> search.run());
        bind("reset", GLFW.GLFW_KEY_F10, client -> search.reset());
        bind("walk", GLFW.GLFW_KEY_G, walker::toggle);
        bind("hud", GLFW.GLFW_KEY_H, client -> {
            search.editSettings(options -> options.hudVisible = !options.hudVisible);
            return settings.current().hudVisible ? "HUD visível." : "HUD oculto.";
        });
        bind("overlay", GLFW.GLFW_KEY_V, client -> {
            search.editSettings(options -> options.overlaysVisible = !options.overlaysVisible);
            return settings.current().overlaysVisible ? "Visualização 3D ativa." : "Visualização 3D oculta.";
        });
        bind("algorithm", GLFW.GLFW_KEY_K, client -> {
            var next = search.algorithm() == SearchAlgorithm.BFS ? SearchAlgorithm.DFS : SearchAlgorithm.BFS;
            search.editSettings(options -> options.algorithm = next);
            search.selectAlgorithm(next);
            return next + " selecionado.";
        });
        bind("slower", GLFW.GLFW_KEY_LEFT_BRACKET, client -> changeSpeed(-4));
        bind("faster", GLFW.GLFW_KEY_RIGHT_BRACKET, client -> changeSpeed(4));
    }

    private String changeSpeed(int delta) {
        search.editSettings(options -> options.expansionsPerSecond =
            Math.max(1, Math.min(ElaraSettings.MAX_SPEED, options.expansionsPerSecond + delta)));
        return settings.current().expansionsPerSecond + " exp/s";
    }

    private void bind(String name, int code, Function<MinecraftClient, String> action) {
        var key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.elara." + name, InputUtil.Type.KEYSYM, code, "key.categories.elara"));
        bindings.add(new Binding(key, action));
    }

    public void tick(MinecraftClient client) {
        search.bindWorld(client.world);
        for (Binding binding : bindings) {
            while (binding.key().wasPressed()) {
                if (client.currentScreen != null || client.player == null) {
                    continue;
                }
                try {
                    String feedback = binding.action().apply(client);
                    if (!feedback.isEmpty()) {
                        client.player.sendMessage(ElaraChat.info(settings.palette(), feedback), true);
                    }
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    client.player.sendMessage(ElaraChat.warning(settings.palette(), exception.getMessage()), false);
                }
            }
        }
    }

    private record Binding(KeyBinding key, Function<MinecraftClient, String> action) {}
}
