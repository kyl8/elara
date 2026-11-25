package org.elaramc.client.input;

import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.elaramc.client.ElaraChat;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.presentation.Scenario;
import org.elaramc.client.presentation.ScenarioManager;
import org.elaramc.client.search.SearchTimer;
import org.elaramc.client.search.WorldSearch;
import org.elaramc.client.world.WorldScanner;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchAlgorithm;
import org.elaramc.search.SearchRegion;
import org.elaramc.search.SearchSession;
import org.elaramc.search.SearchStatus;

import java.util.List;

/** Drives normal local player input along a path and yields to manual input. */
public final class PathWalker {
    private static final int SETTLE_TICKS = 5;
    private final WorldSearch search;
    private final SettingsStore settings;
    private final ScenarioManager scenarios;
    private final SearchTimer travelTime = new SearchTimer(System::nanoTime);
    private ClientPlayerEntity player;
    private ClientWorld world;
    private GuidedInput input;
    private SearchSession session;
    private SearchSession timedSession;
    private GridPosition destination;
    private SearchAlgorithm algorithm;
    private SearchRegion preferredRegion;
    private Scenario.Exit exit;
    private List<GridPosition> path;
    private int waypoint;
    private int ticksAtWaypoint;
    private boolean planning;
    private boolean exiting;
    private boolean arrived;
    private int settleTicks;

    public PathWalker(WorldSearch search, SettingsStore settings, ScenarioManager scenarios) {
        this.search = search;
        this.settings = settings;
        this.scenarios = scenarios;
    }

    public String startOrSearch(MinecraftClient client) {
        return client.getServer() == null ? search.startQuick() : findRoute(client);
    }

    public String findRoute(MinecraftClient client) {
        return begin(client, search.goal(), search.algorithm(), null, scenarios.activeExit());
    }

    public String findExit(MinecraftClient client, SearchAlgorithm selected) {
        requireLocalPlayer(client);
        var scene = scenarios.exitScenario(client);
        if (scene == null) {
            return begin(client, search.goal(), selected, null, null);
        }
        return begin(client, scene.goal(), selected, scene.region(), scene.exit());
    }

    public String toggle(MinecraftClient client) {
        return input == null ? start(client) : stop();
    }

    public String start(MinecraftClient client) {
        if (search.session() == null || search.session().status() != SearchStatus.FOUND) {
            throw new IllegalStateException("Encontre um caminho com /elara start primeiro.");
        }
        return findRoute(client);
    }

    private String begin(MinecraftClient client, GridPosition goal, SearchAlgorithm selected,
                         SearchRegion preferred, Scenario.Exit transition) {
        requireLocalPlayer(client);
        if (goal == null) {
            throw new IllegalStateException("Marque a chegada com /elara finish.");
        }
        restoreInput();
        destination = goal;
        algorithm = selected;
        preferredRegion = preferred;
        exit = transition;
        player = client.player;
        world = client.world;
        travelTime.reset();
        timedSession = null;
        arrived = false;

        try {
            planFrom(feet());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            restoreInput();
            throw exception;
        }
        input = new GuidedInput(player.input);
        player.input = input;
        player.setSprinting(false);
        return planning ? "Buscando a rota da sua posição atual." : "Caminhando pela rota já encontrada.";
    }

    public String stop() {
        boolean active = input != null;
        restoreInput();
        return active && timedSession != null ? "Caminhada encerrada. Tempo: " + travelTime.label() + "."
            : "Caminhada encerrada.";
    }

    private void restoreInput() {
        travelTime.stop();
        if (input != null && player != null && player.input == input) {
            player.input = input.delegate;
        }
        player = null;
        input = null;
        session = null;
        world = null;
        path = null;
        exit = null;
        preferredRegion = null;
        destination = null;
        algorithm = null;
        settleTicks = 0;
        planning = false;
        exiting = false;
    }

    private void planFrom(GridPosition position) {
        var cached = search.session();
        if (cached != null && cached.status() == SearchStatus.FOUND && destination.equals(search.goal())
            && algorithm == search.algorithm()) {
            int first = cached.path().indexOf(position);
            if (first >= 0) {
                follow(cached, first);
                return;
            }
        }
        session = search.routeFrom(position, destination, algorithm, preferredRegion);
        planning = true;
        path = null;
    }

    private void follow(SearchSession route, int first) {
        session = route;
        timedSession = route;
        path = route.path();
        waypoint = first;
        ticksAtWaypoint = 0;
        planning = false;
        exiting = false;
    }

    public void tick(MinecraftClient client) {
        if (input == null) {
            if (!hasTravelTime()) {
                timedSession = null;
            }
            return;
        }
        if (player != client.player || world != client.world || player.input != input) {
            restoreInput();
            timedSession = null;
            return;
        }
        if (planning && session == null && !search.scanning()) {
            session = search.session();
            if (session == null || !destination.equals(search.goal()) || algorithm != search.algorithm()) {
                restoreInput();
                return;
            }
        }
        if (session != null && (session != search.session() || session.status() == SearchStatus.READY)) {
            finish(client, "Caminhada interrompida.", false);
            return;
        }

        input.forward = 0;
        input.jump = false;
        if (client.currentScreen != null || client.isPaused()) {
            travelTime.stop();
            return;
        }
        if (input.manual) {
            travelTime.stop();
            settleTicks = SETTLE_TICKS;
            return;
        }
        if (settleTicks > 0) {
            if (!player.isOnGround() && !player.isClimbing() || --settleTicks > 0) {
                return;
            }
            try {
                planFrom(feet());
                chat(client, planning ? "Recalculando a rota da sua posição." : "Continuando pela rota em cache.");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                finish(client, exception.getMessage(), false);
            }
            return;
        }

        if (planning) {
            if (session == null) {
                return;
            }
            if (session.status() == SearchStatus.EXHAUSTED) {
                restoreInput();
            } else if (session.status() == SearchStatus.FOUND) {
                int index = session.path().indexOf(feet());
                if (index >= 0) {
                    follow(session, index);
                } else {
                    settleTicks = SETTLE_TICKS;
                }
            }
            return;
        }

        travelTime.start();
        if (exiting) {
            advanceExit(client);
            return;
        }
        advancePath(client);
    }

    private void advancePath(MinecraftClient client) {
        GridPosition target = path.get(waypoint);
        if (horizontalDistance(player, target) < 0.14 && Math.abs(player.getY() - search.surface(target)) < 0.18) {
            ticksAtWaypoint = 0;
            if (waypoint + 1 == path.size()) {
                if (exit == null) {
                    finish(client, "Destino alcançado em " + target.coordinates() + ".", true);
                } else if (!(world.getBlockState(block(target)).getBlock() instanceof LadderBlock)
                    || !(world.getBlockState(block(exit.plate())).getBlock() instanceof AbstractPressurePlateBlock)) {
                    finish(client, "A escada ou a placa da saída não foi encontrada.", false);
                } else {
                    exiting = true;
                    chat(client, "Subindo até a placa de pressão do mapa.");
                }
                return;
            }
            target = path.get(++waypoint);
        }
        double targetY = search.surface(target);
        double previousY = search.surface(path.get(Math.max(0, waypoint - 1)));
        if (horizontalDistance(player, target) > 2.0
            || player.getY() < Math.min(previousY, targetY) - 0.55
            || player.getY() > Math.max(previousY, targetY) + 1.35) {
            travelTime.stop();
            settleTicks = SETTLE_TICKS;
            return;
        }
        if (++ticksAtWaypoint > 90) {
            finish(client, "Rota bloqueada perto de " + target.coordinates() + ".", false);
            return;
        }
        if (horizontalDistance(player, target) >= 0.14) {
            steer(target);
        }
        double rise = targetY - player.getY();
        input.jump = (player.isClimbing() && rise > 0.02) || (player.isOnGround() && rise > WorldScanner.STEP_HEIGHT);
    }

    private void advanceExit(MinecraftClient client) {
        if (horizontalDistance(player, exit.landing()) <= 2.0 && Math.abs(player.getY() - exit.landing().y()) < 2.0) {
            finish(client, "Nível 1 alcançado em " + exit.landing().coordinates() + ".", true);
            return;
        }
        if (++ticksAtWaypoint > 240 || horizontalDistance(player, exit.plate()) > 7.0) {
            finish(client, "A saída não acionou. Use a escada e a placa de pressão manualmente.", false);
            return;
        }
        if (horizontalDistance(player, exit.plate()) > 0.13) {
            steer(exit.plate());
        }
        input.jump = player.isClimbing() && player.getY() < exit.plate().y();
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

    private void finish(MinecraftClient client, String message, boolean completed) {
        restoreInput();
        arrived = completed;
        chat(client, timedSession == null ? message : message + " Tempo de caminhada: " + travelTime.label() + ".");
    }

    public boolean walking() { return input != null && !planning; }

    public boolean hasTravelTime() {
        return timedSession != null && timedSession == search.session() && timedSession.status() == SearchStatus.FOUND;
    }

    public boolean arrived() { return hasTravelTime() && arrived; }

    public String travelLabel() { return travelTime.label(); }

    private GridPosition feet() {
        BlockPos block = player.getBlockPos();
        return new GridPosition(block.getX(), block.getY(), block.getZ());
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
        private boolean jump;
        private boolean manual;

        private GuidedInput(Input delegate) {
            this.delegate = delegate;
        }

        @Override
        public void tick(boolean slowDown, float factor) {
            delegate.tick(slowDown, factor);
            manual = delegate.movementForward != 0 || delegate.movementSideways != 0
                || delegate.jumping || delegate.sneaking;
            movementForward = manual ? delegate.movementForward : (slowDown ? forward * factor : forward);
            movementSideways = manual ? delegate.movementSideways : 0;
            pressingForward = manual ? delegate.pressingForward : forward > 0;
            pressingBack = manual && delegate.pressingBack;
            pressingLeft = manual && delegate.pressingLeft;
            pressingRight = manual && delegate.pressingRight;
            jumping = manual ? delegate.jumping : jump;
            sneaking = manual && delegate.sneaking;
        }
    }
}
