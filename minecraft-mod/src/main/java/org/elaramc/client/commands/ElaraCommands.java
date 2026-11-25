package org.elaramc.client.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.elaramc.client.ElaraChat;
import net.minecraft.util.math.BlockPos;
import org.elaramc.client.config.ElaraSettings;
import org.elaramc.client.config.PlayMode;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.hud.InspectorScreen;
import org.elaramc.client.input.PathWalker;
import org.elaramc.client.presentation.ScenarioManager;
import org.elaramc.client.search.WorldSearch;
import org.elaramc.client.world.WorldScanner;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchAlgorithm;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class ElaraCommands {
    private static final int WORLD_COORDINATE_LIMIT = 30_000_000;

    private final WorldSearch search;
    private final SettingsStore settings;
    private final ScenarioManager scenarios;
    private final PathWalker walker;

    public ElaraCommands(WorldSearch search, SettingsStore settings, ScenarioManager scenarios, PathWalker walker) {
        this.search = search;
        this.settings = settings;
        this.scenarios = scenarios;
        this.walker = walker;
    }

    public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var region = literal("region")
            .then(selection("first", "Canto A", search::selectFirstCorner))
            .then(selection("second", "Canto B", search::selectSecondCorner))
            .then(action("auto", () -> {
                search.useAutomaticRegion();
                return "Região automática selecionada. Use /elara scan.";
            }));
        var limits = literal("limits").then(argument("side", integer(1, WorldScanner.MAX_SIDE))
            .then(argument("states", integer(1, WorldScanner.MAX_STATES)).executes(context -> reply(context, () -> {
                int side = getInteger(context, "side");
                int states = getInteger(context, "states");
                search.setLimits(side, states);
                return "Limites | " + side + " por lado em X/Z | " + states + " estados em X/Y/Z. Use /elara scan.";
            }))));
        var algorithm = literal("algorithm")
            .then(action("bfs", () -> search.chooseAlgorithm(SearchAlgorithm.BFS)))
            .then(action("dfs", () -> search.chooseAlgorithm(SearchAlgorithm.DFS)));

        dispatcher.register(literal("elara")
            .executes(context -> ElaraHelp.overview(context.getSource(), settings))
            .then(ElaraHelp.command(settings))
            .then(coordinates(action("start", () -> walker.startOrSearch(MinecraftClient.getInstance())),
                "Início", search::selectStart)
                .then(action("walk", () -> walker.findRoute(MinecraftClient.getInstance()))))
            .then(endpoint("origin", "Início", search::selectStart))
            .then(endpoint("finish", "Chegada", search::selectGoal))
            .then(selection("goal", "Meta", search::selectGoal))
            .then(action("swap", search::swapPoints))
            .then(action("clear", search::clearPoints))
            .then(region)
            .then(limits)
            .then(algorithm)
            .then(literal("mode")
                .then(action("step", () -> mode(PlayMode.STEP)))
                .then(action("animated", () -> mode(PlayMode.ANIMATED)))
                .then(action("instant", () -> mode(PlayMode.INSTANT))))
            .then(literal("speed").then(argument("rate", integer(1, ElaraSettings.MAX_SPEED))
                .executes(context -> reply(context, () -> {
                    int rate = getInteger(context, "rate");
                    edit(options -> options.expansionsPerSecond = rate);
                    return rate + " expansões por segundo no modo Animado.";
                }))))
            .then(action("reload", scenarios::reload))
            .then(scenarioCommands())
            .then(action("replay", () -> scenarios.replay(MinecraftClient.getInstance()))
                .then(literal("loop").then(argument("seconds", integer(0, 120))
                    .executes(context -> reply(context, () -> {
                        int delay = getInteger(context, "seconds");
                        edit(options -> options.replayDelaySeconds = delay);
                        return delay == 0 ? "Repetição automática desativada." : "Repetição automática após " + delay + " s do fim da busca.";
                    })))))
            .then(action("hud", () -> {
                edit(options -> options.hudVisible = !options.hudVisible);
                return settings.current().hudVisible ? "HUD visível." : "HUD oculto.";
            }))
            .then(action("walk", () -> walker.toggle(MinecraftClient.getInstance()))
                .then(action("start", () -> walker.start(MinecraftClient.getInstance())))
                .then(action("stop", walker::stop)))
            .then(action("exit", () -> walker.findExit(MinecraftClient.getInstance(), search.algorithm()))
                .then(action("bfs", () -> walker.findExit(MinecraftClient.getInstance(), SearchAlgorithm.BFS)))
                .then(action("dfs", () -> walker.findExit(MinecraftClient.getInstance(), SearchAlgorithm.DFS))))
            .then(action("overlay", () -> {
                edit(options -> options.overlaysVisible = !options.overlaysVisible);
                return settings.current().overlaysVisible ? "Visualização 3D ativa." : "Visualização 3D oculta.";
            }))
            .then(action("inspector", () -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> client.setScreen(new InspectorScreen(search, settings, scenarios, walker, false)));
                return "Inspetor aberto.";
            }))
            .then(action("scan", search::scan))
            .then(action("run", search::run)
                .then(action("bfs", () -> search.run(SearchAlgorithm.BFS)))
                .then(action("dfs", () -> search.run(SearchAlgorithm.DFS))))
            .then(action("step", search::step))
            .then(action("pause", search::pause))
            .then(action("resume", search::resume))
            .then(action("reset", search::reset))
            .then(action("status", () -> search.status()
                + (walker.hasTravelTime() ? " | Caminhada " + walker.travelLabel() : "")))
            .then(action("path", () -> search.path(1)).then(argument("page", integer(1))
                .executes(context -> reply(context, () -> search.path(getInteger(context, "page")))))));
    }

    private String mode(PlayMode mode) {
        edit(options -> options.mode = mode);
        return "Modo " + mode.label() + " selecionado.";
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> scenarioCommands() {
        var named = argument("name", word()).suggests((context, builder) -> {
            for (String name : scenarios.names()) {
                if (name.startsWith(builder.getRemaining())) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        });
        named.executes(context -> reply(context, () -> loadScenario(context, null)));
        named.then(literal("bfs").executes(context -> reply(context, () -> loadScenario(context, SearchAlgorithm.BFS))));
        named.then(literal("dfs").executes(context -> reply(context, () -> loadScenario(context, SearchAlgorithm.DFS))));
        return literal("scenario")
            .then(action("list", () -> String.join(", ", scenarios.names())))
            .then(literal("save").then(argument("name", word()).executes(context -> reply(context,
                () -> scenarios.save(MinecraftClient.getInstance(), getString(context, "name"))))))
            .then(literal("load").then(named));
    }

    private String loadScenario(CommandContext<FabricClientCommandSource> context, SearchAlgorithm override) {
        return scenarios.load(MinecraftClient.getInstance(), getString(context, "name"),
            override == null ? search.algorithm() : override);
    }

    private void edit(Consumer<ElaraSettings> change) {
        search.editSettings(change);
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> action(String name, Supplier<String> action) {
        return literal(name).executes(context -> reply(context, action));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> selection(
        String name, String label, Consumer<GridPosition> select) {
        return coordinates(literal(name)
            .executes(context -> reply(context, () -> mark(label, select, currentPosition(context.getSource())))),
            label, select);
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> endpoint(
        String name, String label, Consumer<GridPosition> select) {
        return selection(name, label, select).then(literal("look").executes(context ->
            reply(context, () -> mark(label, select, aimedPosition(context.getSource())))));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> coordinates(
        LiteralArgumentBuilder<FabricClientCommandSource> command, String label, Consumer<GridPosition> select) {
        return command.then(argument("x", integer(-WORLD_COORDINATE_LIMIT, WORLD_COORDINATE_LIMIT))
                .then(argument("y", integer(-WORLD_COORDINATE_LIMIT, WORLD_COORDINATE_LIMIT))
                    .then(argument("z", integer(-WORLD_COORDINATE_LIMIT, WORLD_COORDINATE_LIMIT))
                        .executes(context -> reply(context, () -> mark(label, select, new GridPosition(
                            getInteger(context, "x"), getInteger(context, "y"), getInteger(context, "z"))))))));
    }

    private static String mark(String label, Consumer<GridPosition> select, GridPosition position) {
        select.accept(position);
        return label + " em " + position.coordinates() + ".";
    }

    private static GridPosition currentPosition(FabricClientCommandSource source) {
        BlockPos block = source.getPlayer().getBlockPos();
        return new GridPosition(block.getX(), block.getY(), block.getZ());
    }

    private static GridPosition aimedPosition(FabricClientCommandSource source) {
        var target = source.getPlayer().raycast(48, 1, false);
        if (!(target instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
            || hit.getSide() != Direction.UP) {
            throw new IllegalArgumentException("Aponte para a face de cima do piso.");
        }
        BlockPos block = hit.getBlockPos();
        return new GridPosition(block.getX(), MathHelper.floor(hit.getPos().getY() + 1e-6), block.getZ());
    }

    private int reply(CommandContext<FabricClientCommandSource> context, Supplier<String> action) {
        FabricClientCommandSource source = context.getSource();
        search.bindWorld(source.getWorld());
        try {
            String message = action.get();
            if (!message.isEmpty()) {
                source.sendFeedback(ElaraChat.info(settings.palette(), message));
            }
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException | IllegalStateException error) {
            source.sendFeedback(ElaraChat.warning(settings.palette(), error.getMessage()));
            return 0;
        }
    }
}
