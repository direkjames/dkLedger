package com.direk.dkledger.command;

import com.direk.dkcore.command.DkCommand;
import com.direk.dkcore.command.SubCommand;
import com.direk.dkcore.message.Messages;
import com.direk.dkledger.DkLedger;
import com.direk.dkledger.admin.LedgerActions;
import com.direk.dkledger.api.AlertView;
import com.direk.dkledger.api.LedgerApi;
import com.direk.dkledger.audit.AuditResult;
import com.direk.dkledger.audit.Checks;
import com.direk.dkledger.audit.Finding;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.audit.model.Faucet;
import com.direk.dkledger.ledger.LedgerStore;
import com.direk.dkledger.whatif.WhatIf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /ledger (alias /dkledger): the menu, the ledger, alerts, admin actions, the config audit and the simulator.
 * Author: direk james
 */
public final class LedgerCommand extends DkCommand {

    public static final String ADMIN = "dkledger.admin";
    public static final String OWNER = "dkledger.owner";
    private static final int PAGE = 8;
    private static final List<String> FILTERS = filters();
    private static final List<String> PERIODS = List.of("day", "week", "month");

    private final DkLedger plugin;

    public LedgerCommand(DkLedger plugin) {
        super("ledger", ADMIN, plugin.messages());
        this.plugin = plugin;
        sub(new Simple("help", s -> help(s)));
        sub(new Simple("menu", s -> {
            if (s instanceof Player p) plugin.menus().openMain(p);
            else messages.send(s, "player-only");
        }, "gui"));
        sub(new Simple("supply", this::supply));
        sub(new Period("sources", this::sources));
        sub(new Period("top", this::top));
        sub(new Period("items", this::items));
        sub(new Period("reasons", this::reasons));
        sub(new PlayerSub());
        sub(new Alerts());
        sub(new Resolve());
        sub(new Freeze());
        sub(new Unfreeze());
        sub(new Simple("frozen", this::frozen));
        sub(new Note());
        sub(new Reverse());
        sub(new Simple("scan", this::scan));
        sub(new Findings());
        sub(new Simple("budget", this::budget));
        sub(new Simple("report", this::report));
        sub(new WhatIfSub());
        sub(new Simple("weekly", this::weekly));
        sub(new Simple("reload", this::reload));
        sub(new WebSub());
    }

    @Override
    protected void root(CommandSender sender, String[] args) {
        if (args.length == 0 && sender instanceof Player p) plugin.menus().openMain(p);
        else help(sender);
    }

    // ------------------------------------------------------------------ sub command helpers

    private final class Simple extends SubCommand {
        private final java.util.function.Consumer<CommandSender> run;

        Simple(String name, java.util.function.Consumer<CommandSender> run, String... aliases) {
            super(name, ADMIN, false, aliases);
            this.run = run;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            run.accept(sender);
        }
    }

    private final class Period extends SubCommand {
        private final java.util.function.BiConsumer<CommandSender, Integer> run;

        Period(String name, java.util.function.BiConsumer<CommandSender, Integer> run) {
            super(name, ADMIN, false);
            this.run = run;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            run.accept(sender, days(args.length > 0 ? args[0] : "week"));
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            return args.length == 1 ? PERIODS : List.of();
        }
    }

    private static int days(String period) {
        return switch (period.toLowerCase(Locale.ROOT)) {
            case "day", "today" -> 1;
            case "month" -> 30;
            default -> 7;
        };
    }

    private static String periodName(int days) {
        return days == 1 ? "today" : days == 30 ? "30 days" : "7 days";
    }

    // ------------------------------------------------------------------ help, supply, sources, top

    private void help(CommandSender sender) {
        messages.send(sender, "help", Messages.text("version", plugin.getPluginMeta().getVersion()));
    }

    private void supply(CommandSender sender) {
        plugin.actions().supply().thenAccept(s -> {
            if (s.time() == 0) {
                messages.send(sender, "supply.none");
                return;
            }
            long minutes = Math.max(0, (System.currentTimeMillis() - s.time()) / 60_000);
            messages.send(sender, "supply.lines", Messages.text("total", Money.format(s.total())),
                    Messages.text("wallet", Money.format(s.wallet())), Messages.text("bank", Money.format(s.bank())),
                    Messages.component("change24h", change(s.change24h())), Messages.component("change7d", change(s.change7d())),
                    Messages.text("per-active", Money.format(s.perActive())), Messages.text("top-share", Money.percent(s.topShare())),
                    Messages.text("age", minutes < 60 ? minutes + " min" : (minutes / 60) + " h"));
        });
    }

    private static Component change(double c) {
        if (Double.isNaN(c)) return Component.text("not enough data yet", NamedTextColor.DARK_GRAY);
        return Component.text((c >= 0 ? "+" : "-") + Money.percent(Math.abs(c)), c > 0.001 ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
    }

    private void sources(CommandSender sender, int days) {
        plugin.actions().sources(days).thenAccept(list -> {
            messages.send(sender, "sources.header", Messages.text("period", periodName(days)));
            if (list.isEmpty()) messages.send(sender, "nothing");
            for (LedgerApi.Source s : list.subList(0, Math.min(15, list.size()))) {
                messages.send(sender, "sources.line",
                        Messages.component("name", Component.text(s.name(), kindColor(s.kind()))), Messages.text("kind", s.kind()), Messages.text("in", Money.format(s.gained())),
                        Messages.text("out", Money.format(s.lost())),
                        Messages.component("net", Component.text(Money.format(s.net()), s.net() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED)));
            }
            messages.send(sender, "sources.footer");
        });
    }

    static TextColor kindColor(String kind) {
        return switch (kind) {
            case "faucet" -> NamedTextColor.GREEN;
            case "sink" -> NamedTextColor.RED;
            case "staff" -> NamedTextColor.LIGHT_PURPLE;
            default -> NamedTextColor.WHITE;
        };
    }

    private void top(CommandSender sender, int days) {
        plugin.actions().top(days).thenAccept(list -> {
            messages.send(sender, "top.header", Messages.text("period", periodName(days)));
            if (list.isEmpty()) messages.send(sender, "nothing");
            int rank = 1;
            for (LedgerStore.PlayerTotal p : list) {
                messages.send(sender, "top.line", Messages.text("rank", rank++), Messages.component("player", Ui.player(p.name())),
                        Messages.text("earned", Money.format(p.earned())), Messages.text("spent", Money.format(p.spent())));
            }
        });
    }

    private void items(CommandSender sender, int days) {
        plugin.actions().items(days).thenAccept(list -> {
            messages.send(sender, "items.header", Messages.text("period", periodName(days)));
            if (list.isEmpty()) messages.send(sender, plugin.ledgerConfig().tracking().items() ? "nothing" : "items.off");
            for (LedgerApi.Item i : list.subList(0, Math.min(15, list.size()))) {
                messages.send(sender, "items.line", Messages.text("item", i.item()),
                        Messages.text("sold", String.format(Locale.US, "%,d", i.sold())), Messages.text("sold-money", Money.format(i.soldMoney())),
                        Messages.text("bought", String.format(Locale.US, "%,d", i.bought())), Messages.text("bought-money", Money.format(i.boughtMoney())));
            }
        });
    }

    private void reasons(CommandSender sender, int days) {
        plugin.actions().reasons(days).thenAccept(list -> {
            messages.send(sender, "reasons.header", Messages.text("period", periodName(days)));
            if (list.isEmpty()) messages.send(sender, "reasons.none");
            for (LedgerApi.Reason r : list.subList(0, Math.min(15, list.size()))) {
                double net = r.gained() - r.lost();
                messages.send(sender, "reasons.line", Messages.text("source", r.source()), Messages.text("reason", r.reason()),
                        Messages.component("net", Component.text(Money.format(net), net >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED)));
            }
        });
    }

    // ------------------------------------------------------------------ players

    private final class PlayerSub extends SubCommand {
        PlayerSub() {
            super("player", ADMIN, false, "p", "history");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length < 1) {
                messages.send(sender, "usage", Messages.text("usage", "/ledger player <name>"));
                return;
            }
            showPlayer(sender, args[0]);
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            return args.length == 1 ? onlineNames() : List.of();
        }
    }

    public void showPlayer(CommandSender sender, String name) {
        plugin.actions().player(name).thenAccept(found -> {
            if (found.isEmpty()) {
                messages.send(sender, "unknown-player", Messages.text("player", name));
                return;
            }
            LedgerApi.Player p = found.get();
            plugin.actions().activity(p.name()).thenCombine(plugin.actions().playerItems(p.uuid()),
                    (act, items) -> { showPlayer(sender, p, act, items); return null; });
        });
    }

    private void showPlayer(CommandSender sender, LedgerApi.Player p, java.util.Optional<LedgerApi.Activity> act,
                            List<LedgerStore.ItemTotal> items) {
        messages.send(sender, "player.header", Messages.text("name", p.name()));
        messages.send(sender, "player.earned", Messages.text("today", Money.format(p.earnedToday())),
                Messages.text("week", Money.format(p.earned7d())));
        if (p.frozen()) messages.send(sender, "player.frozen", Messages.text("reason", p.frozenReason() == null ? "" : p.frozenReason()));
        showActivity(sender, p, act, items);
        if (!p.sources7d().isEmpty()) {
            messages.send(sender, "player.sources-title");
            for (LedgerApi.Source s : p.sources7d().subList(0, Math.min(8, p.sources7d().size()))) {
                messages.send(sender, "player.source", Messages.text("name", s.name()), Messages.text("in", Money.format(s.gained())),
                        Messages.text("out", Money.format(s.lost())));
            }
        }
        if (!p.recentEntries().isEmpty()) {
            messages.send(sender, "player.recent-title");
            for (String line : p.recentEntries()) messages.send(sender, "player.entry", Messages.text("text", line));
        }
        if (!p.notes().isEmpty()) {
            messages.send(sender, "player.notes-title");
            for (String line : p.notes()) messages.send(sender, "player.note", Messages.text("text", line));
        }
    }

    /** First join, playtime, money per hour played, progress and items traded. */
    private void showActivity(CommandSender sender, LedgerApi.Player p, java.util.Optional<LedgerApi.Activity> act,
                              List<LedgerStore.ItemTotal> items) {
        act.ifPresent(a -> {
            String first = a.firstJoin() <= 0 ? "unknown"
                    : DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(plugin.ledgerConfig().zone()).format(Instant.ofEpochMilli(a.firstJoin()));
            long daysAgo = a.firstJoin() <= 0 ? -1 : (System.currentTimeMillis() - a.firstJoin()) / 86_400_000L;
            messages.send(sender, "player.activity", Messages.text("first-join", first),
                    Messages.text("days-ago", daysAgo < 0 ? "?" : String.valueOf(daysAgo)),
                    Messages.text("active", hours(a.activeMinutes())), Messages.text("afk", hours(a.afkMinutes())),
                    Messages.text("sessions", a.sessions()), Messages.text("days", a.daysPlayed()));
            if (a.activeMinutes() + a.afkMinutes() >= 60) {
                double perHour = p.earned7d() / ((a.activeMinutes() + a.afkMinutes()) / 60.0);
                messages.send(sender, "player.per-hour", Messages.text("per-hour", Money.format(perHour)));
            }
            if (!a.progress().isEmpty()) {
                messages.send(sender, "player.progress-title");
                for (String line : a.progress()) messages.send(sender, "player.progress", Messages.text("text", line));
            }
        });
        if (!items.isEmpty()) {
            messages.send(sender, "player.items-title");
            for (LedgerStore.ItemTotal i : items) {
                messages.send(sender, "player.item", Messages.text("item", i.item()),
                        Messages.text("sold", String.format(Locale.US, "%,d", i.sold())), Messages.text("sold-money", Money.format(i.soldMoney())),
                        Messages.text("bought", String.format(Locale.US, "%,d", i.bought())), Messages.text("bought-money", Money.format(i.boughtMoney())));
            }
        }
    }

    private static String hours(int minutes) {
        return minutes < 60 ? minutes + " min" : String.format(Locale.US, "%.1f h", minutes / 60.0);
    }

    // ------------------------------------------------------------------ web dashboard

    private final class WebSub extends SubCommand {
        WebSub() {
            super("web", ADMIN, false, "dashboard");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length > 0 && args[0].equalsIgnoreCase("logout")) {
                int n = plugin.web().sessions().logoutAll();
                messages.send(sender, "web.logged-out", Messages.text("count", n));
                return;
            }
            if (!plugin.web().running()) {
                messages.send(sender, "web.off");
                return;
            }
            if (sender instanceof Player p) {
                String link = plugin.web().loginLink(p.getUniqueId(), p.getName());
                messages.send(sender, "web.link", Messages.component("link", Ui.link("Open the dashboard", link,
                        "Signs you in to the dashboard. Works once, for 5 minutes. Don't share it.")));
            } else {
                messages.send(sender, "web.console", Messages.text("link", plugin.web().loginLink(null, "console")));
            }
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            return args.length == 1 ? List.of("logout") : List.of();
        }
    }

    // ------------------------------------------------------------------ alerts

    private final class Alerts extends SubCommand {
        Alerts() {
            super("alerts", ADMIN, false);
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            boolean all = args.length > 0 && args[0].equalsIgnoreCase("all");
            int page = 1;
            for (String a : args) if (a.matches("\\d+")) page = Math.max(1, Integer.parseInt(a));
            List<AlertView> list = all ? plugin.alerts().recent() : plugin.alerts().open();
            int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
            page = Math.min(page, pages);
            messages.send(sender, "alerts.header", Messages.text("filter", all ? "all, 30 days" : "open"),
                    Messages.text("page", page), Messages.text("pages", pages));
            if (list.isEmpty()) messages.send(sender, "nothing");
            DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(plugin.ledgerConfig().zone());
            for (AlertView a : list.subList((page - 1) * PAGE, Math.min(list.size(), page * PAGE))) {
                Component resolved = a.open() ? Component.empty()
                        : messages.get("alerts.resolved-tag", Messages.text("by", a.resolvedBy() == null ? "?" : a.resolvedBy()));
                messages.send(sender, "alerts.line-" + a.severity().toLowerCase(Locale.ROOT), Messages.text("id", a.id()),
                        Messages.text("check", a.check()),
                        Messages.component("title", Ui.hover(a.title(), a.detail() + (a.note() != null ? " Note: " + a.note() : ""), NamedTextColor.WHITE)),
                        Messages.text("time", f.format(Instant.ofEpochMilli(a.time()))), Messages.component("resolved", resolved));
            }
            if (pages > 1) {
                Component nav = Component.text("  ");
                String base = "/ledger alerts " + (all ? "all " : "");
                if (page > 1) nav = nav.append(Ui.button("« Prev", base + (page - 1), "Page " + (page - 1), NamedTextColor.AQUA)).append(Component.space());
                if (page < pages) nav = nav.append(Ui.button("Next »", base + (page + 1), "Page " + (page + 1), NamedTextColor.AQUA));
                sender.sendMessage(nav);
            }
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            return args.length == 1 ? List.of("open", "all") : List.of();
        }
    }

    private final class Resolve extends SubCommand {
        Resolve() {
            super("resolve", ADMIN, false);
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length < 1 || !args[0].matches("\\d+")) {
                messages.send(sender, "usage", Messages.text("usage", "/ledger resolve <alert id> [note]"));
                return;
            }
            long id = Long.parseLong(args[0]);
            String note = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null;
            plugin.actions().resolveAlert(id, sender.getName(), note).thenAccept(done ->
                    messages.send(sender, done ? "alerts.resolved" : "alerts.not-found", Messages.text("id", id)));
        }
    }

    // ------------------------------------------------------------------ freeze, notes, reverse

    private final class Freeze extends SubCommand {
        Freeze() {
            super("freeze", ADMIN, false);
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length < 1) {
                messages.send(sender, "usage", Messages.text("usage", "/ledger freeze <player> [reason]"));
                return;
            }
            String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
            plugin.actions().freeze(args[0], sender.getName(), reason).thenAccept(done ->
                    messages.send(sender, done ? "freeze.done" : "unknown-player", Messages.text("player", args[0])));
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            return args.length == 1 ? onlineNames() : List.of();
        }
    }

    private final class Unfreeze extends SubCommand {
        Unfreeze() {
            super("unfreeze", ADMIN, false);
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length < 1) {
                messages.send(sender, "usage", Messages.text("usage", "/ledger unfreeze <player>"));
                return;
            }
            plugin.actions().unfreeze(args[0]).thenAccept(done ->
                    messages.send(sender, done ? "freeze.undone" : "freeze.not-frozen", Messages.text("player", args[0])));
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            return args.length == 1 ? plugin.freezes().all().stream().map(LedgerStore.Frozen::name).toList() : List.of();
        }
    }

    private void frozen(CommandSender sender) {
        messages.send(sender, "freeze.list-header");
        List<LedgerStore.Frozen> all = plugin.freezes().all();
        if (all.isEmpty()) messages.send(sender, "nothing");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(plugin.ledgerConfig().zone());
        for (LedgerStore.Frozen fr : all) {
            messages.send(sender, "freeze.list-line", Messages.component("player", Ui.player(fr.name())),
                    Messages.text("since", f.format(Instant.ofEpochMilli(fr.since()))), Messages.text("by", fr.by()),
                    Messages.text("reason", fr.reason().isEmpty() ? "-" : fr.reason()));
        }
    }

    private final class Note extends SubCommand {
        Note() {
            super("note", ADMIN, false);
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length < 2) {
                messages.send(sender, "usage", Messages.text("usage", "/ledger note <player> <text>"));
                return;
            }
            String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            plugin.actions().note(args[0], sender.getName(), text).thenAccept(done ->
                    messages.send(sender, done ? "note.added" : "unknown-player", Messages.text("player", args[0])));
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            return args.length == 1 ? onlineNames() : List.of();
        }
    }

    private final class Reverse extends SubCommand {
        Reverse() {
            super("reverse", OWNER, false, "undo");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length < 1 || !args[0].matches("\\d+")) {
                messages.send(sender, "usage", Messages.text("usage", "/ledger reverse <entry id> (ids are in /ledger player)"));
                return;
            }
            long id = Long.parseLong(args[0]);
            if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                messages.send(sender, "reverse.confirm", Messages.text("id", id), Messages.text("text", "you'll see the amount after"),
                        Messages.component("confirm", Ui.button("CONFIRM", "/ledger reverse " + id + " confirm",
                                "Click to undo entry #" + id, NamedTextColor.GREEN)));
                return;
            }
            plugin.actions().reverse(id, sender.getName()).thenAccept(text -> messages.send(sender, "reverse.result", Messages.text("text", text)));
        }
    }

    // ------------------------------------------------------------------ config audit

    private void scan(CommandSender sender) {
        messages.send(sender, "scan.started");
        plugin.scans().scan().whenComplete((r, err) -> {
            if (err != null) messages.send(sender, "scan.failed");
            else if (r == null) messages.send(sender, "scan.busy");
            else sendSummary(sender, r);
        });
    }

    /** The short scan summary with buttons. Also sent to admins after the startup scan. */
    public void sendSummary(CommandSender sender, AuditResult r) {
        for (String line : LedgerActions.summary(r)) messages.send(sender, "line", Messages.text("text", line));
        Component buttons = Component.text("  ");
        if (r.count(Severity.CRITICAL) > 0) buttons = buttons.append(Ui.button("Critical", "/ledger findings critical", "Show critical findings", NamedTextColor.RED)).append(Component.space());
        if (r.count(Severity.WARNING) > 0) buttons = buttons.append(Ui.button("Warnings", "/ledger findings warning", "Show warnings", NamedTextColor.YELLOW)).append(Component.space());
        buttons = buttons.append(Ui.button("All", "/ledger findings all", "Show every finding", NamedTextColor.AQUA)).append(Component.space())
                .append(Ui.button("Budget", "/ledger budget", "Daily reward income", NamedTextColor.AQUA));
        sender.sendMessage(buttons);
    }

    private final class Findings extends SubCommand {
        Findings() {
            super("findings", ADMIN, false);
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            AuditResult r = plugin.scans().last();
            if (r == null) {
                messages.send(sender, "scan.none-yet");
                return;
            }
            String filter = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "all";
            if (!FILTERS.contains(filter)) {
                messages.send(sender, "error", Messages.text("error", "Use all, critical, warning, info or a1 to a12."));
                return;
            }
            int page = args.length > 1 && args[1].matches("\\d+") ? Math.max(1, Integer.parseInt(args[1])) : 1;
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
            int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
            page = Math.min(page, pages);
            String label = filter.startsWith("a") ? filter.toUpperCase(Locale.ROOT) + " " + Checks.name(filter.toUpperCase(Locale.ROOT)) : filter;
            messages.send(sender, "header", Messages.text("title", "Findings: " + label + " (" + list.size() + ")"));
            if (list.isEmpty()) messages.send(sender, "nothing");
            boolean player = sender instanceof Player;
            for (Finding f : list.subList((page - 1) * PAGE, Math.min(list.size(), page * PAGE))) {
                String details = Checks.name(f.check()) + ". " + f.detail() + (f.fix().isEmpty() ? "" : " Fix: " + f.fix())
                        + (f.where().isEmpty() ? "" : " (" + f.where() + ")");
                Component line = Component.text("■ ", color(f.severity())).append(Component.text(f.check() + " ", NamedTextColor.DARK_GRAY))
                        .append(player ? Ui.hover(f.title(), details, NamedTextColor.WHITE) : Component.text(f.title(), NamedTextColor.WHITE));
                sender.sendMessage(line);
                if (!player) sender.sendMessage(Component.text("    " + details, NamedTextColor.GRAY));
            }
            Component nav = Component.text("  ");
            if (page > 1) nav = nav.append(Ui.button("« Prev", "/ledger findings " + filter + " " + (page - 1), "Page " + (page - 1), NamedTextColor.AQUA)).append(Component.space());
            nav = nav.append(Component.text("Page " + page + "/" + pages, NamedTextColor.GRAY));
            if (page < pages) nav = nav.append(Component.space()).append(Ui.button("Next »", "/ledger findings " + filter + " " + (page + 1), "Page " + (page + 1), NamedTextColor.AQUA));
            if (player) nav = nav.append(Component.text("   Hover a line for details.", NamedTextColor.DARK_GRAY));
            sender.sendMessage(nav);
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            return args.length == 1 ? FILTERS : List.of();
        }
    }

    private static TextColor color(Severity s) {
        return switch (s) {
            case CRITICAL -> NamedTextColor.RED;
            case WARNING -> NamedTextColor.YELLOW;
            case INFO -> NamedTextColor.AQUA;
        };
    }

    private void budget(CommandSender sender) {
        AuditResult r = plugin.scans().last();
        if (r == null) {
            messages.send(sender, "scan.none-yet");
            return;
        }
        messages.send(sender, "header", Messages.text("title", "Daily income from rewards"));
        for (Faucet f : r.faucets()) {
            sender.sendMessage(Component.text("  ").append(Ui.hover(f.name(), f.schedule() + ". " + f.note(), NamedTextColor.WHITE))
                    .append(Component.text(" (" + f.plugin() + "): ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(Money.format(f.casual()), NamedTextColor.GREEN))
                    .append(Component.text(" casual · ", NamedTextColor.GRAY))
                    .append(Component.text(Money.format(f.grinder()), NamedTextColor.GOLD))
                    .append(Component.text(" grinder", NamedTextColor.GRAY)));
        }
        messages.send(sender, "line", Messages.text("text", "Total: " + Money.format(r.casualPerDay()) + " casual · "
                + Money.format(r.grinderPerDay()) + " grinder per day (rewards only; profiles are in config.yml)."));
    }

    private void report(CommandSender sender) {
        if (plugin.scans().lastReport() == null) {
            messages.send(sender, "scan.none-yet");
            return;
        }
        String path = "plugins/" + plugin.getDataFolder().getName() + "/reports/" + plugin.scans().lastReport().getFileName();
        messages.send(sender, "scan.report", Messages.component("path", Ui.copy(path)));
    }

    // ------------------------------------------------------------------ simulator, weekly, reload

    private final class WhatIfSub extends SubCommand {
        WhatIfSub() {
            super("whatif", ADMIN, false, "simulate");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length == 0) {
                messages.send(sender, "whatif.usage");
                return;
            }
            WhatIf.Change change;
            try {
                change = WhatIf.parse(args);
            } catch (IllegalArgumentException e) {
                messages.send(sender, "error", Messages.text("error", e.getMessage()));
                return;
            }
            messages.send(sender, "whatif.started");
            plugin.actions().live().thenCompose(live -> plugin.scans().whatIf(change, live)).whenComplete((lines, err) -> {
                if (err != null) {
                    messages.send(sender, "error", Messages.text("error", "The simulation failed. See the console."));
                    return;
                }
                for (String line : lines) {
                    String trimmed = line.trim();
                    String key = line.startsWith("=") ? "whatif.title" : trimmed.startsWith("+") ? "whatif.good"
                            : trimmed.startsWith("-") ? "whatif.bad" : trimmed.startsWith("~") ? "whatif.changed" : "whatif.plain";
                    messages.send(sender, key, Messages.text("text", line.startsWith("=") ? line.substring(1).trim() : line));
                }
            });
        }

        @Override
        public List<String> tab(CommandSender sender, String[] args) {
            if (args.length == 1) return List.of("price", "spawner", "spawnerprice", "bank", "reward");
            if (args.length == 3 && args[0].equalsIgnoreCase("price")) return List.of("buy", "sell");
            if (args.length == 3 && args[0].equalsIgnoreCase("bank")) return List.of("online", "offline", "cap");
            return List.of();
        }
    }

    private void weekly(CommandSender sender) {
        messages.send(sender, "weekly.started");
        plugin.weekly().write().whenComplete((path, err) -> plugin.tasks().sync(() -> {
            if (err != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Couldn't write the weekly report", err);
                messages.send(sender, "weekly.failed");
            } else {
                messages.send(sender, "weekly.done", Messages.component("path",
                        Ui.copy("plugins/" + plugin.getDataFolder().getName() + "/reports/" + path.getFileName())));
            }
        }));
    }

    private void reload(CommandSender sender) {
        List<String> warnings = plugin.reloadEverything();
        for (String w : warnings) messages.send(sender, "reload-warning", Messages.text("warning", w));
        messages.send(sender, "reloaded");
    }

    private static List<String> filters() {
        List<String> list = new ArrayList<>(List.of("all", "critical", "warning", "info"));
        for (String id : Checks.ids()) list.add(id.toLowerCase(Locale.ROOT));
        return List.copyOf(list);
    }
}
