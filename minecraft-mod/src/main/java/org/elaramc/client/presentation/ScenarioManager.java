package org.elaramc.client.presentation;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.world.ClientWorld;
import org.elaramc.client.ElaraChat;
import org.elaramc.client.config.PlayMode;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.search.WorldSearch;
import org.elaramc.client.world.WorldScanner.UnloadedRegionException;
import org.elaramc.search.GridGraph;
import org.elaramc.search.SearchAlgorithm;
import org.elaramc.search.SearchStatus;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class ScenarioManager {
    private static final int LOAD_TICKS = 120;
    private final ScenarioStore store;
    private final WorldSearch search;
    private final SettingsStore settings;
    private ClientWorld boundWorld;
    private boolean startupAttempted;
    private boolean worldOpenAttempted;
    private Request pending;
    private Scenario active;
    private String activeName;
    private GridGraph activeGraph;
    private int loadTicks;
    private int terminalTicks;

    private record Request(String name, Scenario scene, SearchAlgorithm override) {}

    public ScenarioManager(ScenarioStore store, WorldSearch search, SettingsStore settings) {
        this.store = store;
        this.search = search;
        this.settings = settings;
    }

    public List<String> names() {
        return store.names();
    }

    public String activeName() {
        return activeGraph != null && activeGraph == search.graph() ? activeName : "";
    }

    public boolean loading() {
        return pending != null;
    }

    public Scenario.Exit activeExit() {
        return active != null && activeGraph == search.graph() ? active.exit() : null;
    }

    public Scenario exitScenario(MinecraftClient client) {
        Scenario exit = store.get("level0-exit");
        return matchesWorld(client, exit) ? exit : null;
    }

    public String load(MinecraftClient client, String name, SearchAlgorithm override) {
        Scenario scene = store.get(name);
        if (!matchesWorld(client, scene)) {
            throw new IllegalArgumentException("Abra o mundo " + scene.world() + " na dimensão " + scene.dimension() + ".");
        }
        if (scene.camera().y() < client.world.getBottomY() || scene.camera().y() >= client.world.getTopY()) {
            throw new IllegalArgumentException("A câmera está fora da altura permitida nesta dimensão.");
        }
        bind(client.world);
        if (search.session() != null) {
            search.pause();
        }
        restoreCamera(client, scene.camera());
        pending = new Request(name, scene, override);
        loadTicks = 0;
        return "Carregando " + name + " | Aguardando os chunks próximos.";
    }

    public String save(MinecraftClient client, String name) {
        if (client.player == null) {
            throw new IllegalStateException("Abra um mundo primeiro.");
        }
        if (search.graph() == null) {
            throw new IllegalStateException("Analise a região antes de salvar um cenário.");
        }
        var player = client.player;
        Scenario.Camera camera = new Scenario.Camera(player.getX(), player.getY(), player.getZ(),
            player.getYaw(), player.getPitch());
        var scene = new Scenario(worldId(client), dimension(client), search.graph().region(), search.start(),
            search.goal(), camera, search.algorithm(), settings.current().expansionsPerSecond, activeExit());
        store.save(name, scene);
        activeGraph = search.graph();
        activeName = name;
        active = scene;
        return "Cenário salvo: " + name + ".";
    }

    public String replay(MinecraftClient client) {
        if (pending != null) {
            throw new IllegalStateException("Aguarde o cenário terminar de carregar.");
        }
        if (active != null && activeGraph == search.graph()) {
            restoreCamera(client, active.camera());
        }
        terminalTicks = 0;
        return search.run();
    }

    public String reload() {
        var previous = settings.current();
        var preferences = settings.read();
        var custom = store.read();
        store.validateStartup(preferences.startupScenario, custom);
        store.apply(custom);
        settings.apply(preferences);
        search.applySettings(previous);
        return "Ajustes e cenários recarregados.";
    }

    public void tick(MinecraftClient client) {
        bind(client.world);
        if (client.player == null || client.world == null) {
            openPreparedWorld(client);
            return;
        }
        var options = settings.current();
        if (!startupAttempted) {
            startupAttempted = true;
            if (!options.startupScenario.isEmpty()) {
                try {
                    if (matchesWorld(client, store.get(options.startupScenario))) {
                        chat(client, load(client, options.startupScenario, options.algorithm));
                    }
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    client.player.sendMessage(ElaraChat.warning(settings.palette(), exception.getMessage()), false);
                }
            }
        }
        if (pending != null) {
            finishLoad(client);
            return;
        }
        var session = search.session();
        if (options.replayDelaySeconds == 0 || options.mode == PlayMode.STEP || session == null
            || session.status() != SearchStatus.FOUND && session.status() != SearchStatus.EXHAUSTED) {
            terminalTicks = 0;
        } else if (!client.isPaused() && ++terminalTicks >= 20 * options.replayDelaySeconds) {
            try {
                chat(client, replay(client));
            } catch (IllegalArgumentException | IllegalStateException exception) {
                terminalTicks = 0;
                client.player.sendMessage(ElaraChat.warning(settings.palette(), exception.getMessage()), false);
            }
        }
    }

    private void openPreparedWorld(MinecraftClient client) {
        String folder = settings.current().startupWorldFolder;
        if (!worldOpenAttempted && !folder.isEmpty() && client.currentScreen instanceof TitleScreen title) {
            worldOpenAttempted = true;
            if (client.getLevelStorage().levelExists(folder)) {
                client.createIntegratedServerLoader().start(title, folder);
            } else {
                LoggerFactory.getLogger("Elara").warn("Prepared save {} is missing.", folder);
            }
        }
    }

    private void finishLoad(MinecraftClient client) {
        Request request = pending;
        Scenario scene = request.scene();
        try {
            search.prepareScenario(scene.region(), scene.start(), scene.goal(),
                request.override() == null ? scene.algorithm() : request.override());
            if (settings.current().expansionsPerSecond != scene.expansionsPerSecond()) {
                search.editSettings(options -> options.expansionsPerSecond = scene.expansionsPerSecond());
            }
            active = scene;
            activeName = request.name();
            activeGraph = search.graph();
            pending = null;
            terminalTicks = 0;
            chat(client, activeName + " pronto | " + search.graph().size() + " estados transitáveis.");
        } catch (UnloadedRegionException exception) {
            if (++loadTicks >= LOAD_TICKS) {
                pending = null;
                client.player.sendMessage(ElaraChat.warning(settings.palette(),
                    exception.getMessage() + " Depois, carregue o cenário de novo."), false);
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            pending = null;
            client.player.sendMessage(ElaraChat.warning(settings.palette(), exception.getMessage()), false);
        }
    }

    private void bind(ClientWorld world) {
        if (boundWorld != world) {
            boundWorld = world;
            startupAttempted = false;
            pending = null;
            active = null;
            activeGraph = null;
            activeName = null;
            terminalTicks = 0;
        }
    }

    private static boolean matchesWorld(MinecraftClient client, Scenario scene) {
        return client.world != null && client.player != null && scene.world().equals(worldId(client))
            && scene.dimension().equals(dimension(client));
    }

    private static String worldId(MinecraftClient client) {
        var server = client.getServer();
        if (server != null) {
            return "local:" + server.getSaveProperties().getLevelName();
        }
        var entry = client.getCurrentServerEntry();
        if (entry == null) {
            throw new IllegalStateException("Não foi possível identificar este mundo ou servidor.");
        }
        return "server:" + entry.address;
    }

    private static String dimension(MinecraftClient client) {
        return client.world.getRegistryKey().getValue().toString();
    }

    private static void restoreCamera(MinecraftClient client, Scenario.Camera camera) {
        var server = client.getServer();
        if (server == null) {
            client.player.setYaw(camera.yaw());
            client.player.setPitch(camera.pitch());
            return;
        }
        var uuid = client.player.getUuid();
        server.execute(() -> {
            var player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                player.teleport(player.getServerWorld(), camera.x(), camera.y(), camera.z(), camera.yaw(), camera.pitch());
            }
        });
    }

    private void chat(MinecraftClient client, String text) {
        client.player.sendMessage(ElaraChat.info(settings.palette(), text), false);
    }
}
