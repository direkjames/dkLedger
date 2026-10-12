package dev.direk.dkledger.scan;

import dev.direk.dkledger.DkLedgerPlugin;
import dev.direk.dkledger.audit.AuditResult;
import dev.direk.dkledger.audit.Auditor;
import dev.direk.dkledger.audit.ServerFacts;
import dev.direk.dkledger.audit.report.ReportWriter;
import dev.direk.dkledger.config.LedgerConfig;
import dev.direk.dkledger.platform.ServerFactsCollector;
import dev.direk.dkledger.platform.SnakeYamlSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Runs config scans. Recipes and commands are collected on the main thread; reading files and running the
 * checks happens on a background thread, so a scan never lags the server.
 */
public final class ScanService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US);

    private final DkLedgerPlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile AuditResult last;
    private volatile Path lastReport;

    public ScanService(DkLedgerPlugin plugin) {
        this.plugin = plugin;
    }

    public AuditResult last() {
        return last;
    }

    public Path lastReport() {
        return lastReport;
    }

    public boolean running() {
        return running.get();
    }

    /**
     * Starts a scan. Must be called on the main thread.
     *
     * @param done called on the main thread with the result, or null if the scan failed
     * @return false if a scan is already running
     */
    public boolean start(Consumer<AuditResult> done) {
        if (!running.compareAndSet(false, true)) return false;
        LedgerConfig config = plugin.ledgerConfig();
        ServerFacts facts;
        try {
            facts = ServerFactsCollector.collect(plugin.getServer());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't read the server's recipes and commands; A3, A4 and A8 are skipped", e);
            facts = ServerFacts.none();
        }
        ServerFacts server = facts;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            AuditResult result = null;
            Path report = null;
            try {
                result = Auditor.run(config.audit(), new SnakeYamlSource(), server);
                report = save(result, config);
            } catch (RuntimeException | IOException e) {
                plugin.getLogger().log(Level.SEVERE, "The scan failed", e);
            } finally {
                running.set(false);
            }
            AuditResult finished = result;
            Path saved = report;
            if (!plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (finished != null) {
                    last = finished;
                    lastReport = saved;
                }
                done.accept(finished);
            });
        });
        return true;
    }

    /** Writes the report as reports/scan-&lt;time&gt;.md and reports/latest.md, and deletes the oldest reports. */
    private Path save(AuditResult result, LedgerConfig config) throws IOException {
        Path folder = plugin.getDataFolder().toPath().resolve("reports");
        Files.createDirectories(folder);
        String markdown = ReportWriter.markdown(result, config.zone());
        Path file = folder.resolve("scan-" + FILE_TIME.withZone(config.zone()).format(result.time()) + ".md");
        Files.writeString(file, markdown, StandardCharsets.UTF_8);
        Path tmp = folder.resolve("latest.md.tmp");
        Files.writeString(tmp, markdown, StandardCharsets.UTF_8);
        Files.move(tmp, folder.resolve("latest.md"), StandardCopyOption.REPLACE_EXISTING);

        List<Path> old;
        try (Stream<Path> list = Files.list(folder)) {
            old = list.filter(p -> p.getFileName().toString().startsWith("scan-"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .skip(config.keepReports()).toList();
        }
        for (Path p : old) Files.deleteIfExists(p);
        return file;
    }
}
