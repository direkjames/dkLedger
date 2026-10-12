package dev.direk.dkledger.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.direk.dkledger.DkLedgerPlugin;
import dev.direk.dkledger.audit.AuditResult;
import dev.direk.dkledger.audit.Checks;
import dev.direk.dkledger.audit.Finding;
import dev.direk.dkledger.audit.Money;
import dev.direk.dkledger.audit.Severity;
import dev.direk.dkledger.audit.model.Faucet;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /ledger: help, scan, findings, budget, report, reload. Admins only.
 */
public final class LedgerCommand {

    public static final String PERMISSION = "dkledger.admin";
    private static final int PAGE_SIZE = 8;
    private static final List<String> FILTERS = filters();

    private final DkLedgerPlugin plugin;

    public LedgerCommand(DkLedgerPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("ledger")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(ctx -> help(sender(ctx)))
                .then(Commands.literal("help").executes(ctx -> help(sender(ctx))))
                .then(Commands.literal("scan").executes(ctx -> scan(sender(ctx))))
                .then(Commands.literal("findings")
                        .executes(ctx -> findings(sender(ctx), "all", 1))
                        .then(Commands.argument("filter", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemainingLowerCase();
                                    FILTERS.stream().filter(f -> f.startsWith(typed)).forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> findings(sender(ctx), StringArgumentType.getString(ctx, "filter"), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> findings(sender(ctx), StringArgumentType.getString(ctx, "filter"),
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("budget").executes(ctx -> budget(sender(ctx))))
                .then(Commands.literal("report").executes(ctx -> report(sender(ctx))))
                .then(Commands.literal("reload").executes(ctx -> reload(sender(ctx))))
                .build();
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    // ---------------------------------------------------------------- subcommands

    private int help(CommandSender sender) {
        sender.sendMessage(Text.header("dkLedger " + plugin.getPluginMeta().getVersion()));
        line(sender, "/ledger scan", "Audit every economy plugin's config now");
        line(sender, "/ledger findings [critical|warning|info|A1-A12] [page]", "List what the last scan found");
        line(sender, "/ledger budget", "How much money rewards pay per day");
        line(sender, "/ledger report", "Where the full report is saved");
        line(sender, "/ledger reload", "Reload config.yml");
        return Command.SINGLE_SUCCESS;
    }

    private static void line(CommandSender sender, String command, String what) {
        String suggestion = command.contains(" [") ? command.substring(0, command.indexOf(" [")) + " " : command;
        sender.sendMessage(Component.text(command, NamedTextColor.GOLD)
                .clickEvent(ClickEvent.suggestCommand(suggestion))
                .append(Component.text(" - " + what, NamedTextColor.GRAY)));
    }

    private int scan(CommandSender sender) {
        boolean started = plugin.scans().start(result -> {
            if (result == null) {
                sender.sendMessage(Text.prefix().append(Component.text("The scan failed. See the console.", NamedTextColor.RED)));
                return;
            }
            sendSummary(sender, result);
        });
        if (started) {
            sender.sendMessage(Text.prefix().append(Component.text("Scanning every economy plugin...", NamedTextColor.GRAY)));
        } else {
            sender.sendMessage(Text.prefix().append(Component.text("A scan is already running.", NamedTextColor.YELLOW)));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** The short summary after a scan. Also sent to admins online after the startup scan. */
    public void sendSummary(CommandSender sender, AuditResult r) {
        sender.sendMessage(Text.prefix()
                .append(Component.text("Scan done in " + r.millis() + " ms: ", NamedTextColor.GRAY))
                .append(Component.text(r.count(Severity.CRITICAL) + " critical", Text.color(Severity.CRITICAL)))
                .append(Component.text(", ", NamedTextColor.GRAY))
                .append(Component.text(r.count(Severity.WARNING) + " warnings", Text.color(Severity.WARNING)))
                .append(Component.text(", ", NamedTextColor.GRAY))
                .append(Component.text(r.count(Severity.INFO) + " info", Text.color(Severity.INFO)))
                .append(Component.text(".", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  Rewards pay about ", NamedTextColor.GRAY)
                .append(Component.text(Money.format(r.casualPerDay()), NamedTextColor.WHITE))
                .append(Component.text("/day casual, ", NamedTextColor.GRAY))
                .append(Component.text(Money.format(r.grinderPerDay()), NamedTextColor.WHITE))
                .append(Component.text("/day grinder.", NamedTextColor.GRAY)));
        Component buttons = Component.text("  ");
        if (r.count(Severity.CRITICAL) > 0) buttons = buttons.append(Text.button("Critical", "/ledger findings critical", Text.color(Severity.CRITICAL))).append(Component.space());
        if (r.count(Severity.WARNING) > 0) buttons = buttons.append(Text.button("Warnings", "/ledger findings warning", Text.color(Severity.WARNING))).append(Component.space());
        buttons = buttons.append(Text.button("All", "/ledger findings all", NamedTextColor.AQUA)).append(Component.space())
                .append(Text.button("Budget", "/ledger budget", NamedTextColor.AQUA));
        sender.sendMessage(buttons);
    }

    private int findings(CommandSender sender, String filterText, int page) {
        AuditResult r = plugin.scans().last();
        if (r == null) return noScanYet(sender);
        String filter = filterText.toLowerCase(Locale.ROOT);
        if (!FILTERS.contains(filter)) {
            sender.sendMessage(Text.prefix().append(Component.text("Unknown filter. Use all, critical, warning, info or A1 to A12.", NamedTextColor.RED)));
            return 0;
        }
        List<Finding> list = new ArrayList<>();
        for (Finding f : r.findings()) {
            boolean keep = switch (filter) {
                case "all" -> true;
                case "critical" -> f.severity() == Severity.CRITICAL;
                case "warning" -> f.severity() == Severity.WARNING;
                case "info" -> f.severity() == Severity.INFO;
                default -> f.check().equalsIgnoreCase(filter);
            };
            if (keep) list.add(f);
        }
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.min(page, pages);
        String label = filter.startsWith("a") ? filter.toUpperCase(Locale.ROOT) + " " + Checks.name(filter.toUpperCase(Locale.ROOT)) : filter;
        sender.sendMessage(Text.header("Findings: " + label + " (" + list.size() + ")"));
        if (list.isEmpty()) {
            sender.sendMessage(Component.text("  Nothing here.", NamedTextColor.GREEN));
            return Command.SINGLE_SUCCESS;
        }
        boolean player = sender instanceof Player;
        for (Finding f : list.subList((p - 1) * PAGE_SIZE, Math.min(list.size(), p * PAGE_SIZE))) {
            Component title = Component.text("■ ", Text.color(f.severity()))
                    .append(Component.text(f.check() + " ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(f.title(), NamedTextColor.WHITE));
            if (player) {
                sender.sendMessage(title.hoverEvent(HoverEvent.showText(details(f))));
            } else {
                sender.sendMessage(title);
                if (!f.detail().isEmpty()) sender.sendMessage(Component.text("    " + f.detail(), NamedTextColor.GRAY));
                if (!f.fix().isEmpty()) sender.sendMessage(Component.text("    Fix: " + f.fix(), NamedTextColor.GREEN));
            }
        }
        Component nav = Component.text("  ");
        if (p > 1) nav = nav.append(Text.button("« Prev", "/ledger findings " + filter + " " + (p - 1), NamedTextColor.AQUA)).append(Component.space());
        nav = nav.append(Component.text("Page " + p + "/" + pages, NamedTextColor.GRAY));
        if (p < pages) nav = nav.append(Component.space()).append(Text.button("Next »", "/ledger findings " + filter + " " + (p + 1), NamedTextColor.AQUA));
        if (player) nav = nav.append(Component.text("   Hover a line for details.", NamedTextColor.DARK_GRAY));
        sender.sendMessage(nav);
        return Command.SINGLE_SUCCESS;
    }

    private static Component details(Finding f) {
        Component c = Component.text(Checks.name(f.check()), NamedTextColor.GOLD);
        if (!f.detail().isEmpty()) c = c.appendNewline().append(Component.text(Text.wrap(f.detail(), 50), NamedTextColor.GRAY));
        if (!f.fix().isEmpty()) c = c.appendNewline().appendNewline().append(Component.text("Fix: " + Text.wrap(f.fix(), 50), NamedTextColor.GREEN));
        if (!f.where().isEmpty()) c = c.appendNewline().appendNewline().append(Component.text(Text.wrap(f.where(), 50), NamedTextColor.DARK_GRAY));
        return c;
    }

    private int budget(CommandSender sender) {
        AuditResult r = plugin.scans().last();
        if (r == null) return noScanYet(sender);
        sender.sendMessage(Text.header("Daily income from rewards"));
        for (Faucet f : r.faucets()) {
            Component line = Component.text("  " + f.name(), NamedTextColor.WHITE)
                    .append(Component.text(" (" + f.plugin() + "): ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(Money.format(f.casual()), NamedTextColor.GREEN))
                    .append(Component.text(" casual · ", NamedTextColor.GRAY))
                    .append(Component.text(Money.format(f.grinder()), NamedTextColor.GOLD))
                    .append(Component.text(" grinder", NamedTextColor.GRAY));
            sender.sendMessage(line.hoverEvent(HoverEvent.showText(Component.text(f.schedule() + "\n" + Text.wrap(f.note(), 50), NamedTextColor.GRAY))));
        }
        sender.sendMessage(Component.text("  Total: ", NamedTextColor.WHITE)
                .append(Component.text(Money.format(r.casualPerDay()), NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .append(Component.text(" casual · ", NamedTextColor.GRAY))
                .append(Component.text(Money.format(r.grinderPerDay()), NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .append(Component.text(" grinder per day", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  Rewards only. Profiles are set in config.yml (profiles).", NamedTextColor.DARK_GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private int report(CommandSender sender) {
        if (plugin.scans().lastReport() == null) return noScanYet(sender);
        String path = plugin.getDataFolder().getName() + "/reports/" + plugin.scans().lastReport().getFileName();
        sender.sendMessage(Text.prefix().append(Component.text("Full report: ", NamedTextColor.GRAY))
                .append(Component.text("plugins/" + path, NamedTextColor.WHITE).clickEvent(ClickEvent.copyToClipboard("plugins/" + path))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to copy"))))
                .append(Component.text(" (also latest.md). Open it in any Markdown viewer.", NamedTextColor.GRAY)));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandSender sender) {
        plugin.reloadLedgerConfig();
        sender.sendMessage(Text.prefix().append(Component.text("config.yml reloaded. Run /ledger scan to use it.", NamedTextColor.GREEN)));
        return Command.SINGLE_SUCCESS;
    }

    private static int noScanYet(CommandSender sender) {
        sender.sendMessage(Text.prefix().append(Component.text("No scan yet. Run ", NamedTextColor.GRAY))
                .append(Text.button("/ledger scan", "/ledger scan", NamedTextColor.AQUA)));
        return Command.SINGLE_SUCCESS;
    }

    private static List<String> filters() {
        List<String> list = new ArrayList<>(List.of("all", "critical", "warning", "info"));
        for (String id : Checks.ids()) list.add(id.toLowerCase(Locale.ROOT));
        return List.copyOf(list);
    }
}
