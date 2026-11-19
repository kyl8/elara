package org.elaramc.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.elaramc.client.config.ElaraSettings.Palette;
import org.elaramc.client.config.SettingsStore;

final class ElaraButton extends ButtonWidget {
    private final SettingsStore settings;
    private final boolean selected;

    ElaraButton(int x, int y, int width, int height, String message, PressAction action,
                SettingsStore settings, boolean selected) {
        super(x, y, width, height, Text.literal(message), action, DEFAULT_NARRATION_SUPPLIER);
        this.settings = settings;
        this.selected = selected;
    }

    @Override
    public void renderButton(DrawContext draw, int mouseX, int mouseY, float delta) {
        var colors = settings.palette();
        int x = getX(), y = getY();
        int accent = selected ? colors.lavender() : colors.pearl();
        draw.fill(x, y, x + width, y + height, Palette.alpha(accent, isHovered() || isFocused() ? 53 : 24));
        draw.fill(x, y + height - 1, x + width, y + height,
            Palette.alpha(accent, isFocused() || selected ? 216 : 71));
        var font = MinecraftClient.getInstance().textRenderer;
        String label = font.trimToWidth(getMessage().getString(), width - 6);
        draw.drawText(font, label, x + (width - font.getWidth(label)) / 2, y + (height - 8) / 2,
            active ? accent : Palette.alpha(accent, 130), false);
    }
}
