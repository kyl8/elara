package org.elaramc.client.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.elaramc.client.ElaraChat;
import org.elaramc.client.config.SettingsStore;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

final class ElaraHelp {
    private record Entry(String usage, String example, String description) {}
    private record Group(String name, String title, List<Entry> entries) {}

    private static final List<Group> GROUPS = List.of(
        new Group("setup", "Preparar a região", List.of(
            new Entry("start [x y z]", "start", "Marca o início."),
            new Entry("goal [x y z]", "goal", "Marca a meta."),
            new Entry("region first [x y z]", "region first", "Marca o canto A."),
            new Entry("region second [x y z]", "region second", "Marca o canto B."),
            new Entry("region auto", "region auto", "Usa limites automáticos."),
            new Entry("limits <lado> <estados>", "limits ", "Ajusta os limites."),
            new Entry("scan", "scan", "Analisa as colisões e cria o grafo."))),
        new Group("search", "Executar a busca", List.of(
            new Entry("algorithm <bfs|dfs>", "algorithm ", "Escolhe o algoritmo."),
            new Entry("mode <step|animated|instant>", "mode ", "Escolhe o modo."),
            new Entry("speed <taxa>", "speed ", "Define as expansões por segundo."),
            new Entry("run [bfs|dfs]", "run", "Inicia uma busca."),
            new Entry("pause", "pause", "Pausa a busca."),
            new Entry("resume", "resume", "Retoma a busca."),
            new Entry("step", "step", "Expande um estado."),
            new Entry("reset", "reset", "Limpa a busca e o cronômetro."),
            new Entry("status", "status", "Mostra as métricas e o tempo."),
            new Entry("path [página]", "path", "Lista as coordenadas da rota."))),
        new Group("scenarios", "Cenários e caminhada", List.of(
            new Entry("scenario list", "scenario list", "Lista os cenários."),
            new Entry("scenario save <nome>", "scenario save ", "Salva o cenário e a câmera."),
            new Entry("scenario load <nome> [bfs|dfs]", "scenario load ", "Carrega um cenário."),
            new Entry("replay", "replay", "Recomeça no cenário ativo."),
            new Entry("replay loop <segundos>", "replay loop ", "Repete a busca após o intervalo. Zero desativa."),
            new Entry("walk [start|stop]", "walk", "Segue o caminho encontrado no modo solo."),
            new Entry("exit [bfs|dfs]", "exit", "Busca e percorre a saída do Nível 0."))),
        new Group("display", "Tela, ajustes e ajuda", List.of(
            new Entry("hud", "hud", "Alterna o HUD."),
            new Entry("overlay", "overlay", "Alterna as marcas 3D."),
            new Entry("inspector", "inspector", "Abre o inspetor."),
            new Entry("reload", "reload", "Relê os arquivos de ajustes e cenários."),
            new Entry("help [setup|search|scenarios|display]", "help", "Mostra este guia."))));

    private ElaraHelp() {}

    static LiteralArgumentBuilder<FabricClientCommandSource> command(SettingsStore settings) {
        var help = literal("help").executes(context -> display(context.getSource(), settings, GROUPS));
        for (Group group : GROUPS) {
            help.then(literal(group.name).executes(context -> display(context.getSource(), settings, List.of(group))));
        }
        return help;
    }

    private static int display(FabricClientCommandSource source, SettingsStore settings, List<Group> groups) {
        var colors = settings.palette();
        if (groups.size() > 1) {
            source.sendFeedback(ElaraChat.info(colors, "Guia de comandos"));
            source.sendFeedback(ElaraChat.help(colors, "", "", "Mostra sua posição atual."));
        }
        if (groups.size() > 1) {
            source.sendFeedback(ElaraChat.info(colors, "Atalhos por tema | Clique para preencher o chat."));
            for (Group group : groups) {
                source.sendFeedback(ElaraChat.help(colors, "help " + group.name, "help " + group.name, group.title));
            }
        }
        for (Group group : groups) {
            source.sendFeedback(ElaraChat.info(colors, group.title));
            for (Entry entry : group.entries) {
                source.sendFeedback(ElaraChat.help(colors, entry.usage, entry.example, entry.description));
            }
        }
        return Command.SINGLE_SUCCESS;
    }
}
