package com.direk.dkledger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;

import java.util.List;

/**
 * The dkLedger startup banner in the console (same style as dkCore's and dkCommands', in gold to orange).
 * The logo is plain text and every value goes in as an unparsed placeholder, so nothing can break the formatting.
 * Turn it off with "startup-banner: false" in config.yml.
 * <p>
 * Author: direk james
 */
final class Banner {

    private static final String[] LOGO = {
            "     _  _     _              _",
            "  __| || | __| |    ___   __| |  __ _   ___  _ __",
            " / _` || |/ /| |   / _ \\ / _` | / _` | / _ \\| '__|",
            "| (_| ||   < | |__|  __/| (_| || (_| ||  __/| |",
            " \\__,_||_|\\_\\|_____\\___| \\__,_| \\__, | \\___||_|",
            "                                |___/"
    };

    private static final TextColor GOLD = TextColor.color(0xf5c542);
    private static final TextColor ORANGE = TextColor.color(0xe8833a);
    private static final String LINE = "<dark_gray>" + "-".repeat(74);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Banner() {
    }

    /** One row of the status block: label, MiniMessage value (with &lt;v&gt; for the value). */
    record Row(String label, String value, String v) {
    }

    static void print(DkLedger plugin, List<Row> rows, List<String> warnings, long ms) {
        blank();
        line(LINE);
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (int i = 0; i < LOGO.length; i++) {
            float progress = (float) i / (LOGO.length - 1);
            console.sendMessage(Component.text(LOGO[i], TextColor.lerp(progress, GOLD, ORANGE)));
        }
        blank();
        line("  <white><bold>dkLedger</bold></white> <gray>v<v></gray>  <dark_gray>|</dark_gray>  <gray>by</gray> <#f5c542>direk james",
                Placeholder.unparsed("v", plugin.getPluginMeta().getVersion()));
        line("  <dark_gray><italic>The dk suite's financial analyst");
        line(LINE);
        for (Row r : rows) {
            line("  <#f5c542>" + String.format("%-10s", r.label()) + "</#f5c542><gray>" + r.value(), Placeholder.unparsed("v", r.v()));
        }
        if (warnings.isEmpty()) line("  <#f5c542>Warnings  </#f5c542><green>none");
        else line("  <#f5c542>Warnings  </#f5c542><yellow><v> - see above", Placeholder.unparsed("v", String.valueOf(warnings.size())));
        line("  <#f5c542>Status    </#f5c542><green>Enabled</green> <dark_gray>in <v> ms", Placeholder.unparsed("v", String.valueOf(ms)));
        line(LINE);
        blank();
    }

    private static void line(String miniMessage, TagResolver... values) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(miniMessage, TagResolver.resolver(values)));
    }

    private static void blank() {
        Bukkit.getConsoleSender().sendMessage(Component.text(" "));
    }
}
