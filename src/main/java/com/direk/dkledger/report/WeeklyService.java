package com.direk.dkledger.report;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.api.AlertView;
import com.direk.dkledger.audit.AuditResult;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.config.LedgerConfig;
import com.direk.dkledger.ledger.LedgerStore;
import com.direk.dkledger.ledger.Sources;
import com.direk.dkledger.scan.ScanService;
import com.direk.dkledger.supply.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Writes the weekly report (reports/weekly-&lt;date&gt;.html and .md) on the configured day and time,
 * or right away with /ledger weekly.
 */
public final class WeeklyService {

    private static final long DAY = 86_400_000L;
    private static final String LAST = "last-weekly";

    private final DkLedger plugin;
    private final LedgerStore store;
    private volatile String lastWeek = "";

    public WeeklyService(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public CompletableFuture<Void> load() {
        return store.meta(LAST).thenAccept(v -> lastWeek = v.orElse(""));
    }

    /** Checked every few minutes: writes the report once its day and time have come this week. Main thread. */
    public void tick() {
        LedgerConfig.Weekly w = plugin.ledgerConfig().weekly();
        if (!w.enabled()) return;
        ZonedDateTime now = ZonedDateTime.now(plugin.ledgerConfig().zone());
        ZonedDateTime due = now.with(TemporalAdjusters.previousOrSame(w.day())).with(w.time()).withSecond(0).withNano(0);
        if (now.isBefore(due)) return;
        String week = due.toLocalDate().toString();
        if (week.equals(lastWeek)) return;
        boolean firstRun = lastWeek.isEmpty();
        lastWeek = week;
        store.meta(LAST, week);
        if (firstRun) return; // just installed: the first report comes next week, with a full week of data
        write().whenComplete((path, err) -> {
            if (err != null) plugin.getLogger().log(Level.WARNING, "Couldn't write the weekly report", err);
            else plugin.getLogger().info("Weekly economy report written: plugins/dkLedger/reports/" + path.getFileName());
        });
    }

    /** Writes the report for the last 7 days now. Completes with the HTML file. */
    public CompletableFuture<Path> write() {
        LedgerConfig config = plugin.ledgerConfig();
        LocalDate today = LocalDate.now(config.zone());
        String from = today.minusDays(6).toString();
        String to = today.toString();
        long now = System.currentTimeMillis();
        Sources sources = plugin.ledger().sources();

        CompletableFuture<List<Snapshot>> snaps = store.snapshots(now - 7 * DAY, now);
        CompletableFuture<List<LedgerStore.SourceTotal>> totals = store.sourceTotals(from, to);
        CompletableFuture<List<LedgerStore.PlayerTotal>> earners = store.topEarners(from, to, 10);
        return CompletableFuture.allOf(snaps, totals, earners).thenApply(v -> {
            Map<String, double[]> merged = new LinkedHashMap<>();
            for (LedgerStore.SourceTotal t : totals.join()) {
                double[] m = merged.computeIfAbsent(t.source(), k -> new double[2]);
                m[0] += t.gained();
                m[1] += t.lost();
            }
            List<WeeklyReport.SourceLine> lines = new ArrayList<>();
            for (Map.Entry<String, double[]> e : merged.entrySet()) {
                String s = e.getKey();
                String kind = Sources.isStaff(s) ? "staff" : sources.isTransfer(s) ? "between players"
                        : sources.isInternal(s) ? "wallet ↔ bank" : e.getValue()[0] >= e.getValue()[1] ? "faucet" : "sink";
                lines.add(new WeeklyReport.SourceLine(s, e.getValue()[0], e.getValue()[1], kind));
            }
            List<WeeklyReport.EarnerLine> top = new ArrayList<>();
            for (LedgerStore.PlayerTotal p : earners.join()) top.add(new WeeklyReport.EarnerLine(p.name(), p.earned(), p.spent()));
            List<WeeklyReport.AlertLine> alerts = new ArrayList<>();
            for (AlertView a : plugin.alerts().recent()) {
                if (now - a.time() <= 7 * DAY) alerts.add(new WeeklyReport.AlertLine(a.time(), a.check(), a.severity(), a.title(), a.open()));
            }
            AuditResult scan = plugin.scans().last();
            String scanText = scan == null ? "" : "Last scan: " + scan.count(Severity.CRITICAL) + " critical, " + scan.count(Severity.WARNING)
                    + " warnings. Rewards pay about " + Money.format(scan.casualPerDay()) + "/day casual and "
                    + Money.format(scan.grinderPerDay()) + "/day grinder.";
            WeeklyReport.Data data = new WeeklyReport.Data(from, to, snaps.join(), lines, top, alerts, scanText, plugin.freezes().all().size());
            try {
                Path folder = plugin.getDataFolder().toPath().resolve("reports");
                Files.createDirectories(folder);
                Path html = folder.resolve("weekly-" + to + ".html");
                Files.writeString(html, WeeklyReport.html(data, config.zone()), StandardCharsets.UTF_8);
                Files.writeString(folder.resolve("weekly-" + to + ".md"), WeeklyReport.markdown(data, config.zone()), StandardCharsets.UTF_8);
                ScanService.prune(folder, "weekly-", config.weekly().keep() * 2);
                return html;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }
}
