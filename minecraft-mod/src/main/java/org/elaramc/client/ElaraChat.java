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
        return Text.empty().append(link(colors, "/elara " + command, example, ClickEvent.Action.SUGGEST_COMMAND))
            .append(colored("  " + description, colors.pearl()));
    }

    public static MutableText link(Palette colors, String label, String command, ClickEvent.Action action) {
        return colored(label, colors.iceBlue())
            .styled(style -> style.withClickEvent(new ClickEvent(action, "/elara " + command)).withUnderline(true));
    }

    private static Text message(Palette colors, String message, boolean warning) {
        var result = colored("elara.", colors.lavender()).styled(style -> style.withItalic(true));
        var body = Text.empty().append(result);
        String[] parts = message.split(" \\| ");
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            int color = warning ? colors.champagne() : metricColor(colors, part);
            body.append(Text.literal(index == 0 ? "  " : "\n")).append(colored(part, color));
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
