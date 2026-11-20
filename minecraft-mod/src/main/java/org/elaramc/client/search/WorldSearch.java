package org.elaramc.client.search;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import org.elaramc.client.ElaraChat;
import org.elaramc.client.config.ElaraSettings;
import org.elaramc.client.config.PlayMode;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.world.WorldScanner;
import org.elaramc.client.world.WorldSnapshot;
import org.elaramc.search.GridGraph;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchAlgorithm;
import org.elaramc.search.SearchEvent;
import org.elaramc.search.SearchMetrics;
import org.elaramc.search.SearchRegion;
import org.elaramc.search.SearchSession;
import org.elaramc.search.SearchStatus;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Owns a client's current selection, graph snapshot, and search session. */
public final class WorldSearch {
    private static final int PATH_PAGE_SIZE = 6;

    private final WorldScanner scanner = new WorldScanner();
    private final SettingsStore settings;
    private final ExpansionClock clock = new ExpansionClock();
    private final SearchTimer timer = new SearchTimer(System::nanoTime);
    private final SearchTrace trace = new SearchTrace();
    private ClientWorld world;
    private GridPosition start;
    private GridPosition goal;
    private GridPosition firstCorner;
    private GridPosition secondCorner;
    private WorldSnapshot snapshot;
    private SearchSession session;
    private SearchAlgorithm algorithm;
    private boolean quickSearch;
    private String scanError;

    public WorldSearch(SettingsStore settings) {
        this.settings = settings;
        algorithm = settings.current().algorithm;
    }

    public void bindWorld(ClientWorld nextWorld) {
        if (world != nextWorld) {
            world = nextWorld;
            start = null;
            goal = null;
            useAutomaticRegion();
        }
    }

    public void selectStart(GridPosition position) {
        start = position;
        clearSnapshot();
    }

    public void selectGoal(GridPosition position) {
        goal = position;
        clearSnapshot();
    }

    public String swapPoints() {
        requireEndpoints();
        GridPosition origin = start;
        start = goal;
        goal = origin;
        clearSnapshot();
        return "Início e chegada invertidos.";
    }

    public String clearPoints() {
        start = null;
        goal = null;
        useAutomaticRegion();
        return "Seleção limpa.";
    }

    public void selectFirstCorner(GridPosition position) {
        firstCorner = position;
        clearSnapshot();
    }

    public void selectSecondCorner(GridPosition position) {
        secondCorner = position;
        clearSnapshot();
    }

    public void useAutomaticRegion() {
        firstCorner = null;
        secondCorner = null;
        clearSnapshot();
    }

    public void setLimits(int side, int states) {
        settings.edit(options -> {
            options.maxSide = side;
            options.maxStates = states;
        });
        clearSnapshot();
    }

    private void clearSnapshot() {
        snapshot = null;
        scanError = null;
        clearSession();
    }

    private void clearSession() {
        session = null;
        quickSearch = false;
        trace.clear();
        clock.reset();
        timer.reset();
    }

    public String scan() {
        try {
            SearchRegion region = selectedRegion();
            snapshot = scanner.scan(world, region, start, goal, settings.current().maxSide, settings.current().maxStates);
            scanError = null;
            clearSession();
            return snapshotMessage();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            scanError = exception.getMessage();
            throw exception;
        }
    }

    public String snapshotMessage() {
        SearchRegion region = graph().region();
        return "Região analisada | " + graph().size() + " estados transitáveis | X " + region.minX()
            + " a " + region.maxX() + " | Z " + region.minZ() + " a " + region.maxZ()
            + " | Y " + region.y();
    }

    private SearchRegion selectedRegion() {
        requireEndpoints();
        if (firstCorner == null && secondCorner == null) {
            return scanner.automaticRegion(world, start, goal, settings.current().maxSide, settings.current().maxStates);
        }
        if (firstCorner == null || secondCorner == null) {
            throw new IllegalStateException("Marque os dois cantos ou use /elara region auto.");
        }
        if (firstCorner.y() != start.y() || secondCorner.y() != start.y()) {
            throw new IllegalArgumentException("Os dois cantos precisam estar no piso selecionado (Y).");
        }
        return new SearchRegion(
            Math.min(firstCorner.x(), secondCorner.x()), Math.min(firstCorner.z(), secondCorner.z()),
            Math.max(firstCorner.x(), secondCorner.x()), Math.max(firstCorner.z(), secondCorner.z()), start.y());
    }

    private void requireEndpoints() {
        if (start == null || goal == null) {
            throw new IllegalStateException("Marque o início com /elara origin e a chegada com /elara finish.");
        }
        if (start.y() != goal.y()) {
            throw new IllegalArgumentException("Marque os dois pontos no mesmo piso (Y).");
        }
    }

    public void prepareScenario(SearchRegion region, GridPosition origin, GridPosition target, SearchAlgorithm selected) {
        WorldSnapshot prepared = scanner.scan(world, region, origin, target,
            settings.current().maxSide, settings.current().maxStates);
        start = origin;
        goal = target;
        firstCorner = new GridPosition(region.minX(), region.y(), region.minZ());
        secondCorner = new GridPosition(region.maxX(), region.y(), region.maxZ());
        snapshot = prepared;
        algorithm = selected;
        scanError = null;
        clearSession();
    }

    public String chooseAlgorithm(SearchAlgorithm selection) {
        editSettings(options -> options.algorithm = selection);
        selectAlgorithm(selection);
        return selection + " selecionado.";
    }

    private void selectAlgorithm(SearchAlgorithm selection) {
        if (algorithm != selection) {
            algorithm = selection;
            clearSession();
        }
    }

    public String run() {
        return run(algorithm);
    }

    public String run(SearchAlgorithm selection) {
        return run(selection, false);
    }

    public String startQuick() {
        return run(algorithm, true);
    }

    private String run(SearchAlgorithm selection, boolean instant) {
        SearchSession next = newSession(selection);
        clearSession();
        session = next;
        algorithm = selection;
        quickSearch = instant;
        if (runMode() == PlayMode.STEP) {
            return "Busca pronta. Use /elara step ou F8.";
        }
        session.resume();
        if (!quickSearch) {
            timer.start();
            return "Buscando com " + algorithm + ".";
        }
        return "";
    }

    public String step() {
        if (session == null) {
            session = newSession(algorithm);
        }
        timer.start();
        List<SearchEvent> events;
        try {
            events = session.step();
        } finally {
            timer.stop();
        }
        if (events.isEmpty()) {
            throw new IllegalStateException("Busca encerrada. Use /elara reset ou /elara run para repetir.");
        }
        record(events);
        return "Expandidos " + session.metrics().expanded() + " com " + algorithm + ".";
    }

    public String pause() {
        requireSession().pause();
        timer.stop();
        return session.status() == SearchStatus.PAUSED ? "Busca pausada." : phaseLabel();
    }

    public String resume() {
        if (runMode() == PlayMode.STEP) {
            throw new IllegalStateException("Use /elara step ou altere o modo com /elara mode animated.");
        }
        requireSession().resume();
        if (session.status() == SearchStatus.RUNNING && !quickSearch) {
            timer.start();
        }
        return session.status() == SearchStatus.RUNNING ? "Busca retomada com " + algorithm + "." : phaseLabel();
    }

    public String reset() {
        if (session == null) {
            session = newSession(algorithm);
        } else {
            session.reset();
        }
        trace.clear();
        quickSearch = false;
        clock.reset();
        timer.reset();
        return "Busca reiniciada.";
    }

    private SearchSession newSession(SearchAlgorithm selection) {
        if (snapshot == null) {
            scan();
        }
        return new SearchSession(graph(), start, goal, selection);
    }

    private SearchSession requireSession() {
        if (session == null) {
            throw new IllegalStateException("Inicie a busca com /elara start.");
        }
        return session;
    }

    public String status() {
        if (session == null) {
            String readiness = snapshot == null ? "Defina e analise uma região" : graph().size() + " estados transitáveis";
            return algorithm + " | " + readiness + " | Início " + coordinates(start) + " | Meta " + coordinates(goal);
        }
        var metrics = session.metrics();
        return resultSummary() + " | Descobertos " + metrics.discovered()
            + " | Fronteira " + metrics.frontierSize() + " | Atual " + coordinates(metrics.current())
            + " | Meta " + goal.coordinates();
    }

    private String resultSummary() {
        var metrics = session.metrics();
        String cost = metrics.pathLength().isPresent() ? " | Caminho " + metrics.pathLength().getAsInt() + " passos" : "";
        return algorithm + " " + phaseLabel() + " | Tempo " + elapsedLabel() + cost + " | Expandidos " + metrics.expanded();
    }

    public String path(int page) {
        var path = requireSession().path();
        if (path.isEmpty()) {
            throw new IllegalStateException("O caminho fica disponível quando a busca encontra a meta.");
        }
        int pages = (path.size() + PATH_PAGE_SIZE - 1) / PATH_PAGE_SIZE;
        if (page < 1 || page > pages) {
            throw new IllegalArgumentException("Escolha uma página de 1 a " + pages + ".");
        }
        int from = (page - 1) * PATH_PAGE_SIZE;
        String points = path.subList(from, Math.min(path.size(), from + PATH_PAGE_SIZE)).stream()
            .map(GridPosition::coordinates).collect(Collectors.joining(" → "));
        return "Caminho " + page + "/" + pages + " | " + points;
    }

    public void tick(MinecraftClient client) {
        bindWorld(client.world);
        if (client.player == null || client.isPaused()) {
            timer.stop();
            return;
        }
        trace.tickPath(settings.current().reducedMotion);
        if (session == null || session.status() != SearchStatus.RUNNING) {
            return;
        }
        timer.start();
        var options = settings.current();
        int budget = clock.budget(runMode(), options.expansionsPerSecond);
        for (int index = 0; index < budget && session.status() == SearchStatus.RUNNING; index++) {
            record(session.advance());
        }
        if (quickSearch || session.status() != SearchStatus.RUNNING) {
            timer.stop();
        }
        if (session.status() != SearchStatus.RUNNING) {
            var metrics = session.metrics();
            String outcome = metrics.pathLength().isPresent()
                ? metrics.pathLength().getAsInt() + " passos"
                : "Sem rota na região";
            client.player.sendMessage(ElaraChat.info(settings.palette(), algorithm + "  " + outcome
                + "   " + elapsedLabel()), false);
        }
    }

    private void record(List<SearchEvent> events) {
        var options = settings.current();
        trace.accept(events, snapshot, options.historySize, options.reducedMotion || runMode() == PlayMode.INSTANT);
    }

    private PlayMode runMode() {
        return quickSearch ? PlayMode.INSTANT : settings.current().mode;
    }

    public void editSettings(Consumer<ElaraSettings> edit) {
        ElaraSettings previous = settings.current();
        settings.edit(edit);
        applySettings(previous);
    }

    public String togglePause() {
        return session != null && session.status() == SearchStatus.RUNNING ? pause()
            : session == null ? run() : resume();
    }

    public void applySettings(ElaraSettings previous) {
        ElaraSettings options = settings.current();
        if (previous.maxSide != options.maxSide || previous.maxStates != options.maxStates) {
            clearSnapshot();
        }
        if (previous.algorithm != options.algorithm) {
            selectAlgorithm(options.algorithm);
        }
        if (previous.mode != options.mode) {
            clock.reset();
            if (runMode() == PlayMode.STEP && session != null) {
                session.pause();
                timer.stop();
            }
        }
        trace.trim(options.historySize);
    }

    public SearchTrace trace() { return trace; }
    public GridPosition start() { return start; }
    public GridPosition goal() { return goal; }
    public SearchAlgorithm algorithm() { return algorithm; }
    public boolean showExploration() { return !quickSearch; }
    public SearchSession session() { return session; }
    public GridGraph graph() { return snapshot == null ? null : snapshot.graph(); }
    public String scanError() { return scanError; }

    public double surface(GridPosition position) {
        return snapshot == null ? position.y() + 1.0 / 16 : snapshot.surface(position);
    }

    public SearchMetrics metrics() {
        return session == null ? null : session.metrics();
    }

    public String phase() {
        if (session != null) {
            return session.status() == SearchStatus.EXHAUSTED ? "NO PATH" : session.status().name();
        }
        return snapshot != null ? "READY" : scanError == null ? "IDLE" : "ERROR";
    }

    public String phaseLabel() {
        return switch (phase()) {
            case "READY" -> "PRONTO";
            case "RUNNING" -> "BUSCANDO";
            case "PAUSED" -> "PAUSADO";
            case "FOUND" -> "ENCONTRADO";
            case "NO PATH" -> "SEM CAMINHO";
            case "ERROR" -> "ERRO";
            default -> "INATIVO";
        };
    }

    public String elapsedLabel() {
        long millis = timer.milliseconds();
        return millis == 0 ? "<1 ms" : millis < 1000 ? millis + " ms" : elapsedSeconds(',') + " s";
    }

    public String elapsedSeconds(char separator) {
        long tenths = timer.tenths();
        return Long.toString(tenths / 10) + separator + tenths % 10;
    }

    public static String coordinates(GridPosition position) {
        return position == null ? "—" : position.coordinates();
    }
}
