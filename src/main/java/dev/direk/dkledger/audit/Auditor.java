package dev.direk.dkledger.audit;

import dev.direk.dkledger.audit.check.BankCheck;
import dev.direk.dkledger.audit.check.CommandCheck;
import dev.direk.dkledger.audit.check.OneBlockCheck;
import dev.direk.dkledger.audit.check.PriceChangeCheck;
import dev.direk.dkledger.audit.check.PriceChecks;
import dev.direk.dkledger.audit.check.RecipeChecks;
import dev.direk.dkledger.audit.check.RewardCheck;
import dev.direk.dkledger.audit.check.SinkCheck;
import dev.direk.dkledger.audit.check.SpawnerCheck;
import dev.direk.dkledger.audit.read.ConfigScan;
import dev.direk.dkledger.audit.read.EsguiReader;
import dev.direk.dkledger.audit.read.HeadDatabaseReader;
import dev.direk.dkledger.audit.read.LodestockReader;
import dev.direk.dkledger.audit.read.VirtualSpawnerReader;
import dev.direk.dkledger.audit.yaml.ConfigSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs the whole config audit: reads every plugin's prices and rewards, then runs checks A1 to A12.
 * Uses no Bukkit API, so it can run off the main thread (and in tests).
 */
public final class Auditor {

    private Auditor() {
    }

    /** The checks in the order they run. A9 runs before A7 (it adds the bank's faucet) and A11 (its sinks). */
    private static List<Check> checks() {
        return List.of(
                new PriceChecks.SellAboveBuy(),
                new PriceChecks.CrossPluginArbitrage(),
                new RecipeChecks(),
                new PriceChecks.RenewableValue(),
                new SpawnerCheck(),
                new BankCheck(),
                new RewardCheck(),
                new CommandCheck(),
                new OneBlockCheck(),
                new SinkCheck(),
                new PriceChangeCheck());
    }

    public static AuditResult run(AuditSettings settings, ConfigSource source, ServerFacts server) {
        long start = System.nanoTime();
        AuditContext ctx = new AuditContext(settings, server, source);

        EsguiReader.read(ctx);
        LodestockReader.read(ctx);
        VirtualSpawnerReader.read(ctx);
        HeadDatabaseReader.read(ctx);
        ctx.scan = ConfigScan.run(ctx);
        if (!ctx.scan.broken().isEmpty()) {
            ctx.notes.add(ctx.scan.broken().size() + " plugin files couldn't be read and were skipped: "
                    + String.join(", ", ctx.scan.broken().subList(0, Math.min(10, ctx.scan.broken().size())))
                    + (ctx.scan.broken().size() > 10 ? ", …" : ""));
        }
        if (ctx.prices.isEmpty()) {
            ctx.notes.add("No prices were found. Check the plugin folder names under 'plugins' in dkLedger's config.yml.");
        }

        for (Check check : checks()) {
            try {
                check.run(ctx);
            } catch (RuntimeException e) {
                ctx.add(Finding.warning(check.id(), "Check " + check.id() + " stopped with an error",
                        e + (e.getStackTrace().length > 0 ? " at " + e.getStackTrace()[0] : ""),
                        "Send this to the developer. The other checks still ran.", ""));
            }
        }

        List<Finding> findings = new ArrayList<>(ctx.findings);
        List<String> order = Checks.ids();
        findings.sort(Comparator.comparing(Finding::severity)
                .thenComparingInt(f -> order.indexOf(f.check())));
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new AuditResult(Instant.now(), millis, List.copyOf(findings), List.copyOf(ctx.faucets),
                List.copyOf(ctx.sinks), List.copyOf(ctx.tables), List.copyOf(ctx.sources), List.copyOf(ctx.notes));
    }
}
