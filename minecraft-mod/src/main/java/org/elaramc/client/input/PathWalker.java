package org.elaramc.client.input;

import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import org.elaramc.client.ElaraChat;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.elaramc.client.config.PlayMode;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.presentation.Scenario;
import org.elaramc.client.presentation.ScenarioManager;
import org.elaramc.client.search.WorldSearch;
import org.elaramc.search.GridGraph;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchAlgorithm;
import org.elaramc.search.SearchSession;
import org.elaramc.search.SearchStatus;

import java.util.List;

/** Drives normal local player input along a completed path. All movement uses Minecraft physics. */
public final class PathWalker {
    private final WorldSearch search;
    private final SettingsStore settings;
    private final ScenarioManager scenarios;
    private ClientPlayerEntity player;
    private ClientWorld world;
    private SearchSession session;
    private GuidedInput input;
    private List<GridPosition> path;
    private Scenario.Exit exit;
    private int waypoint;
    private int ticksAtWaypoint;
    private boolean exiting;
    private boolean exitRequested;
    private GridGraph previousGraph;
    private SearchSession exitSearch;

    public PathWalker(WorldSearch search, SettingsStore settings, ScenarioManager scenarios) {
        this.search = search;
        this.settings = settings;
        this.scenarios = scenarios;
    }

    public String findExit(MinecraftClient client, SearchAlgorithm algorithm) {
        requireLocalPlayer(client);
        String loading = scenarios.load(client, "level0-exit", algorithm);
        restoreInput();
        previousGraph = search.graph();
        exitRequested = true;
        exitSearch = null;
        return loading + " | O jogador caminhará até a saída após a busca.";
    }

    public String toggle(MinecraftClient client) {
        if (input != null || exitRequested || exitSearch != null) {
            return stop();
        }
        return start(client);
    }

    public String start(MinecraftClient client) {
        requireLocalPlayer(client);
        if (input != null) {
            throw new IllegalStateException("O jogador já está seguindo um caminho.");
        }
        SearchSession result = search.session();
        if (result == null || result.status() != SearchStatus.FOUND) {
            throw new IllegalStateException("Encontre um caminho e depois use /elara walk.");
        }
        ClientPlayerEntity candidate = client.player;
        GridPosition origin = result.path().get(0);
        if (horizontalDistance(candidate, origin) > 0.75 || Math.abs(candidate.getY() - origin.y()) > 0.45) {
            throw new IllegalStateException("Fique no início da rota " + origin.coordinates() + ".");
        }
        Scenario.Exit selectedExit = scenarios.activeExit();
        if (selectedExit != null) {
            var mcGoal = block(result.path().get(result.path().size() - 1));
            if (!(client.world.getBlockState(mcGoal).getBlock() instanceof LadderBlock)
                || !(client.world.getBlockState(block(selectedExit.plate())).getBlock() instanceof AbstractPressurePlateBlock)) {
                throw new IllegalStateException("A escada ou a placa da saída não foi encontrada no mapa.");
            }
        }
        player = candidate;
        world = client.world;
        session = result;
        path = result.path();
        exit = selectedExit;
        input = new GuidedInput(player.input);
        player.input = input;
        player.setSprinting(false);
        waypoint = 0;
        ticksAtWaypoint = 0;
        exiting = false;
        return "Caminhando " + (path.size() - 1) + " passos | Use uma tecla de movimento ou /elara walk stop para parar.";
    }

    public String stop() {
        restoreInput();
        exitRequested = false;
        exitSearch = null;
        return "Caminhada encerrada.";
    }

    private void restoreInput() {
        if (player != null && player.input == input) {
            player.input = input.delegate;
        }
        player = null;
        input = null;
        session = null;
        path = null;
        world = null;
        exit = null;
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.getServer() == null) {
            stop();
            return;
        }
        if (exitRequested && !scenarios.loading()) {
            exitRequested = false;
            if (scenarios.activeExit() != null && search.graph() != previousGraph) {
                try {
                    if (settings.current().mode == PlayMode.STEP) {
                        search.editSettings(options -> options.mode = PlayMode.ANIMATED);
                    }
                    chat(client, search.run());
                    exitSearch = search.session();
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    client.player.sendMessage(ElaraChat.warning(settings.palette(), exception.getMessage()), false);
                }
            }
        }
        if (exitSearch != null && (exitSearch != search.session() || exitSearch.status() == SearchStatus.READY
            || exitSearch.status() == SearchStatus.EXHAUSTED)) {
            exitSearch = null;
        }
        if (exitSearch != null && exitSearch.status() == SearchStatus.FOUND) {
            exitSearch = null;
            try {
                chat(client, start(client));
            } catch (IllegalStateException exception) {
                client.player.sendMessage(ElaraChat.warning(settings.palette(), exception.getMessage()), false);
            }
        }
        if (input == null) {
            return;
        }
        if (player != client.player || world != client.world || session != search.session()
            || session.status() != SearchStatus.FOUND || player.input != input || input.interrupted) {
            finish(client, "Caminhada interrompida.");
            return;
        }
        input.forward = 0;
        input.climb = false;
        if (client.currentScreen != null || client.isPaused()) {
            return;
        }
        if (exiting) {
            advanceExit(client);
            return;
        }
        GridPosition target = path.get(waypoint);
        if (horizontalDistance(player, target) < 0.14) {
            ticksAtWaypoint = 0;
            if (++waypoint == path.size()) {
                if (exit == null) {
                    finish(client, "Destino alcançado em " + target.coordinates() + ".");
                } else {
                    exiting = true;
                    chat(client, "Escada da saída alcançada | Subindo até a placa de pressão do mapa.");
                }
                return;
            }
            target = path.get(waypoint);
        }
        if (++ticksAtWaypoint > 90 || horizontalDistance(player, target) > 2.0
            || Math.abs(player.getY() - target.y()) > 0.65) {
            finish(client, "Rota alterada ou bloqueada perto de " + target.coordinates() + ".");
            return;
        }
        steer(target);
    }

    private void advanceExit(MinecraftClient client) {
        if (horizontalDistance(player, exit.landing()) <= 2.0 && Math.abs(player.getY() - exit.landing().y()) < 2.0) {
            finish(client, "Nível 1 alcançado em " + exit.landing().coordinates() + ".");
            return;
        }
        if (++ticksAtWaypoint > 240 || horizontalDistance(player, exit.plate()) > 7.0) {
            finish(client, "A saída não acionou. Use a escada e a placa de pressão manualmente.");
            return;
        }
        if (horizontalDistance(player, exit.plate()) > 0.13) {
            steer(exit.plate());
        }
        input.climb = player.isClimbing() && player.getY() < exit.plate().y();
    }

    private void steer(GridPosition target) {
        double dx = target.x() + 0.5 - player.getX();
        double dz = target.z() + 0.5 - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYaw(player.getYaw() + MathHelper.wrapDegrees(yaw - player.getYaw()));
        input.forward = (float) Math.min(0.75, Math.hypot(dx, dz) * 2.8);
    }

    private static void requireLocalPlayer(MinecraftClient client) {
        if (client.getServer() == null || client.player == null || client.player.isSpectator()
            || client.player.getAbilities().flying || client.player.hasVehicle()) {
            throw new IllegalStateException("Abra um mundo solo e fique no chão para seguir a rota.");
        }
    }

    private void finish(MinecraftClient client, String message) {
        restoreInput();
        chat(client, message);
    }

    private static double horizontalDistance(ClientPlayerEntity player, GridPosition position) {
        return Math.hypot(position.x() + 0.5 - player.getX(), position.z() + 0.5 - player.getZ());
    }

    private static BlockPos block(GridPosition position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private void chat(MinecraftClient client, String message) {
        client.player.sendMessage(ElaraChat.info(settings.palette(), message), false);
    }

    private static final class GuidedInput extends Input {
        private final Input delegate;
        private float forward;
        private boolean climb;
        private boolean interrupted;

        private GuidedInput(Input delegate) {
            this.delegate = delegate;
        }

        @Override
        public void tick(boolean slowDown, float factor) {
            delegate.tick(slowDown, factor);
            interrupted |= delegate.movementForward != 0 || delegate.movementSideways != 0 || delegate.jumping || delegate.sneaking;
            if (interrupted) {
                movementForward = delegate.movementForward;
                movementSideways = delegate.movementSideways;
                pressingForward = delegate.pressingForward;
                pressingBack = delegate.pressingBack;
                pressingLeft = delegate.pressingLeft;
                pressingRight = delegate.pressingRight;
                jumping = delegate.jumping;
                sneaking = delegate.sneaking;
            } else {
                movementForward = slowDown ? forward * factor : forward;
                movementSideways = 0;
                pressingForward = forward > 0;
                jumping = climb;
            }
        }
    }
}
