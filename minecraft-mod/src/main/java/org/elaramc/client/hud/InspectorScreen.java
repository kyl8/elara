package org.elaramc.client.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.elaramc.client.ElaraChat;
import net.minecraft.util.Formatting;
import org.elaramc.client.config.ElaraSettings;
import org.elaramc.client.config.ElaraSettings.Palette;
import org.elaramc.client.config.PlayMode;
import org.elaramc.client.config.SettingsStore;
import org.elaramc.client.input.PathWalker;
import org.elaramc.client.search.SearchTrace.Expansion;
import org.elaramc.client.search.WorldSearch;
import org.elaramc.client.presentation.ScenarioManager;
import org.elaramc.search.Direction;
import org.elaramc.search.GridGraph;
import org.elaramc.search.GridPosition;
import org.elaramc.search.SearchAlgorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class InspectorScreen extends Screen {
    private enum Tab { SETUP, INSPECT, SCENARIOS }
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

    public InspectorScreen(WorldSearch search, SettingsStore settings, ScenarioManager scenarios, PathWalker walker,
                           boolean openScenarios) {
        super(Text.literal("Inspetor de busca do Elara"));
        this.search = search;
        this.settings = settings;
        this.scenarios = scenarios;
        this.walker = walker;
        tab = openScenarios ? Tab.SCENARIOS : search.graph() == null ? Tab.SETUP : Tab.INSPECT;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(430, width - 12);
        panelHeight = Math.min(254, height - 10);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int x = left + 12;
        int inner = panelWidth - 24;
        int tabWidth = (inner - 12) / 4;
        int stride = tabWidth + 4;
        button(x, top + 33, tabWidth, "Preparar", () -> open(Tab.SETUP), tab == Tab.SETUP);
        button(x + stride, top + 33, tabWidth, "Inspecionar", () -> open(Tab.INSPECT), tab == Tab.INSPECT);
        button(x + 2 * stride, top + 33, tabWidth, "Cenários", () -> open(Tab.SCENARIOS), tab == Tab.SCENARIOS);
        button(x + 3 * stride, top + 33, tabWidth, "Fechar", this::close, false);
        if (tab == Tab.SETUP) {
            setupButtons(x, inner);
        } else if (tab == Tab.INSPECT) {
            inspectButtons(x, inner);
        } else {
            scenarioButtons(x, inner);
        }
    }

    private void setupButtons(int x, int inner) {
        int buttonWidth = (inner - 8) / 3;
        int stride = buttonWidth + 4;
        int y = top + 84;
        button(x, y, buttonWidth, "Marcar início", () -> perform(() -> {
            search.selectStart(feet());
            return "Início em " + feet().coordinates();
        }), false);
        button(x + stride, y, buttonWidth, "Marcar meta", () -> perform(() -> {
            search.selectGoal(feet());
            return "Meta em " + feet().coordinates();
        }), false);
        button(x + stride * 2, y, buttonWidth, "Analisar", () -> perform(search::scan), false);
        button(x, y + 21, buttonWidth, "Canto A", () -> perform(() -> {
            search.selectFirstCorner(feet());
            return "Canto A " + feet().coordinates();
        }), false);
        button(x + stride, y + 21, buttonWidth, "Canto B", () -> perform(() -> {
            search.selectSecondCorner(feet());
            return "Canto B " + feet().coordinates();
        }), false);
        button(x + stride * 2, y + 21, buttonWidth, "Limites auto.", () -> perform(() -> {
            search.useAutomaticRegion();
            return "Limites automáticos selecionados.";
        }), false);
        button(x, y + 42, buttonWidth, "Algoritmo", () -> perform(() -> {
            var next = search.algorithm() == SearchAlgorithm.BFS ? SearchAlgorithm.DFS : SearchAlgorithm.BFS;
            search.editSettings(options -> options.algorithm = next);
            search.selectAlgorithm(next);
            return next + " selecionado.";
        }), false);
        button(x + stride, y + 42, buttonWidth, "Modo", () -> perform(() -> {
            var modes = PlayMode.values();
            PlayMode next = modes[(settings.current().mode.ordinal() + 1) % modes.length];
            search.editSettings(options -> options.mode = next);
            return "Modo " + next.label() + " selecionado.";
        }), false);
        button(x + stride * 2, y + 42, buttonWidth, "Executar", () -> perform(search::run), false);
        button(x, y + 63, buttonWidth, "Pausar / retomar", () -> perform(search::togglePause), false);
        button(x + stride, y + 63, buttonWidth, "Passo", () -> perform(search::step), false);
        button(x + stride * 2, y + 63, buttonWidth, "Reiniciar", () -> perform(search::reset), false);
        button(x, y + 84, buttonWidth, "Mais lento", () -> perform(() -> changeSpeed(-4)), false);
        button(x + stride, y + 84, buttonWidth, "Mais rápido", () -> perform(() -> changeSpeed(4)), false);
        button(x + stride * 2, y + 84, buttonWidth, "Caminhar", () -> perform(() -> {
            String result = walker.toggle(client);
            close();
            client.player.sendMessage(ElaraChat.info(settings.palette(), result), false);
            return result;
        }), false);
    }

    private void inspectButtons(int x, int inner) {
        int y = top + 83;
        button(x, y, 57, "Anterior", () -> shiftHistory(-1), false);
        button(x + 61, y, 57, "Seguinte", () -> shiftHistory(1), false);
        button(x + 122, y, 48, "Ao vivo", () -> {
            pinnedExpansion = 0;
            page = 0;
        }, false);
        int buttonWidth = (inner - 12) / 4;
        int stride = buttonWidth + 4;
        viewButton(x, y + 21, buttonWidth, "Ações", View.MOVES);
        viewButton(x + stride, y + 21, buttonWidth, "Fronteira", View.AGENDA);
        viewButton(x + stride * 2, y + 21, buttonWidth, "Descobertos", View.VISITED);
        viewButton(x + stride * 3, y + 21, buttonWidth, "Caminho", View.PATH);
        int footer = top + panelHeight - 23;
        button(x, footer, 52, "Voltar", () -> page = Math.max(0, page - 1), false);
        button(x + 56, footer, 52, "Próxima", () -> page++, false);
    }

    private void scenarioButtons(int x, int inner) {
        int half = (inner - 4) / 2;
        button(x, top + 88, half, "Cenário anterior", () -> changeScenario(-1), false);
        button(x + half + 4, top + 88, half, "Próximo cenário", () -> changeScenario(1), false);
        int third = (inner - 8) / 3;
        int stride = third + 4;
        button(x, top + 109, third, "Carregar", () -> perform(() -> load(null)), false);
        button(x + stride, top + 109, third, "Carregar BFS", () -> perform(() -> load(SearchAlgorithm.BFS)), false);
        button(x + 2 * stride, top + 109, third, "Carregar DFS", () -> perform(() -> load(SearchAlgorithm.DFS)), false);

        var field = new TextFieldWidget(textRenderer, x + 4, top + 152, inner - 66, 17, Text.literal("Nome do cenário"));
        field.setMaxLength(40);
        field.setDrawsBackground(false);
        field.setText(typedName);
        field.setPlaceholder(Text.literal("nome-do-cenario"));
        field.setChangedListener(value -> typedName = value);
        addDrawableChild(field);
        button(x + inner - 55, top + 149, 55, "Salvar", () -> perform(() -> scenarios.save(client, typedName)), false);
        button(x, top + 175, half, "Repetir busca", () -> perform(() -> scenarios.replay(client)), false);
        button(x + half + 4, top + 175, half, "Reler ajustes", () -> perform(scenarios::reload), false);
    }

    private void changeScenario(int delta) {
        scenarioIndex = Math.floorMod(scenarioIndex + delta, scenarios.names().size());
    }

    private String load(SearchAlgorithm algorithm) {
        return scenarios.load(client, scenarios.names().get(scenarioIndex), algorithm);
    }

    private void viewButton(int x, int y, int width, String label, View next) {
        button(x, y, width, label, () -> {
            view = next;
            page = 0;
            rebuild();
        }, view == next);
    }

    private String changeSpeed(int delta) {
        search.editSettings(options -> options.expansionsPerSecond =
            Math.max(1, Math.min(ElaraSettings.MAX_SPEED, options.expansionsPerSecond + delta)));
        return settings.current().expansionsPerSecond + " expansões por segundo.";
    }

    private void open(Tab next) {
        tab = next;
        feedback = "";
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    private void button(int x, int y, int width, String label, Runnable action, boolean selected) {
        addDrawableChild(new ElaraButton(x, y, width, 17, label, ignored -> action.run(), settings, selected));
    }

    private void perform(Supplier<String> action) {
        try {
            feedback = action.get();
            error = false;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            feedback = exception.getMessage();
            error = true;
        }
    }

    private GridPosition feet() {
        if (client.player == null) {
            throw new IllegalStateException("Abra um mundo primeiro.");
        }
        var position = client.player.getBlockPos();
        return new GridPosition(position.getX(), position.getY(), position.getZ());
    }

    @Override
    public void render(DrawContext draw, int mouseX, int mouseY, float delta) {
        var colors = settings.palette();
        draw.fill(0, 0, width, height, Palette.alpha(colors.obsidian(), 96));
        draw.fill(left, top, left + panelWidth, top + panelHeight, colors.obsidian());
        draw.fill(left, top, left + panelWidth, top + 1, Palette.alpha(colors.lavender(), 183));
        var matrix = draw.getMatrices();
        matrix.push();
        matrix.translate(left + 12, top + 10, 0);
        matrix.scale(1.5f, 1.5f, 1);
        draw.drawText(textRenderer, Text.literal("elara.").formatted(Formatting.ITALIC), 0, 0, colors.pearl(), false);
        matrix.pop();
        line(draw, tab == Tab.SETUP ? "Todo caminho começa com uma escolha." : "INSPETOR DE BUSCA",
            left + 62, top + 14, colors.lavender());
        draw.fill(left + 12, top + 56, left + panelWidth - 12, top + 57, Palette.alpha(colors.pearl(), 55));
        if (tab == Tab.SETUP) {
            setupText(draw, colors);
        } else if (tab == Tab.INSPECT) {
            inspectText(draw, colors);
        } else {
            scenarioText(draw, colors);
        }
        super.render(draw, mouseX, mouseY, delta);
    }

    private void setupText(DrawContext draw, Palette colors) {
        int x = left + 12;
        line(draw, "Início " + WorldSearch.coordinates(search.start()), x, top + 61, colors.pearl());
        line(draw, "Meta " + WorldSearch.coordinates(search.goal()), x, top + 72, colors.sage());
        var options = settings.current();
        line(draw, search.algorithm() + "  ·  " + options.mode.label() + "  ·  " + options.expansionsPerSecond + " exp/s",
            x, top + 194, colors.lavender());
        var message = feedback.isEmpty() ? search.graph() == null ? "Marque os pontos na posição do jogador e analise a região."
            : search.graph().size() + " estados transitáveis · " + search.phaseLabel() : feedback;
        var wrapped = textRenderer.wrapLines(Text.literal(message), panelWidth - 24);
        for (int index = 0; index < Math.min(3, wrapped.size()); index++) {
            draw.drawText(textRenderer, wrapped.get(index), x, top + 208 + index * 10,
                error ? colors.champagne() : colors.pearl(), false);
        }
    }

    private void scenarioText(DrawContext draw, Palette colors) {
        int x = left + 12;
        var names = scenarios.names();
        scenarioIndex = Math.min(scenarioIndex, names.size() - 1);
        line(draw, names.get(scenarioIndex), x, top + 62, colors.pearl());
        line(draw, (scenarioIndex + 1) + " de " + names.size() + "  ·  "
            + (scenarios.loading() ? "Carregando" : scenarios.activeName().isEmpty() ? "Selecione um cenário" : "Ativo: " + scenarios.activeName()),
            x, top + 74, colors.lavender());
        line(draw, "Salvar seleção e câmera atuais", x, top + 136, colors.lavender());
        draw.fill(x, top + 149, left + panelWidth - 71, top + 166, Palette.alpha(colors.pearl(), 24));
        String message = feedback.isEmpty() ? "Ao carregar, a câmera muda no modo solo." : feedback;
        var wrapped = textRenderer.wrapLines(Text.literal(message), panelWidth - 24);
        for (int index = 0; index < Math.min(2, wrapped.size()); index++) {
            draw.drawText(textRenderer, wrapped.get(index), x, top + 201 + index * 11,
                error ? colors.champagne() : colors.pearl(), false);
        }
    }

    private void inspectText(DrawContext draw, Palette colors) {
        int x = left + 12;
        var metrics = search.metrics();
        var expansion = selectedExpansion();
        String phase = search.phase();
        String state = expansion == null ? "Aguardando expansão" : "Expansão " + expansion.state().number()
            + "  ·  " + expansion.state().state().coordinates();
        line(draw, search.algorithm() + " " + search.phaseLabel() + "  ·  " + state, x, top + 60,
            SearchHud.phaseColor(colors, phase));
        String counts = metrics == null ? "Execute a busca ou avance um passo."
            : "Expandidos " + metrics.expanded() + " · Descobertos " + metrics.discovered()
                + " · Fronteira " + metrics.frontierSize() + " · " + search.elapsedLabel();
        line(draw, counts, x, top + 72, colors.pearl());
        int rows = Math.max(1, (panelHeight - 154) / 11);
        int count = detailCount(expansion);
        int pages = Math.max(1, (count + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        List<String> lines = detailLines(expansion, page * rows, rows);
        if (lines.isEmpty()) {
            line(draw, "Nenhum dado nesta aba.", x, top + 128, colors.lavender());
        }
        for (int index = 0; index < lines.size(); index++) {
            int y = top + 127 + index * 11;
            if (index % 2 == 0) {
                draw.fill(x - 2, y - 1, left + panelWidth - 10, y + 9, Palette.alpha(colors.pearl(), 12));
            }
            int ink = view == View.PATH ? colors.champagne() : colors.pearl();
            line(draw, lines.get(index), x, y, ink);
        }
        line(draw, (page + 1) + "/" + pages, x + 116, top + panelHeight - 19, colors.lavender());
    }

    private int detailCount(Expansion expansion) {
        if (search.session() == null) {
            return 0;
        }
        return switch (view) {
            case MOVES -> expansion == null ? 0 : 5 + expansion.discovered().size() + expansion.skipped().size();
            case AGENDA -> 1 + search.session().agendaSize();
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
        if (view == View.MOVES && expansion != null) {
            for (Direction direction : Direction.values()) {
                var move = search.graph().transition(expansion.state().state(), direction);
                lines.add(directionLabel(direction) + "  " + WorldSearch.coordinates(move.target()) + "  " + moveLabel(move.status()));
            }
            lines.add("Novos " + expansion.discovered().size() + "  ·  Ignorados " + expansion.totalSkipped()
                + (expansion.skipped().size() < expansion.totalSkipped() ? " (exibe os primeiros " + expansion.skipped().size() + ")" : ""));
            for (var child : expansion.discovered()) {
                lines.add("+" + child.state().coordinates() + "  ←  " + child.predecessor().coordinates());
            }
            for (var skip : expansion.skipped()) {
                lines.add(directionLabel(skip.transition().direction()) + " em " + skip.source().coordinates() + "  "
                    + (skip.alreadyDiscovered() ? "Já descoberto" : moveLabel(skip.transition().status())));
            }
            return lines.subList(Math.min(start, lines.size()), Math.min(start + count, lines.size()));
        }
        if (view == View.AGENDA) {
            if (start == 0) {
                lines.add(search.algorithm() == SearchAlgorithm.BFS ? "Fila FIFO atual · início primeiro" : "Pilha LIFO atual · topo primeiro");
            }
            for (var position : session.agendaPage(Math.max(0, start - 1), count - lines.size())) {
                lines.add(position.coordinates());
            }
        } else if (view == View.VISITED) {
            for (var position : session.discoveredPage(start, count)) {
                GridPosition parent = session.predecessor(position);
                lines.add(position.coordinates() + (parent == null ? "  início" : "  ←  " + parent.coordinates()));
            }
        } else if (view == View.PATH) {
            var path = session.path();
            for (int index = start; index < Math.min(path.size(), start + count); index++) {
                lines.add(index + "   " + path.get(index).coordinates());
            }
        }
        return lines;
    }

    private static String directionLabel(Direction direction) {
        return switch (direction) {
            case NORTH -> "Norte";
            case SOUTH -> "Sul";
            case EAST -> "Leste";
            case WEST -> "Oeste";
        };
    }

    private static String moveLabel(GridGraph.MoveStatus status) {
        return switch (status) {
            case OPEN -> "Livre";
            case OUTSIDE_REGION -> "Fora da região";
            case BLOCKED_CELL -> "Posição bloqueada";
            case BLOCKED_TRANSITION -> "Movimento bloqueado";
        };
    }

    private Expansion selectedExpansion() {
        var trace = search.trace();
        if (observedGeneration != trace.generation()) {
            observedGeneration = trace.generation();
            pinnedExpansion = 0;
            page = 0;
        }
        Expansion newest = trace.historyAt(0);
        if (newest == null || pinnedExpansion == 0) {
            return newest;
        }
        return trace.historyAt(Math.max(0, Math.min(trace.historySize() - 1, newest.state().number() - pinnedExpansion)));
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

    private void line(DrawContext draw, String text, int x, int y, int color) {
        draw.drawText(textRenderer, textRenderer.trimToWidth(text, left + panelWidth - 12 - x), x, y, color, false);
    }
}
