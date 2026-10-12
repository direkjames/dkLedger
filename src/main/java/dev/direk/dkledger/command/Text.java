package dev.direk.dkledger.command;

import dev.direk.dkledger.audit.Severity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Shared chat formatting. */
final class Text {

    private Text() {
    }

    static Component prefix() {
        return Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("dkLedger", NamedTextColor.GOLD))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY));
    }

    static Component header(String title) {
        return Component.text("— ", NamedTextColor.DARK_GRAY)
                .append(Component.text(title, NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .append(Component.text(" —", NamedTextColor.DARK_GRAY));
    }

    static Component button(String label, String command, TextColor color) {
        return Component.text("[" + label + "]", color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    static TextColor color(Severity severity) {
        return switch (severity) {
            case CRITICAL -> NamedTextColor.RED;
            case WARNING -> NamedTextColor.YELLOW;
            case INFO -> NamedTextColor.AQUA;
        };
    }

    /** Breaks long text into lines for hover boxes. */
    static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder();
        int lineLength = 0;
        for (String word : text.split(" ")) {
            if (lineLength > 0 && lineLength + word.length() + 1 > width) {
                out.append('\n');
                lineLength = 0;
            } else if (lineLength > 0) {
                out.append(' ');
                lineLength++;
            }
            out.append(word);
            lineLength += word.length();
        }
        return out.toString();
    }
}
