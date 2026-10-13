package com.direk.dkledger.summary;

import com.direk.dkcore.message.Messages;
import com.direk.dkledger.DkLedger;
import com.direk.dkledger.api.AlertView;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.command.Ui;
import com.direk.dkledger.ledger.LedgerStore;
import com.direk.dkledger.ledger.Sources;
import com.direk.dkledger.supply.Snapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * "While you were away": a few lines for staff when they join, about what changed in the economy since they
 * last left. Sent only when something deserves a look (a new alert, a new money source, a freeze, or the money
 * supply moving more than a set amount).
 */
public final class LoginSummary implements Listener {

    public static final String PERMISSION = "dkledger.summary";
    private static final long DAY = 86_400_000L;

    private final DkLedger plugin;
    private final LedgerStore store;
    private volatile boolean enabled = true;
    private volatile int delaySeconds = 5;
    private volatile double minChange = 0.05;
    private volatile int maxDays = 7;

    public LoginSummary(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void configure(boolean enabled, int delaySeconds, double minChange, int maxDays) {
        this.enabled = enabled;
        this.delaySeconds = Math.max(0, delaySeconds);
        this.minChange = Math.max(0, minChange);
        this.maxDays = Math.max(1, maxDays);
    }

    // ------------------------------------------------------------------ joining and leaving

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!enabled || !p.hasPermission(PERMISSION)) return;
        UUID id = p.getUniqueId();
        // A few seconds later, so it isn't lost among other plugins' join messages.
        plugin.tasks().later(() -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null || !online.isOnline()) return;
            store.staff(id).thenAccept(s -> plugin.tasks().sync(() -> {
                long now = System.currentTimeMillis();
                if (!s.enabled() || s.mutedUntil() > now) return;
                build(since(s, now)).thenAccept(summary -> plugin.tasks().sync(() -> {
                    Player still = Bukkit.getPlayer(id);
                    if (still != null && summary.worthSending(minChange)) send(still, summary);
                }));
            }));
        }, 20L * delaySeconds);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPermission(PERMISSION)) return;
        long now = System.currentTimeMillis();
        store.staff(p.getUniqueId()).thenAccept(s -> store.saveStaff(new LedgerStore.Staff(s.uuid(), now, s.mutedUntil(), s.enabled())));
    }

    /** Since they last left, but at most {@code maxDays} back; 24 hours the first time. */
    private long since(LedgerStore.Staff s, long now) {
        long from = s.lastQuit() > 0 ? s.lastQuit() : now - DAY;
        return Math.max(from, now - maxDays * DAY);
    }

    // ------------------------------------------------------------------ /ledger summary

    /** /ledger summary: the summary right now, even if nothing much happened. */
    public void showNow(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            build(System.currentTimeMillis() - DAY).thenAccept(s -> plugin.tasks().sync(() -> sendOrNothing(sender, s)));
            return;
        }
        store.staff(p.getUniqueId()).thenAccept(s -> plugin.tasks().sync(() ->
                build(since(s, System.currentTimeMillis())).thenAccept(summary -> plugin.tasks().sync(() -> sendOrNothing(sender, summary)))));
    }

    private void sendOrNothing(CommandSender sender, Summary s) {
        boolean anything = s.alerts() > 0 || !s.newSources().isEmpty() || !s.newlyFrozen().isEmpty() || s.newPlayers() > 0
                || !Double.isNaN(s.change()) || s.created() > 0 || s.removed() > 0;
        if (anything) send(sender, s);
        else plugin.messages().send(sender, "summary.nothing", Messages.text("away", away(s.since())));
    }

    /** /ledger summary mute: no summary until tomorrow (in the server's time zone). */
    public void mute(Player p) {
        ZoneId zone = plugin.ledgerConfig().zone();
        long tomorrow = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        update(p, s -> new LedgerStore.Staff(s.uuid(), s.lastQuit(), tomorrow, s.enabled()), "summary.muted");
    }

    public void setEnabled(Player p, boolean on) {
        update(p, s -> new LedgerStore.Staff(s.uuid(), s.lastQuit(), on ? 0 : s.mutedUntil(), on), on ? "summary.on" : "summary.off");
    }

    private void update(Player p, java.util.function.UnaryOperator<LedgerStore.Staff> change, String message) {
        store.staff(p.getUniqueId()).thenCompose(s -> store.saveStaff(change.apply(s)))
                .thenRun(() -> plugin.tasks().sync(() -> plugin.messages().send(p, message)));
    }

    // ------------------------------------------------------------------ building and sending

    /** Gathers the summary. Start on the main thread; completes on the database thread. */
    public CompletableFuture<Summary> build(long since) {
        Snapshot now = plugin.supply().latest();
        List<AlertView> alerts = new ArrayList<>();
        for (AlertView a : plugin.alerts().open()) if (a.time() >= since) alerts.add(a);
        List<String> frozen = new ArrayList<>();
        for (LedgerStore.Frozen f : plugin.freezes().all()) if (f.since() >= since) frozen.add(f.name());
        Sources rules = plugin.ledger().sources();

        CompletableFuture<Optional<Snapshot>> then = store.snapshotAtOrBefore(since);
        CompletableFuture<Map<String, double[]>> flow = store.sourcesSince(since);
        CompletableFuture<List<String>> sources = store.newSourcesSince(since);
        CompletableFuture<Integer> players = store.newPlayersSince(since);
        return CompletableFuture.allOf(then, flow, sources, players).thenApply(v -> {
            double[] money = Summary.flow(flow.join(), rules);
            int criticals = 0;
            AlertView worst = null;
            for (AlertView a : alerts) {
                if (a.severity().equals("CRITICAL")) criticals++;
                if (worst == null || rank(a.severity()) > rank(worst.severity())
                        || rank(a.severity()) == rank(worst.severity()) && a.time() > worst.time()) worst = a;
            }
            List<String> newSources = new ArrayList<>();
            for (String s : sources.join()) if (!Sources.isStaff(s) && !s.equals(Sources.UNKNOWN)) newSources.add(s);
            return new Summary(since, now == null ? Double.NaN : now.total(),
                    then.join().map(Snapshot::total).orElse(Double.NaN), money[0], money[1], alerts.size(), criticals,
                    worst == null ? null : worst.title(), newSources, players.join(), frozen);
        });
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }

    private void send(CommandSender to, Summary s) {
        var m = plugin.messages();
        m.send(to, "summary.header", Messages.text("away", away(s.since())));
        if (!Double.isNaN(s.supplyNow())) {
            double c = s.change();
            Component change = Double.isNaN(c) ? Component.empty()
                    : Component.text("(" + (c >= 0 ? "+" : "-") + Money.percent(Math.abs(c)) + ")",
                    Math.abs(c) >= minChange ? NamedTextColor.YELLOW : NamedTextColor.GRAY);
            m.send(to, "summary.money", Messages.text("total", Money.format(s.supplyNow())), Messages.component("change", change),
                    Messages.text("created", compact(s.created())), Messages.text("removed", compact(s.removed())));
        }
        if (s.alerts() > 0) {
            Component criticals = s.criticals() > 0 ? Component.text(" (" + s.criticals() + " critical)", NamedTextColor.RED) : Component.empty();
            m.send(to, "summary.alerts", Messages.text("count", s.alerts()), Messages.component("criticals", criticals),
                    Messages.text("title", s.worstAlert() == null ? "" : s.worstAlert()),
                    Messages.component("view", Ui.button("View", "/ledger alerts", "Open alerts", NamedTextColor.AQUA)));
        }
        List<String> news = new ArrayList<>();
        if (!s.newSources().isEmpty()) news.add("new money source: " + list(s.newSources()));
        if (!s.newlyFrozen().isEmpty()) news.add("frozen: " + list(s.newlyFrozen()));
        if (s.newPlayers() > 0) news.add(s.newPlayers() + " new player" + (s.newPlayers() == 1 ? "" : "s") + " joined");
        if (!news.isEmpty()) m.send(to, "summary.news", Messages.text("text", String.join(" · ", news)));
        Component buttons = Component.empty();
        if (plugin.web().running()) {
            buttons = buttons.append(Ui.button("Open dashboard", "/ledger web", "Get a sign-in link for the web dashboard", NamedTextColor.AQUA))
                    .append(Component.text(" "));
        }
        if (to instanceof Player) {
            buttons = buttons.append(Ui.button("Dismiss for today", "/ledger summary mute", "No summary until tomorrow", NamedTextColor.GRAY));
        }
        m.send(to, "summary.buttons", Messages.component("buttons", buttons));
    }

    /** "3 h", "2 days", "40 min". */
    static String away(long since) {
        long minutes = Math.max(0, (System.currentTimeMillis() - since) / 60_000);
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        if (hours < 48) return hours + " h";
        return (hours / 24) + " days";
    }

    private static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) return String.format(java.util.Locale.US, "$%.1fM", v / 1_000_000);
        if (a >= 10_000) return String.format(java.util.Locale.US, "$%.0fK", v / 1_000);
        if (a >= 1_000) return String.format(java.util.Locale.US, "$%.1fK", v / 1_000);
        return Money.format(v);
    }

    private static String list(List<String> names) {
        if (names.size() <= 3) return String.join(", ", names);
        return String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
    }
}
