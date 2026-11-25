package org.elaramc.client.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import org.elaramc.client.ElaraChat;
import org.elaramc.client.config.SettingsStore;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

final class ElaraHelp {
    private record Entry(String usage, String example, String description) {}
    private record Group(String name, String title, List<Entry> entries) {}

    private static final List<Group> GROUPS = List.of(
        new Group("setup", "Preparar a região", List.of(
            new Entry("origin [look|x y z]", "origin", "Marca o início no pé ou no piso mirado."),
            new Entry("finish [look|x y z]", "finish", "Marca a chegada."),
            new Entry("swap", "swap", "Inverte os pontos."),
            new Entry("clear", "clear", "Limpa os pontos e a região."),
            new Entry("region first [x y z]", "region first", "Marca o canto A."),
            new Entry("region second [x y z]", "region second", "Marca o canto B."),
            new Entry("region auto", "region auto", "Expande a região aos poucos para procurar desvios."),
            new Entry("limits <lado> <estados>", "limits ", "Ajusta o lado X/Z e o total de estados XYZ separadamente."),
            new Entry("scan", "scan", "Analisa as colisões em lotes. Use status para acompanhar."))),
        new Group("search", "Executar a busca", List.of(
            new Entry("start", "start", "Busca a rota e caminha da sua posição no modo solo."),
            new Entry("start walk", "start walk", "Atalho para buscar e caminhar no modo solo."),
            new Entry("algorithm <bfs|dfs>", "algorithm ", "BFS acha a menor rota. DFS explora em profundidade."),
            new Entry("mode <step|animated|instant>", "mode ", "Escolhe o modo."),
            new Entry("speed <taxa>", "speed ", "Define as expansões por segundo."),
            new Entry("run [bfs|dfs]", "run", "Busca no modo escolhido."),
            new Entry("pause", "pause", "Pausa a busca."),
            new Entry("resume", "resume", "Retoma a busca."),
            new Entry("step", "step", "Expande um estado."),
            new Entry("reset", "reset", "Limpa a busca e o cronômetro."),
            new Entry("status", "status", "Mostra o progresso da varredura ou as métricas da busca."),
            new Entry("path [página]", "path", "Lista as coordenadas da rota."))),
        new Group("scenarios", "Cenários e caminhada", List.of(
            new Entry("scenario list", "scenario list", "Lista os cenários."),
            new Entry("scenario save <nome>", "scenario save ", "Salva o cenário e a câmera."),
            new Entry("scenario load <nome> [bfs|dfs]", "scenario load ", "Carrega um cenário."),
            new Entry("replay", "replay", "Recomeça no cenário ativo."),
            new Entry("replay loop <segundos>", "replay loop ", "Repete a busca após o intervalo. Zero desativa."),
            new Entry("walk [start|stop]", "walk", "Segue a rota da sua posição ou cancela a caminhada."),
            new Entry("exit [bfs|dfs]", "exit", "Vai até a saída do mapa ou a chegada marcada."))),
        new Group("display", "Tela, ajustes e ajuda", List.of(
            new Entry("hud", "hud", "Alterna o HUD."),
            new Entry("overlay", "overlay", "Alterna as marcas 3D."),
            new Entry("inspector", "inspector", "Abre o inspetor."),
            new Entry("reload", "reload", "Relê os arquivos de ajustes e cenários."),
            new Entry("help [setup|search|scenarios|display]", "help", "Mostra este guia."))));

    private ElaraHelp() {}

    static LiteralArgumentBuilder<FabricClientCommandSource> command(SettingsStore settings) {
        var help = literal("help").executes(context -> overview(context.getSource(), settings));
        for (Group group : GROUPS) {
            help.then(literal(group.name).executes(context -> display(context.getSource(), settings, group)));
        }
        return help;
    }

    static int overview(FabricClientCommandSource source, SettingsStore settings) {
        var colors = settings.palette();
        source.sendFeedback(Text.empty().append(ElaraChat.info(colors, "Sua primeira rota"))
            .append("\n").append(ElaraChat.help(colors, "finish", "finish", "Marque a chegada. Use finish look para mirar."))
            .append("\n").append(ElaraChat.help(colors, "start", "start", "Siga a rota da posição atual no modo solo."))
            .append("\n").append(ElaraChat.help(colors, "origin", "origin", "Marque um início fixo para buscar pelo F6."))
            .append("\n\n").append(category(settings, "Seleção", "setup")).append("    ")
            .append(category(settings, "Busca", "search")).append("    ")
            .append(category(settings, "Cenários", "scenarios")).append("    ")
            .append(category(settings, "Tela", "display")));
        return Command.SINGLE_SUCCESS;
    }

    private static int display(FabricClientCommandSource source, SettingsStore settings, Group group) {
        var colors = settings.palette();
        var text = Text.empty().append(ElaraChat.info(colors, group.title));
        for (Entry entry : group.entries) {
            text.append("\n").append(ElaraChat.help(colors, entry.usage, entry.example, entry.description));
        }
        source.sendFeedback(text.append("\n\n").append(category(settings, "Voltar ao início", "")));
        return Command.SINGLE_SUCCESS;
    }

    private static Text category(SettingsStore settings, String label, String group) {
        String command = group.isEmpty() ? "help" : "help " + group;
        return ElaraChat.link(settings.palette(), label, command, ClickEvent.Action.RUN_COMMAND);
    }
}
