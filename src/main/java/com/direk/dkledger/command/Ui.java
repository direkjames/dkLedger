package com.direk.dkledger.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

/** Clickable and hoverable chat parts, built in code (MiniMessage can't put placeholders inside click or hover). */
public final class Ui {

    private Ui() {
    }

    public static Component button(String label, String command, String hover, TextColor color) {
        return Component.text("[" + label + "]", color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(wrap(hover, 50), NamedTextColor.GRAY)));
    }

    /** A link that opens in the browser. */
    public static Component link(String label, String url, String hover) {
        return Component.text("[" + label + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(wrap(hover, 50), NamedTextColor.GRAY)));
    }

    /** A player name that opens their history. */
    public static Component player(String name) {
        return Component.text(name, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.runCommand("/ledger player " + name))
                .hoverEvent(HoverEvent.showText(Component.text("Click for " + name + "'s money history", NamedTextColor.GRAY)));
    }

    /** Text that shows more on hover. */
    public static Component hover(String text, String details, TextColor color) {
        return Component.text(text, color).hoverEvent(HoverEvent.showText(Component.text(wrap(details, 50), NamedTextColor.GRAY)));
    }

    /** A path that's copied when clicked. */
    public static Component copy(String text) {
        return Component.text(text, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.copyToClipboard(text))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy", NamedTextColor.GRAY)));
    }

    /** Breaks long text into lines of about {@code width} characters. */
    public static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder();
        int length = 0;
        for (String word : text.split(" ")) {
            if (length > 0 && length + word.length() + 1 > width) {
                out.append('\n');
                length = 0;
            } else if (length > 0) {
                out.append(' ');
                length++;
            }
            out.append(word);
            length += word.length();
        }
        return out.toString();
    }

    /** {@link #wrap} as separate lines, for item lore. */
    public static List<String> lines(String text, int width) {
        return new ArrayList<>(List.of(wrap(text, width).split("\n")));
    }
}
