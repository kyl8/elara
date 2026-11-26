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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Owns a client's current selection, graph snapshot, and search session. */
public final class WorldSearch {
    private static final int PATH_PAGE_SIZE = 6;
    private static final int SCAN_WORK_PER_TICK = 512;
    private static final long SCAN_NANOS_PER_TICK = 2_000_000;
    private static final long INSTANT_NANOS_PER_TICK = 2_000_000;

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
    private WorldScanner.Scan pendingScan;
    private Runnable scenarioReady;
    private Supplier<String> afterScan;
    private String scanSummary;

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
        scanSummary = null;
        pendingScan = null;
        scenarioReady = null;
        afterScan = null;
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
        if (pendingScan != null) {
            return scanProgress();
        }
        try {
            WorldScanner.Scan next = beginScan(selectedRegion(), start, goal);
            clearSnapshot();
            pendingScan = next;
            return "Varredura iniciada. Use /elara status para acompanhar.";
        } catch (IllegalArgumentException | IllegalStateException exception) {
            scanError = exception.getMessage();
            throw exception;
        }
    }

    private WorldScanner.Scan beginScan(SearchRegion region, GridPosition origin, GridPosition target) {
        return scanner.begin(world, region, origin, target, settings.current().maxSide, settings.current().maxStates);
    }

    private String scanProgress() {
        return "Analisando região | " + pendingScan.columns() + " colunas lidas | Etapa " + pendingScan.attempts();
    }

    private String afterScan(Supplier<String> action) {
        if (pendingScan == null) {
            scan();
        }
        afterScan = action;
        return "Analisando a região antes de executar a busca.";
    }

    private SearchRegion selectedRegion() {
        requireEndpoints();
        if (firstCorner == null && secondCorner == null) {
            return null;
        }
        if (firstCorner == null || secondCorner == null) {
            throw new IllegalStateException("Marque os dois cantos ou use /elara region auto.");
        }
        return manualRegion();
    }

    private SearchRegion manualRegion() {
        return new SearchRegion(
            Math.min(firstCorner.x(), secondCorner.x()), Math.min(firstCorner.z(), secondCorner.z()),
            Math.max(firstCorner.x(), secondCorner.x()), Math.max(firstCorner.z(), secondCorner.z()),
            Math.min(firstCorner.y(), secondCorner.y()), Math.max(firstCorner.y(), secondCorner.y()));
    }

    private void requireEndpoints() {
        if (start == null || goal == null) {
            throw new IllegalStateException("Marque o início com /elara origin e a chegada com /elara finish.");
        }
    }

    public void prepareScenario(SearchRegion region, GridPosition origin, GridPosition target, SearchAlgorithm selected,
                                Runnable onReady) {
        WorldScanner.Scan next = beginScan(region, origin, target);
        clearSnapshot();
        start = origin;
        goal = target;
        firstCorner = new GridPosition(region.minX(), region.y(), region.minZ());
        secondCorner = new GridPosition(region.maxX(), region.maxY(), region.maxZ());
        pendingScan = next;
        algorithm = selected;
        scenarioReady = onReady;
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
            afterScan = null;
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

    public SearchSession routeFrom(GridPosition position, GridPosition destination, SearchAlgorithm selected,
                                   SearchRegion preferred) {
        boolean sameGoal = destination.equals(goal);
        if (snapshot != null && sameGoal && graph().contains(position)) {
            start = position;
        } else {
            SearchRegion bounds = preferred;
            if (bounds == null && sameGoal && firstCorner != null && secondCorner != null) {
                bounds = manualRegion();
            }
            if (bounds != null && bounds.contains(position) && bounds.contains(destination)) {
                prepareScenario(bounds, position, destination, selected, null);
            } else {
                start = position;
                goal = destination;
                useAutomaticRegion();
            }
        }
        run(selected, true);
        return session;
    }

    private String run(SearchAlgorithm selection, boolean instant) {
        if (snapshot == null) {
            String message = afterScan(() -> run(selection, instant));
            algorithm = selection;
            return message;
        }
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
        if (snapshot == null) {
            return afterScan(this::step);
        }
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
        if (pendingScan != null) {
            afterScan = null;
            return "A varredura continua. Use /elara run quando estiver pronta.";
        }
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
        if (snapshot == null) {
            return afterScan(this::reset);
        }
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

    private SearchSession requireSession() {
        if (session == null) {
            if (pendingScan != null) {
                throw new IllegalStateException("Aguarde a conclusão da varredura. Use /elara status.");
            }
            throw new IllegalStateException("Inicie a busca com /elara start.");
        }
        return session;
    }

    private SearchSession newSession(SearchAlgorithm selection) {
        return new SearchSession(graph(), start, goal, selection, SearchTrace.STORED_SKIPS_PER_STEP);
    }

    public String status() {
        if (pendingScan != null) {
            return scanProgress();
        }
        if (session == null) {
            String readiness = snapshot != null ? scanSummary : scanError != null ? scanError : "Defina e analise uma região";
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
        if (client.player != null && pendingScan != null) {
            advanceScan(client);
            return;
        }
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
        PlayMode mode = runMode();
        int budget = clock.budget(mode, options.expansionsPerSecond);
        long started = mode == PlayMode.INSTANT ? System.nanoTime() : 0;
        for (int index = 0; index < budget && session.status() == SearchStatus.RUNNING; index++) {
            record(session.advance());
            if (mode == PlayMode.INSTANT && System.nanoTime() - started >= INSTANT_NANOS_PER_TICK) {
                break;
            }
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

    private void advanceScan(MinecraftClient client) {
        WorldScanner.Scan scan = pendingScan;
        try {
            if (!scan.advance(SCAN_WORK_PER_TICK, SCAN_NANOS_PER_TICK)) {
                return;
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            clearSnapshot();
            scanError = exception.getMessage();
            client.player.sendMessage(ElaraChat.warning(settings.palette(), scanError), false);
            return;
        }

        snapshot = scan.result();
        pendingScan = null;
        SearchRegion region = graph().region();
        String note = scan.boundaryLimited()
            ? " | O limite da região ou dos chunks carregados ainda pode esconder desvios." : "";
        scanSummary = graph().size() + " estados | " + WorldScanner.dimensions(region) + " | " + scan.columns()
            + " colunas | " + scan.attempts() + " etapa(s) | " + scan.elapsedMillis() + " ms"
            + " | X " + region.minX() + " a " + region.maxX() + " | Z " + region.minZ() + " a " + region.maxZ()
            + " | Y " + region.y() + " a " + region.maxY() + note;
        client.player.sendMessage(ElaraChat.info(settings.palette(), "Varredura concluída | " + scanSummary), false);

        Runnable onReady = scenarioReady;
        Supplier<String> queued = afterScan;
        scenarioReady = null;
        afterScan = null;
        try {
            if (onReady != null) onReady.run();
            if (queued != null) {
                String message = queued.get();
                if (!message.isEmpty()) client.player.sendMessage(ElaraChat.info(settings.palette(), message), false);
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            client.player.sendMessage(ElaraChat.warning(settings.palette(), exception.getMessage()), false);
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
        return (pendingScan != null && afterScan != null) || (session != null && session.status() == SearchStatus.RUNNING) ? pause()
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
    public boolean scanning() { return pendingScan != null; }

    public double surface(GridPosition position) {
        return snapshot == null ? position.y() + 1.0 / 16 : snapshot.surface(position);
    }

    public SearchMetrics metrics() {
        return session == null ? null : session.metrics();
    }

    public String phase() {
        if (pendingScan != null) {
            return "SCANNING";
        }
        if (session != null) {
            return session.status() == SearchStatus.EXHAUSTED ? "NO PATH" : session.status().name();
        }
        return snapshot != null ? "READY" : scanError == null ? "IDLE" : "ERROR";
    }

    public String phaseLabel() {
        return switch (phase()) {
            case "SCANNING" -> "ANALISANDO";
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
        return timer.label();
    }

    public String elapsedSeconds(char separator) {
        long tenths = timer.tenths();
        return Long.toString(tenths / 10) + separator + tenths % 10;
    }

    public static String coordinates(GridPosition position) {
        return position == null ? "—" : position.coordinates();
    }
}
