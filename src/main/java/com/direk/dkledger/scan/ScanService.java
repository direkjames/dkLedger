package com.direk.dkledger.scan;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.audit.AuditResult;
import com.direk.dkledger.audit.Auditor;
import com.direk.dkledger.audit.ServerFacts;
import com.direk.dkledger.audit.report.ReportWriter;
import com.direk.dkledger.config.LedgerConfig;
import com.direk.dkledger.platform.ServerFactsCollector;
import com.direk.dkledger.platform.SnakeYamlSource;
import com.direk.dkledger.whatif.WhatIf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Runs config scans and "what if" simulations. Recipes and commands are collected on the main thread;
 * reading files and running the checks happens in the background, so a scan never lags the server.
 */
public final class ScanService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US);

    private final DkLedger plugin;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile AuditResult last;
    private volatile Path lastReport;

    public ScanService(DkLedger plugin) {
        this.plugin = plugin;
    }

    public AuditResult last() {
        return last;
    }

    public Path lastReport() {
        return lastReport;
    }

    /**
     * Starts a scan. Main thread.
     *
     * @return completes on the main thread with the result; empty future value (null) if one is already running
     */
    public CompletableFuture<AuditResult> scan() {
        CompletableFuture<AuditResult> done = new CompletableFuture<>();
        if (!running.compareAndSet(false, true)) {
            done.complete(null);
            return done;
        }
        LedgerConfig config = plugin.ledgerConfig();
        ServerFacts facts = facts();
        plugin.tasks().async(() -> {
            AuditResult result = null;
            Path report = null;
            try {
                result = Auditor.run(config.audit(), new SnakeYamlSource(), facts);
                report = save(result, config);
            } catch (RuntimeException | IOException e) {
                plugin.getLogger().log(Level.SEVERE, "The config scan failed", e);
            } finally {
                running.set(false);
            }
            AuditResult finished = result;
            Path saved = report;
            if (!plugin.isEnabled()) return;
            plugin.tasks().sync(() -> {
                if (finished != null) {
                    last = finished;
                    lastReport = saved;
                    done.complete(finished);
                } else {
                    done.completeExceptionally(new IllegalStateException("The scan failed. See the console."));
                }
            });
        });
        return done;
    }

    /** Runs a "what if" simulation. Main thread; completes on the main thread with the result lines. */
    public CompletableFuture<List<String>> whatIf(WhatIf.Change change, WhatIf.Live live) {
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        LedgerConfig config = plugin.ledgerConfig();
        ServerFacts facts = facts();
        plugin.tasks().async(() -> {
            try {
                List<String> lines = WhatIf.run(change, config.audit(), new SnakeYamlSource(), facts, live);
                plugin.tasks().sync(() -> done.complete(lines));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "The simulation failed", e);
                plugin.tasks().sync(() -> done.completeExceptionally(e));
            }
        });
        return done;
    }

    private ServerFacts facts() {
        try {
            return ServerFactsCollector.collect(plugin.getServer());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't read the server's recipes and commands; A3, A4 and A8 are skipped", e);
            return ServerFacts.none();
        }
    }

    /** Writes reports/scan-&lt;time&gt;.md and reports/latest.md, and deletes the oldest reports. */
    private Path save(AuditResult result, LedgerConfig config) throws IOException {
        Path folder = plugin.getDataFolder().toPath().resolve("reports");
        Files.createDirectories(folder);
        String markdown = ReportWriter.markdown(result, config.zone());
        Path file = folder.resolve("scan-" + FILE_TIME.withZone(config.zone()).format(result.time()) + ".md");
        Files.writeString(file, markdown, StandardCharsets.UTF_8);
        Path tmp = folder.resolve("latest.md.tmp");
        Files.writeString(tmp, markdown, StandardCharsets.UTF_8);
        Files.move(tmp, folder.resolve("latest.md"), StandardCopyOption.REPLACE_EXISTING);
        prune(folder, "scan-", config.keepReports());
        return file;
    }

    /** Keeps the newest {@code keep} files starting with {@code prefix}. */
    public static void prune(Path folder, String prefix, int keep) throws IOException {
        List<Path> old;
        try (Stream<Path> list = Files.list(folder)) {
            old = list.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .skip(keep).toList();
        }
        for (Path p : old) Files.deleteIfExists(p);
    }
}
