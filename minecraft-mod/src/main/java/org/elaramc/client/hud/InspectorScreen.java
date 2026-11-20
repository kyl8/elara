package org.elaramc.client.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.elaramc.client.ElaraChat;
import org.elaramc.client.config.ElaraSettings.Palette;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.input.PathWalker;
import org.elaramc.client.presentation.ScenarioManager;
import org.elaramc.client.search.SearchTrace.Expansion;
import org.elaramc.client.search.WorldSearch;
import org.elaramc.search.GridGraph;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchAlgorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

public final class InspectorScreen extends Screen {
    private enum Tab { ROUTE, DETAILS, SCENARIOS, HELP }
    private enum View { MOVES, AGENDA, VISITED, PATH }

    private final WorldSearch search;
    private final SettingsStore settings;
    private final ScenarioManager scenarios;
    private final PathWalker walker;
    private Tab tab;
    private View view = View.MOVES;
    private int pinnedExpansion;
    private int observedGeneration;
    private int page;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private String feedback = "";
    private boolean error;
    private String typedName = "";
    private int scenarioIndex;
    private ElaraButton run;
    private ElaraButton walk;
    private ElaraButton swap;
    private ElaraButton algorithm;
    private ElaraButton pause;

    public InspectorScreen(WorldSearch search, SettingsStore settings, ScenarioManager scenarios, PathWalker walker,
                           boolean openScenarios) {
        super(Text.literal("Elara"));
        this.search = search;
        this.settings = settings;
        this.scenarios = scenarios;
        this.walker = walker;
        tab = openScenarios ? Tab.SCENARIOS : Tab.ROUTE;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(356, width - 12);
        panelHeight = Math.min(232, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int x = left + 12;
        int inner = panelWidth - 24;
        button(left + panelWidth - 62, top + 7, 50, "Fechar", this::close);
        tabs(x, inner, top + 30, List.of("Rota", "Busca", "Cenários", "Ajuda"),
            tab.ordinal(), index -> {
                tab = Tab.values()[index];
                feedback = "";
                rebuild();
            });
        switch (tab) {
            case ROUTE -> routeButtons(x, inner);
            case DETAILS -> detailButtons(x, inner);
            case SCENARIOS -> scenarioButtons(x, inner);
            case HELP -> button(x, top + 156, inner, "Abrir comandos no chat",
                () -> client.setScreen(new ChatScreen("/elara help")));
        }
    }

    private void routeButtons(int x, int inner) {
        int actionX = x + inner - 102;
        button(actionX, top + 55, 102, "Marcar início", () -> perform(() -> {
            search.selectStart(feet());
            return "";
        }));
        button(actionX, top + 86, 102, "Marcar chegada", () -> perform(() -> {
            search.selectGoal(feet());
            return "";
        }));
        int third = (inner - 8) / 3;
        algorithm = button(x, top + 119, third, search.algorithm().name(), () -> perform(() -> {
            SearchAlgorithm next = search.algorithm() == SearchAlgorithm.BFS ? SearchAlgorithm.DFS : SearchAlgorithm.BFS;
            search.chooseAlgorithm(next);
            return "";
        }));
        swap = button(x + third + 4, top + 119, third, "Inverter pontos", () -> perform(search::swapPoints));
        button(x + 2 * (third + 4), top + 119, third, "Limpar", () -> perform(search::clearPoints));
        run = button(x, top + 144, inner - 99, "Buscar rota", () -> perform(search::startQuick));
        walk = button(x + inner - 95, top + 144, 95, "Caminhar", () -> perform(() -> {
            String message = walker.toggle(client);
            close();
            client.player.sendMessage(ElaraChat.info(settings.palette(), message), false);
            return "";
        }));
    }

    private void detailButtons(int x, int inner) {
        int quarter = (inner - 12) / 4;
        button(x, top + 80, quarter, "Executar", () -> perform(search::run));
        pause = button(x + quarter + 4, top + 80, quarter, "Pausar", () -> perform(search::togglePause));
        button(x + 2 * (quarter + 4), top + 80, quarter, "Passo", () -> perform(search::step));
        button(x + 3 * (quarter + 4), top + 80, quarter, "Reiniciar", () -> perform(search::reset));
        tabs(x, inner, top + 103, List.of("Ações", frontierName(), "Visitados", "Caminho"),
            view.ordinal(), index -> {
                view = View.values()[index];
                page = 0;
                feedback = "";
                rebuild();
            });
        int footer = top + panelHeight - 23;
        button(x, footer, 50, "Voltar", () -> page = Math.max(0, page - 1));
        button(x + 54, footer, 50, "Próxima", () -> page++);
        if (view == View.MOVES) {
            int right = x + inner;
            button(right - 146, footer, 50, "Anterior", () -> shiftHistory(-1));
            button(right - 92, footer, 50, "Seguinte", () -> shiftHistory(1));
            button(right - 38, footer, 38, "Agora", () -> {
                pinnedExpansion = 0;
                page = 0;
            });
        }
    }

    private void scenarioButtons(int x, int inner) {
        int half = (inner - 4) / 2;
        button(x, top + 84, half, "Anterior", () -> changeScenario(-1));
        button(x + half + 4, top + 84, half, "Próximo", () -> changeScenario(1));
        button(x, top + 107, half, "Carregar", () -> perform(() ->
            scenarios.load(client, scenarios.names().get(scenarioIndex), search.algorithm())));
        button(x + half + 4, top + 107, half, "Repetir", () -> perform(() -> scenarios.replay(client)));
        var field = new TextFieldWidget(textRenderer, x + 5, top + 151, inner - 67, 17, Text.literal("Nome do cenário"));
        field.setMaxLength(40);
        field.setDrawsBackground(false);
        field.setText(typedName);
        field.setPlaceholder(Text.literal("nome-do-cenario"));
        field.setChangedListener(value -> typedName = value);
        addDrawableChild(field);
        button(x + inner - 55, top + 148, 55, "Salvar", () -> perform(() -> scenarios.save(client, typedName)));
    }

    private void changeScenario(int delta) {
        scenarioIndex = Math.floorMod(scenarioIndex + delta, scenarios.names().size());
    }

    private void tabs(int x, int inner, int y, List<String> labels, int selected, IntConsumer select) {
        int w = (inner - 12) / labels.size();
        for (int index = 0; index < labels.size(); index++) {
            int item = index;
            addDrawableChild(new ElaraButton(x + index * (w + 4), y, w, 17, labels.get(index),
                ignored -> select.accept(item), settings, item == selected));
        }
    }

    private ElaraButton button(int x, int y, int width, String label, Runnable action) {
        return addDrawableChild(new ElaraButton(x, y, width, 17, label, ignored -> action.run(), settings, false));
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    private void perform(Supplier<String> action) {
        try {
            feedback = action.get().replace(" | ", "\n");
            error = false;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            feedback = exception.getMessage();
            error = true;
        }
    }

    private GridPosition feet() {
        var position = client.player.getBlockPos();
        return new GridPosition(position.getX(), position.getY(), position.getZ());
    }

    @Override
    public void render(DrawContext draw, int mouseX, int mouseY, float delta) {
        var colors = settings.palette();
        draw.fill(0, 0, width, height, Palette.alpha(colors.obsidian(), 96));
        draw.fill(left, top, left + panelWidth, top + panelHeight, colors.obsidian());
        draw.drawText(textRenderer, Text.literal("Elara").styled(style -> style.withBold(true)),
            left + 12, top + 12, colors.pearl(), false);
        rule(draw, top + 51, colors);
        switch (tab) {
            case ROUTE -> routeText(draw, colors);
            case DETAILS -> detailText(draw, colors);
            case SCENARIOS -> scenarioText(draw, colors);
            case HELP -> helpText(draw, colors);
        }
        super.render(draw, mouseX, mouseY, delta);
    }

    private void routeText(DrawContext draw, Palette colors) {
        int x = left + 12;
        endpoint(draw, "Início", search.start(), x, top + 57, colors.pearl(), colors);
        endpoint(draw, "Chegada", search.goal(), x, top + 88, colors.sage(), colors);
        rule(draw, top + 111, colors);
        String selected = search.algorithm().name();
        if (!algorithm.getMessage().getString().equals(selected)) {
            algorithm.setMessage(Text.literal(selected));
        }
        boolean ready = search.start() != null && search.goal() != null;
        run.active = ready && !scenarios.loading();
        swap.active = ready;
        String walkLabel = walker.walking() ? "Parar" : "Caminhar";
        if (!walk.getMessage().getString().equals(walkLabel)) {
            walk.setMessage(Text.literal(walkLabel));
        }
        walk.active = walker.walking() || (search.phase().equals("FOUND") && client.getServer() != null);

        int accent = SearchHud.phaseColor(colors, search.phase());
        line(draw, SearchHud.routeState(search), x, top + 174, accent);
        var metrics = search.metrics();
        String description = error ? feedback : search.scanError() != null ? search.scanError()
            : metrics != null && metrics.pathLength().isPresent()
                ? metrics.pathLength().getAsInt() + " passos    " + search.elapsedLabel()
            : !feedback.isEmpty() ? feedback
            : ready ? (search.algorithm() == SearchAlgorithm.BFS ? "BFS busca o caminho mais curto." : "DFS explora em profundidade.")
            : "As marcas usam o bloco onde você está.";
        wrapped(draw, description, x, top + 189, error ? colors.champagne() : colors.pearl(), 2);
        if (walker.hasTravelTime() && !error && panelHeight >= 224) {
            line(draw, "Caminhada    " + walker.travelLabel(), x, top + 204, colors.iceBlue());
        }
    }

    private void endpoint(DrawContext draw, String label, GridPosition point, int x, int y, int accent, Palette colors) {
        line(draw, label, x, y, accent);
        line(draw, point == null ? "Não marcado" : point.coordinates(), x, y + 12, colors.pearl());
    }

    private void scenarioText(DrawContext draw, Palette colors) {
        int x = left + 12;
        var names = scenarios.names();
        scenarioIndex = Math.min(scenarioIndex, names.size() - 1);
        line(draw, names.get(scenarioIndex), x, top + 59, colors.pearl());
        line(draw, (scenarioIndex + 1) + " de " + names.size(), x, top + 71, colors.lavender());
        rule(draw, top + 134, colors);
        draw.fill(x, top + 148, left + panelWidth - 71, top + 165, Palette.alpha(colors.pearl(), 17));
        String message = error ? feedback
            : scenarios.loading() ? "Carregando cenário…"
            : !scenarios.activeName().isEmpty() ? "Ativo: " + scenarios.activeName()
            : feedback;
        wrapped(draw, message, x, top + 180, error ? colors.champagne() : colors.pearl(), 2);
    }

    private void helpText(DrawContext draw, Palette colors) {
        int x = left + 12;
        line(draw, "Marque e busque", x, top + 60, colors.lavender());
        line(draw, "/elara origin", x, top + 77, colors.iceBlue());
        line(draw, "/elara finish", x, top + 91, colors.iceBlue());
        line(draw, "/elara start", x, top + 105, colors.iceBlue());
        wrapped(draw, "Acrescente look para marcar o piso que você está mirando.", x, top + 126, colors.pearl(), 2);
    }

    private void detailText(DrawContext draw, Palette colors) {
        int x = left + 12;
        var metrics = search.metrics();
        var expansion = selectedExpansion();
        boolean paused = search.phase().equals("PAUSED");
        String pauseLabel = paused ? "Retomar" : "Pausar";
        if (!pause.getMessage().getString().equals(pauseLabel)) {
            pause.setMessage(Text.literal(pauseLabel));
        }
        pause.active = paused || search.phase().equals("RUNNING");
        line(draw, search.algorithm() + "     " + SearchHud.routeState(search), x, top + 57,
            SearchHud.phaseColor(colors, search.phase()));
        String totals = metrics == null ? "Use Executar ou Passo para acompanhar."
            : metrics.expanded() + " expandidos    " + metrics.discovered() + " descobertos    " + search.elapsedLabel();
        line(draw, totals, x, top + 69, colors.pearl());
        String title = switch (view) {
            case MOVES -> expansion == null ? "Aguardando a primeira expansão"
                : "Expansão " + expansion.state().number() + "     " + expansion.state().state().coordinates();
            case AGENDA -> metrics == null ? frontierName() : frontierName() + "     " + metrics.frontierSize() + " pendentes";
            case VISITED -> "Estado e predecessor";
            case PATH -> "Coordenadas da rota";
        };
        line(draw, title, x, top + 128, colors.lavender());
        int rows = Math.max(1, (panelHeight - 167) / 11);
        int count = detailCount(expansion);
        int pages = Math.max(1, (count + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        if (error) {
            wrapped(draw, feedback, x, top + 143, colors.champagne(), Math.min(2, rows));
        } else {
            var lines = detailLines(expansion, page * rows, rows);
            if (lines.isEmpty()) {
                line(draw, "Sem dados nesta aba", x, top + 143, colors.pearl());
            }
            for (int index = 0; index < lines.size(); index++) {
                line(draw, lines.get(index), x, top + 143 + index * 11,
                    view == View.PATH ? colors.champagne() : colors.pearl());
            }
        }
        line(draw, (page + 1) + "/" + pages, x + 111, top + panelHeight - 19, colors.lavender());
    }

    private String frontierName() {
        return search.algorithm() == SearchAlgorithm.BFS ? "Fila" : "Pilha";
    }

    private int detailCount(Expansion expansion) {
        if (search.session() == null) {
            return 0;
        }
        return switch (view) {
            case MOVES -> expansion == null ? 0
                : directions(expansion).size() + 1 + expansion.discovered().size() + expansion.skipped().size();
            case AGENDA -> search.session().agendaSize();
            case VISITED -> search.metrics().discovered();
            case PATH -> search.session().path().size();
        };
    }

    private List<String> detailLines(Expansion expansion, int start, int count) {
        var session = search.session();
        if (session == null) {
            return List.of();
        }
        var lines = new ArrayList<String>();
        switch (view) {
            case MOVES -> {
                if (expansion != null) {
                    for (var move : directions(expansion)) {
                        lines.add(move.direction().label() + "    " + WorldSearch.coordinates(move.target())
                            + "    " + moveLabel(move.status()));
                    }
                    lines.add(expansion.discovered().size() + " novos    " + expansion.totalSkipped() + " ignorados");
                    for (var child : expansion.discovered()) {
                        lines.add("+" + child.state().coordinates() + "  ←  " + child.predecessor().coordinates());
                    }
                    for (var skip : expansion.skipped()) {
                        lines.add(skip.transition().direction().label() + "    " + skip.source().coordinates() + "    "
                            + (skip.alreadyDiscovered() ? "Já descoberto" : moveLabel(skip.transition().status())));
                    }
                }
                return lines.subList(Math.min(start, lines.size()), Math.min(start + count, lines.size()));
            }
            case AGENDA -> session.agendaPage(start, count).forEach(position -> lines.add(position.coordinates()));
            case VISITED -> {
                for (var position : session.discoveredPage(start, count)) {
                    GridPosition parent = session.predecessor(position);
                    lines.add(position.coordinates() + (parent == null ? "    início" : "  ←  " + parent.coordinates()));
                }
            }
            case PATH -> {
                var path = session.path();
                for (int index = start; index < Math.min(path.size(), start + count); index++) {
                    lines.add(index + "    " + path.get(index).coordinates());
                }
            }
        }
        return lines;
    }

    private List<GridGraph.Transition> directions(Expansion expansion) {
        var graph = search.graph();
        return graph.directions().stream()
            .map(direction -> graph.transition(expansion.state().state(), direction))
            .filter(move -> graph.region().height() == 1 || move.status() == GridGraph.MoveStatus.OPEN)
            .toList();
    }

    private static String moveLabel(GridGraph.MoveStatus status) {
        return switch (status) {
            case OPEN -> "Livre";
            case OUTSIDE_REGION -> "Fora da região";
            case BLOCKED_CELL -> "Bloqueado";
            case BLOCKED_TRANSITION -> "Passagem bloqueada";
        };
    }

    private Expansion selectedExpansion() {
        var trace = search.trace();
        if (observedGeneration != trace.generation()) {
            observedGeneration = trace.generation();
            pinnedExpansion = 0;
            page = 0;
        }
        var newest = trace.historyAt(0);
        return newest == null || pinnedExpansion == 0 ? newest
            : trace.historyAt(Math.max(0, Math.min(trace.historySize() - 1, newest.state().number() - pinnedExpansion)));
    }

    private void shiftHistory(int delta) {
        Expansion current = selectedExpansion();
        Expansion newest = search.trace().historyAt(0);
        if (current != null) {
            int oldest = newest.state().number() - search.trace().historySize() + 1;
            pinnedExpansion = Math.max(oldest, Math.min(newest.state().number(), current.state().number() + delta));
            page = 0;
        }
    }

    private void rule(DrawContext draw, int y, Palette colors) {
        draw.fill(left + 12, y, left + panelWidth - 12, y + 1, Palette.alpha(colors.pearl(), 36));
    }

    private void line(DrawContext draw, String text, int x, int y, int color) {
        draw.drawText(textRenderer, textRenderer.trimToWidth(text, left + panelWidth - 12 - x), x, y, color, false);
    }

    private void wrapped(DrawContext draw, String text, int x, int y, int color, int limit) {
        var lines = textRenderer.wrapLines(Text.literal(text), panelWidth - 24);
        for (int index = 0; index < Math.min(limit, lines.size()); index++) {
            draw.drawText(textRenderer, lines.get(index), x, y + index * 11, color, false);
        }
    }
}
