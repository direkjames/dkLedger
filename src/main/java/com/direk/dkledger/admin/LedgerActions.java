package com.direk.dkledger.admin;

import com.direk.dkcore.bank.BankService;
import com.direk.dkcore.player.PlayerService;
import com.direk.dkledger.DkLedger;
import com.direk.dkledger.api.LedgerApi;
import com.direk.dkledger.audit.AuditResult;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.ledger.Account;
import com.direk.dkledger.ledger.LedgerEntry;
import com.direk.dkledger.ledger.LedgerStore;
import com.direk.dkledger.ledger.Sources;
import com.direk.dkledger.ledger.hook.VaultProxy;
import com.direk.dkledger.supply.Snapshot;
import com.direk.dkledger.whatif.WhatIf;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Everything dkLedger can show or do, in one place: /ledger, the menus and the API (and so the future Discord
 * bot) all call this. Every future completes on the main thread.
 */
public final class LedgerActions implements LedgerApi {

    private static final long DAY = 86_400_000L;

    private final DkLedger plugin;
    private final LedgerStore store;

    public LedgerActions(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    private <T> CompletableFuture<T> main(CompletableFuture<T> future) {
        CompletableFuture<T> out = new CompletableFuture<>();
        future.whenComplete((v, err) -> plugin.tasks().sync(() -> {
            if (err != null) out.completeExceptionally(err);
            else out.complete(v);
        }));
        return out;
    }

    private ZoneId zone() {
        return plugin.ledgerConfig().zone();
    }

    // ------------------------------------------------------------------ supply and sources

    @Override
    public CompletableFuture<Supply> supply() {
        Snapshot now = plugin.supply().latest();
        if (now == null) return CompletableFuture.completedFuture(new Supply(0, 0, Double.NaN, Double.NaN, 0, 0, 0));
        CompletableFuture<Optional<Snapshot>> day = store.snapshotAtOrBefore(now.time() - DAY);
        CompletableFuture<Optional<Snapshot>> week = store.snapshotAtOrBefore(now.time() - 7 * DAY);
        return main(CompletableFuture.allOf(day, week).thenApply(v -> new Supply(now.wallet(), now.bank(),
                change(day.join(), now), change(week.join(), now), now.perActive(), now.topShare(), now.time())));
    }

    private static double change(Optional<Snapshot> then, Snapshot now) {
        return then.filter(s -> s.total() > 0 && s.time() < now.time()).map(s -> now.total() / s.total() - 1).orElse(Double.NaN);
    }

    @Override
    public CompletableFuture<List<Source>> sources(int days) {
        LocalDate today = LocalDate.now(zone());
        return main(store.sourceTotals(today.minusDays(Math.max(1, days) - 1L).toString(), today.toString()).thenApply(this::merge));
    }

    private List<Source> merge(List<LedgerStore.SourceTotal> totals) {
        Map<String, double[]> merged = new LinkedHashMap<>();
        for (LedgerStore.SourceTotal t : totals) {
            double[] m = merged.computeIfAbsent(t.source(), k -> new double[2]);
            m[0] += t.gained();
            m[1] += t.lost();
        }
        Sources rules = plugin.ledger().sources();
        List<Source> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : merged.entrySet()) {
            String s = e.getKey();
            String kind = Sources.isStaff(s) ? "staff" : rules.isTransfer(s) ? "transfer" : rules.isInternal(s) ? "wallet-bank"
                    : e.getValue()[0] >= e.getValue()[1] ? "faucet" : "sink";
            out.add(new Source(s, e.getValue()[0], e.getValue()[1], kind));
        }
        out.sort(Comparator.comparingDouble((Source s) -> Math.abs(s.net())).reversed());
        return out;
    }

    /** New money per day over the last 7 days, for the simulator's projection. */
    public CompletableFuture<WhatIf.Live> live() {
        Snapshot now = plugin.supply().latest();
        return sources(7).thenApply(list -> {
            double net = 0;
            Map<String, Double> perDay = new LinkedHashMap<>();
            for (Source s : list) {
                if (s.kind().equals("staff")) continue;
                net += s.net();
                perDay.put(s.name(), s.net() / 7);
            }
            return new WhatIf.Live(now == null ? 0 : now.total(), net / 7, perDay);
        });
    }

    public CompletableFuture<List<LedgerStore.PlayerTotal>> top(int days) {
        LocalDate today = LocalDate.now(zone());
        return main(store.topEarners(today.minusDays(Math.max(1, days) - 1L).toString(), today.toString(), 10));
    }

    // ------------------------------------------------------------------ players

    /** Finds a player by name (online or anyone dkCore has seen). Completes on the main thread. */
    public CompletableFuture<Optional<PlayerService.PlayerRef>> find(String name) {
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new PlayerService.PlayerRef(online.getUniqueId(), online.getName())));
        }
        return main(plugin.core().players().find(name));
    }

    @Override
    public CompletableFuture<Optional<Player>> player(String name) {
        return find(name).thenCompose(ref -> {
            if (ref.isEmpty()) return CompletableFuture.completedFuture(Optional.<Player>empty());
            UUID uuid = ref.get().uuid();
            LocalDate today = LocalDate.now(zone());
            CompletableFuture<Map<String, double[]>> week = store.playerSources(uuid, System.currentTimeMillis() - 7 * DAY);
            CompletableFuture<Map<String, double[]>> day = store.playerSources(uuid,
                    today.atStartOfDay(zone()).toInstant().toEpochMilli());
            CompletableFuture<List<LedgerEntry>> recent = store.entries(uuid, 8, 0);
            CompletableFuture<List<LedgerStore.Note>> notes = store.notes(uuid);
            return main(CompletableFuture.allOf(week, day, recent, notes).thenApply(v -> {
                Sources rules = plugin.ledger().sources();
                List<Source> sources = new ArrayList<>();
                double earned7 = 0;
                for (Map.Entry<String, double[]> e : week.join().entrySet()) {
                    sources.add(new Source(e.getKey(), e.getValue()[0], e.getValue()[1], rules.isEarning(e.getKey()) ? "earning" : "other"));
                    if (rules.isEarning(e.getKey())) earned7 += e.getValue()[0];
                }
                double earnedToday = 0;
                for (Map.Entry<String, double[]> e : day.join().entrySet()) {
                    if (rules.isEarning(e.getKey())) earnedToday += e.getValue()[0];
                }
                DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(zone());
                List<String> lines = new ArrayList<>();
                for (LedgerEntry e : recent.join()) {
                    lines.add("#" + e.id() + " " + f.format(Instant.ofEpochMilli(e.time())) + " " + (e.amount() >= 0 ? "+" : "")
                            + Money.format(e.amount()) + " " + e.account().name().toLowerCase(Locale.ROOT) + " " + e.source()
                            + (e.other() != null ? " (" + plugin.ledger().name(e.other()) + ")" : ""));
                }
                List<String> noteLines = new ArrayList<>();
                for (LedgerStore.Note n : notes.join()) {
                    noteLines.add(f.format(Instant.ofEpochMilli(n.time())) + " " + n.by() + ": " + n.note());
                }
                Optional<LedgerStore.Frozen> frozen = plugin.freezes().get(uuid);
                return Optional.of(new Player(uuid, ref.get().name(), frozen.isPresent(), frozen.map(LedgerStore.Frozen::reason).orElse(null),
                        earnedToday, earned7, sources, lines, noteLines));
            }));
        });
    }

    // ------------------------------------------------------------------ alerts

    @Override
    public List<com.direk.dkledger.api.AlertView> alerts() {
        return plugin.alerts().recent();
    }

    @Override
    public CompletableFuture<Boolean> resolveAlert(long id, String by, String note) {
        return main(plugin.alerts().resolve(id, by, note));
    }

    // ------------------------------------------------------------------ admin actions

    @Override
    public CompletableFuture<Boolean> freeze(String name, String by, String reason) {
        return find(name).thenCompose(ref -> ref.isEmpty() ? CompletableFuture.completedFuture(false)
                : main(plugin.freezes().freeze(ref.get().uuid(), ref.get().name(), by, reason == null ? "" : reason)
                .thenApply(v -> true)));
    }

    @Override
    public CompletableFuture<Boolean> unfreeze(String name) {
        return find(name).thenCompose(ref -> ref.isEmpty() ? CompletableFuture.completedFuture(false)
                : main(plugin.freezes().unfreeze(ref.get().uuid())));
    }

    @Override
    public boolean isFrozen(UUID player) {
        return plugin.freezes().isFrozen(player);
    }

    @Override
    public CompletableFuture<Boolean> note(String name, String by, String text) {
        return find(name).thenCompose(ref -> ref.isEmpty() ? CompletableFuture.completedFuture(false)
                : main(store.addNote(ref.get().uuid(), System.currentTimeMillis(), by, text).thenApply(v -> true)));
    }

    @Override
    public CompletableFuture<String> reverse(long entryId, String by) {
        return main(store.entry(entryId)).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture("Entry #" + entryId + " doesn't exist (or was deleted after "
                    + plugin.ledgerConfig().ledger().keepDays() + " days).");
            LedgerEntry e = found.get();
            double undo = -e.amount();
            String what = (undo >= 0 ? "Gave " : "Took ") + Money.format(Math.abs(undo)) + (undo >= 0 ? " to " : " from ")
                    + e.name() + "'s " + e.account().name().toLowerCase(Locale.ROOT) + " (undoing #" + entryId + ": "
                    + e.source() + ").";
            String source = Sources.staff(by);
            if (e.account() == Account.BANK) {
                Optional<BankService> bank = plugin.bank().get();
                if (bank.isEmpty()) return CompletableFuture.completedFuture("dkBank isn't running, so bank entries can't be undone.");
                return (undo >= 0 ? bank.get().give(e.player(), undo, source) : bank.get().take(e.player(), -undo, source))
                        .thenApply(r -> r.success() ? what : "Couldn't undo #" + entryId + ": " + r.failure().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
            }
            Economy economy = plugin.vault() == null ? null : plugin.vault().delegate();
            if (economy == null) return CompletableFuture.completedFuture("No economy plugin is running.");
            OfflinePlayer p = Bukkit.getOfflinePlayer(e.player());
            EconomyResponse r = VaultProxy.untracked(() -> undo >= 0 ? economy.depositPlayer(p, undo) : economy.withdrawPlayer(p, -undo));
            if (r == null || !r.transactionSuccess()) {
                return CompletableFuture.completedFuture("Couldn't undo #" + entryId + ": " + (r == null ? "no answer" : r.errorMessage) + ".");
            }
            plugin.ledger().record(e.player(), e.name(), Account.WALLET, undo, source, "reverse #" + entryId, r.balance);
            return CompletableFuture.completedFuture(what);
        });
    }

    // ------------------------------------------------------------------ audit and simulator

    @Override
    public CompletableFuture<List<String>> scan() {
        return plugin.scans().scan().thenApply(r -> r == null ? List.of("A scan is already running.") : summary(r));
    }

    public static List<String> summary(AuditResult r) {
        return List.of("Scan done in " + r.millis() + " ms: " + r.count(Severity.CRITICAL) + " critical, " + r.count(Severity.WARNING)
                        + " warnings, " + r.count(Severity.INFO) + " info.",
                "Rewards pay about " + Money.format(r.casualPerDay()) + "/day casual, " + Money.format(r.grinderPerDay()) + "/day grinder.");
    }

    @Override
    public CompletableFuture<List<String>> whatIf(String arguments) {
        WhatIf.Change change;
        try {
            change = WhatIf.parse(arguments.trim().split("\\s+"));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(List.of(e.getMessage()));
        }
        return live().thenCompose(live -> plugin.scans().whatIf(change, live));
    }
}
