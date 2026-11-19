package org.elaramc.client;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.elaramc.client.config.ElaraSettings.Palette;

public final class ElaraChat {
    private ElaraChat() {}

    public static Text info(Palette colors, String message) {
        return message(colors, message, false);
    }

    public static Text warning(Palette colors, String message) {
        return message(colors, message, true);
    }

    public static Text help(Palette colors, String command, String example, String description) {
        String suggestion = example.isEmpty() ? "/elara" : "/elara " + example;
        String usage = command.isEmpty() ? "/elara" : "/elara " + command;
        return colored("  " + usage, colors.iceBlue())
            .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggestion)))
            .append(colored("  ·  ", colors.lavender()))
            .append(colored(description, colors.pearl()));
    }

    private static Text message(Palette colors, String message, boolean warning) {
        var result = colored("elara.", colors.lavender()).styled(style -> style.withItalic(true));
        var body = Text.empty().append(result);
        for (String part : message.split(" \\| ")) {
            int color = warning ? colors.champagne() : metricColor(colors, part);
            body.append(colored("  ·  ", colors.lavender())).append(colored(part, color));
        }
        return body;
    }

    private static int metricColor(Palette colors, String part) {
        String label = part.split(" ", 2)[0];
        return switch (label) {
            case "Meta", "Nível" -> colors.sage();
            case "Caminho", "Caminhando" -> colors.champagne();
            case "Tempo", "Expandidos" -> colors.iceBlue();
            case "Fronteira", "Descobertos" -> colors.lavender();
            case "BFS", "DFS" -> switch (part.substring(label.length()).trim()) {
                case "ENCONTRADO" -> colors.sage();
                case "SEM CAMINHO", "PAUSADO" -> colors.champagne();
                case "BUSCANDO" -> colors.iceBlue();
                default -> colors.pearl();
            };
            default -> colors.pearl();
        };
    }

    private static MutableText colored(String value, int color) {
        return Text.literal(value).styled(style -> style.withColor(color & 0xFFFFFF));
    }
}
