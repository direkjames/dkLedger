package com.direk.dkledger.detect;

import com.direk.dkcore.message.Messages;
import com.direk.dkledger.DkLedger;
import com.direk.dkledger.api.AlertView;
import com.direk.dkledger.api.LedgerAlertEvent;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.ledger.LedgerStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Saves alerts, tells admins online, and keeps the last 30 days in memory. The same problem
 * (check + subject) isn't raised again within the repeat time, unless it got worse.
 */
public final class AlertService {

    public static final String PERMISSION = "dkledger.alerts";
    private static final long DAY = 86_400_000L;

    private final DkLedger plugin;
    private final LedgerStore store;
    private final Map<String, long[]> lastRaised = new ConcurrentHashMap<>();
    private final List<AlertView> recent = new CopyOnWriteArrayList<>();
    private volatile long repeatMillis = 6 * 3_600_000L;
    private volatile boolean notify = true;
    private volatile String sound = "block.note_block.bell";

    public AlertService(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void configure(long repeatMillis, boolean notify, String sound) {
        this.repeatMillis = repeatMillis;
        this.notify = notify;
        this.sound = sound == null ? "" : sound;
    }

    /** Loads the last 30 days of alerts. */
    public CompletableFuture<Void> load() {
        return store.alerts(System.currentTimeMillis() - 30 * DAY, 500).thenAccept(list -> {
            List<AlertView> views = new ArrayList<>();
            for (LedgerStore.StoredAlert a : list) views.add(view(a));
            recent.clear();
            recent.addAll(views);
            for (AlertView v : views) lastRaised.putIfAbsent(v.check() + "|" + v.subject(), new long[]{v.time(), rank(v.severity())});
        });
    }

    /** Raises alerts from any thread. */
    public void raise(List<AlertDraft> drafts) {
        long now = System.currentTimeMillis();
        for (AlertDraft d : drafts) {
            long[] last = lastRaised.get(d.key());
            int rank = rank(d.severity().name());
            if (last != null && now - last[0] < repeatMillis && rank <= last[1]) continue;
            lastRaised.put(d.key(), new long[]{now, rank});
            store.addAlert(now, d.check(), d.severity().name(), d.subject(), d.title(), d.detail()).whenComplete((id, err) -> {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Couldn't save an alert: " + d.title(), err);
                    return;
                }
                AlertView view = new AlertView(id, now, d.check(), d.severity().name(), d.subject(), d.title(), d.detail(), true, null, null);
                plugin.tasks().sync(() -> announce(view));
            });
        }
    }

    private void announce(AlertView view) {
        recent.addFirst(view);
        while (recent.size() > 500) recent.removeLast();
        String line = "[" + view.check() + "] " + view.title() + " - " + view.detail();
        if (view.severity().equals(Severity.INFO.name())) plugin.getLogger().info("Alert " + line);
        else plugin.getLogger().warning("Alert " + line);
        if (notify) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.hasPermission(PERMISSION)) continue;
                plugin.messages().send(p, "alert.notify-" + view.severity().toLowerCase(java.util.Locale.ROOT),
                        Messages.text("check", view.check()), Messages.text("title", view.title()),
                        Messages.component("details", com.direk.dkledger.command.Ui.button("details", "/ledger alerts",
                                view.detail() + " (alert #" + view.id() + ")", net.kyori.adventure.text.format.NamedTextColor.AQUA)));
                if (!sound.isBlank()) {
                    try {
                        p.playSound(p.getLocation(), sound, 1f, 1f);
                    } catch (RuntimeException ignored) {
                        // a sound name from config that doesn't exist: just no sound
                    }
                }
            }
        }
        Bukkit.getPluginManager().callEvent(new LedgerAlertEvent(view));
    }

    public CompletableFuture<Boolean> resolve(long id, String by, String note) {
        return store.resolveAlert(id, System.currentTimeMillis(), by, note).thenApply(done -> {
            if (done) {
                for (int i = 0; i < recent.size(); i++) {
                    AlertView a = recent.get(i);
                    if (a.id() == id) {
                        recent.set(i, new AlertView(a.id(), a.time(), a.check(), a.severity(), a.subject(), a.title(), a.detail(),
                                false, by, note));
                    }
                }
            }
            return done;
        });
    }

    /** Alerts of the last 30 days, newest first. */
    public List<AlertView> recent() {
        return List.copyOf(recent);
    }

    public List<AlertView> open() {
        return recent.stream().filter(AlertView::open).toList();
    }

    public static String color(String severity) {
        return switch (severity) {
            case "CRITICAL" -> "<red>";
            case "WARNING" -> "<yellow>";
            default -> "<aqua>";
        };
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 2;
            case "WARNING" -> 1;
            default -> 0;
        };
    }

    private static AlertView view(LedgerStore.StoredAlert a) {
        return new AlertView(a.id(), a.time(), a.check(), a.severity(), a.subject(), a.title(), a.detail(), a.open(),
                a.resolvedBy(), a.note());
    }
}
