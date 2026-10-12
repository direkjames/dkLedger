package com.direk.dkledger.detect;

import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.ledger.LedgerEntry;
import com.direk.dkledger.ledger.Sources;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The checks that run on every ledger entry as it happens:
 * B3 (earnings per hour), B4 (buy and sell loops), B5 (new money sources), B6 (staff money),
 * B9 (money between accounts on the same connection) and B10 (big transfers).
 * Not thread-safe: the ledger calls it from one thread.
 */
public final class LiveWatch {

    private static final long HOUR = 3_600_000L;

    private Sources sources;
    private ZoneId zone;
    private Thresholds t;
    private boolean pastWarmup;
    private final Set<String> knownSources = new HashSet<>();
    /** Connections (hashed IPs) per player, for B9. */
    private final Function<UUID, Set<String>> connections;

    private record Earning(long time, double amount) {
    }

    private record Move(long time, boolean in) {
    }

    private final Map<UUID, Deque<Earning>> earnings = new HashMap<>();
    private final Map<String, Deque<Move>> moves = new HashMap<>();
    private final Map<String, Double> altToday = new HashMap<>();
    private LocalDate altDay;

    public LiveWatch(Sources sources, Thresholds thresholds, ZoneId zone, Function<UUID, Set<String>> connections) {
        this.sources = sources;
        this.t = thresholds;
        this.zone = zone;
        this.connections = connections;
    }

    /** New settings after /ledger reload; what was learned (known sources, recent activity) is kept. */
    public void configure(Sources rules, Thresholds thresholds, ZoneId timeZone) {
        this.sources = rules;
        this.t = thresholds;
        this.zone = timeZone;
    }

    /** Sources seen before (loaded from the database), so they aren't reported as new. */
    public void knownSources(Iterable<String> names) {
        names.forEach(knownSources::add);
    }

    /** New sources are only reported after the first day, when every normal source has shown up once. */
    public void pastWarmup(boolean past) {
        this.pastWarmup = past;
    }

    /** @return alerts this entry causes (often none) */
    public List<AlertDraft> check(LedgerEntry e, String name) {
        List<AlertDraft> out = new ArrayList<>();
        String who = name.isEmpty() ? e.player().toString().substring(0, 8) : name;

        // B5: a source never seen before.
        if (knownSources.add(e.source()) && pastWarmup && t.newSource() && !Sources.isStaff(e.source())) {
            out.add(new AlertDraft("B5", Severity.WARNING, e.source(), "New money source: " + e.source(),
                    e.source() + " moved money for the first time: " + Money.format(e.amount()) + " for " + who
                            + " (" + e.detail() + "). If it's a new reward plugin, check its amounts with /ledger scan."));
        }

        // B6: staff money.
        if (Sources.isStaff(e.source()) && Math.abs(e.amount()) >= t.staffMoneyWarn()) {
            out.add(new AlertDraft("B6", Severity.WARNING, e.source() + ">" + who,
                    e.source().replace(Sources.STAFF_PREFIX, "") + " changed " + who + "'s money by " + Money.format(e.amount()),
                    "Staff change of " + Money.format(e.amount()) + " (" + e.detail() + ") to " + who + "'s "
                            + e.account().name().toLowerCase() + "."));
        }

        // B3 (live part): a player's earnings in the last hour.
        if (e.amount() > 0 && sources.isEarning(e.source())) {
            Deque<Earning> d = earnings.computeIfAbsent(e.player(), k -> new ArrayDeque<>());
            d.addLast(new Earning(e.time(), e.amount()));
            while (!d.isEmpty() && e.time() - d.peekFirst().time > HOUR) d.removeFirst();
            double hour = d.stream().mapToDouble(Earning::amount).sum();
            double before = hour - e.amount();
            Severity s = hour >= t.perHourCrit() && before < t.perHourCrit() ? Severity.CRITICAL
                    : hour >= t.perHourWarn() && before < t.perHourWarn() ? Severity.WARNING : null;
            if (s != null) {
                out.add(new AlertDraft("B3", s, who, who + " earned " + Money.format(hour) + " in the last hour",
                        "Latest: " + Money.format(e.amount()) + " from " + e.source() + ". Warning at "
                                + Money.format(t.perHourWarn()) + "/h, critical at " + Money.format(t.perHourCrit()) + "/h."));
            }
        }

        // B4: buying and selling with the same plugin over and over.
        if (!sources.isTransfer(e.source()) && !sources.isInternal(e.source()) && !Sources.isStaff(e.source())) {
            String key = e.player() + "|" + e.source();
            Deque<Move> d = moves.computeIfAbsent(key, k -> new ArrayDeque<>());
            d.addLast(new Move(e.time(), e.amount() > 0));
            while (!d.isEmpty() && e.time() - d.peekFirst().time > t.loopWindowMillis()) d.removeFirst();
            long in = d.stream().filter(Move::in).count();
            long outs = d.size() - in;
            long pairs = Math.min(in, outs);
            Severity s = pairs == t.loopCrit() ? Severity.CRITICAL : pairs == t.loopWarn() ? Severity.WARNING : null;
            if (s != null) {
                out.add(new AlertDraft("B4", s, who + "|" + e.source(),
                        who + " bought and sold with " + e.source() + " " + pairs + " times in " + (t.loopWindowMillis() / 60_000) + " min",
                        "Buying and selling the same way this often is how money loops are used. Check what " + who
                                + " trades with /ledger player " + who + " and run /ledger scan."));
            }
        }

        // B9 and B10: transfers.
        if (e.other() != null && e.amount() > 0 && sources.isTransfer(e.source())) {
            String from = e.other().toString().substring(0, 8);
            if (e.amount() >= t.bigTransferWarn()) {
                out.add(new AlertDraft("B10", e.amount() >= t.bigTransferCrit() ? Severity.CRITICAL : Severity.WARNING,
                        who + "<" + from, "Big transfer: " + Money.format(e.amount()) + " to " + who + " via " + e.source(),
                        "From player " + e.other() + ". Check both players with /ledger player."));
            }
            Set<String> a = connections.apply(e.player());
            Set<String> b = connections.apply(e.other());
            if (!a.isEmpty() && b.stream().anyMatch(a::contains)) {
                LocalDate today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(e.time()), zone);
                if (!today.equals(altDay)) {
                    altDay = today;
                    altToday.clear();
                }
                String key = e.player().toString();
                double total = altToday.merge(key, e.amount(), Double::sum);
                double before = total - e.amount();
                Severity s = total >= t.altTransferCrit() && before < t.altTransferCrit() ? Severity.CRITICAL
                        : total >= t.altTransferWarn() && before < t.altTransferWarn() ? Severity.WARNING : null;
                if (s != null) {
                    out.add(new AlertDraft("B9", s, who, who + " got " + Money.format(total) + " today from accounts on the same connection",
                            "Money from players who joined from the same IP (latest: " + Money.format(e.amount()) + " via "
                                    + e.source() + "). Often an alt collecting rewards for a main account."));
                }
            }
        }
        return out;
    }
}
