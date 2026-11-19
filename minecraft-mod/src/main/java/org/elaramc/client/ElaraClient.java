package org.elaramc.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.elaramc.client.commands.ElaraCommands;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.hud.SearchHud;
import org.elaramc.client.input.ElaraKeys;
import org.elaramc.client.input.PathWalker;
import org.elaramc.client.presentation.ScenarioManager;
import org.elaramc.client.presentation.ScenarioStore;
import org.elaramc.client.presentation.PresentationSync;
import org.elaramc.client.render.WorldOverlay;
import org.elaramc.client.search.WorldSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ElaraClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Elara");

    @Override
    public void onInitializeClient() {
        var configDir = FabricLoader.getInstance().getConfigDir();
        var settings = new SettingsStore(configDir);
        try {
            settings.apply(settings.read());
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Using default settings. {}", exception.getMessage());
        }
        var search = new WorldSearch(settings);
        var scenarioStore = new ScenarioStore(configDir);
        try {
            scenarioStore.apply(scenarioStore.read());
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Using bundled scenarios. {}", exception.getMessage());
        }
        var scenarios = new ScenarioManager(scenarioStore, search, settings);
        var sync = new PresentationSync(settings, search, scenarios);
        var walker = new PathWalker(search, settings, scenarios);
        var commands = new ElaraCommands(search, settings, scenarios, walker);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            commands.register(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(new ElaraKeys(search, settings, scenarios, walker)::tick);
        ClientTickEvents.END_CLIENT_TICK.register(scenarios::tick);
        ClientTickEvents.END_CLIENT_TICK.register(sync::tick);
        ClientTickEvents.END_CLIENT_TICK.register(search::tick);
        ClientTickEvents.END_CLIENT_TICK.register(walker::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> sync.close());
        HudRenderCallback.EVENT.register(new SearchHud(search, settings)::render);
        WorldRenderEvents.LAST.register(new WorldOverlay(search, settings)::render);
        LOGGER.info("Elara initialized");
    }
}
