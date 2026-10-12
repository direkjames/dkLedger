package com.direk.dkledger.audit;

import com.direk.dkledger.audit.model.Faucet;
import com.direk.dkledger.audit.model.PriceBook;
import com.direk.dkledger.audit.model.Sink;
import com.direk.dkledger.audit.model.SourceStatus;
import com.direk.dkledger.audit.model.Table;
import com.direk.dkledger.audit.read.ConfigScan;
import com.direk.dkledger.audit.read.SpawnerData;
import com.direk.dkledger.audit.yaml.ConfigSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Everything one scan reads and finds. */
public final class AuditContext {

    public final AuditSettings settings;
    public final ServerFacts server;
    private final ConfigSource source;

    public final PriceBook prices = new PriceBook();
    /** Filled by the Virtual Spawner reader, or null if it isn't installed. */
    public SpawnerData spawners;
    /** Spawner prices in the shop, by entity type (from "vspawner give" buy commands). */
    public final Map<String, Double> shopSpawnerPrices = new java.util.TreeMap<>();
    /** Every money command and every command in every plugin folder. */
    public ConfigScan scan = ConfigScan.empty();

    public final List<Finding> findings = new ArrayList<>();
    public final List<Faucet> faucets = new ArrayList<>();
    public final List<Sink> sinks = new ArrayList<>();
    public final List<Table> tables = new ArrayList<>();
    public final List<SourceStatus> sources = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    public AuditContext(AuditSettings settings, ServerFacts server, ConfigSource source) {
        this.settings = settings;
        this.server = server;
        this.source = source;
    }

    /** The first of these places (relative to the plugins folder) that exists, or null. */
    public Path find(List<String> candidates) {
        for (String candidate : candidates) {
            Path p = settings.pluginsFolder.resolve(candidate);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    /**
     * Finds and reads a YAML file, and records whether that worked.
     *
     * @return the file's content, or null if it's missing or broken
     */
    public Map<String, Object> read(String what, List<String> candidates) {
        Path file = find(candidates);
        if (file == null) {
            sources.add(new SourceStatus(what, String.join(" or ", candidates), SourceStatus.State.MISSING, ""));
            return null;
        }
        return read(what, file);
    }

    /** Reads a YAML file that's known to exist, and records whether that worked. */
    public Map<String, Object> read(String what, Path file) {
        try {
            Map<String, Object> content = source.load(file);
            sources.add(new SourceStatus(what, relative(file), SourceStatus.State.FOUND, ""));
            return content;
        } catch (IOException | RuntimeException e) {
            sources.add(new SourceStatus(what, relative(file), SourceStatus.State.ERROR, String.valueOf(e.getMessage())));
            return null;
        }
    }

    /** Reads a file without recording it (for the many files of one folder). Null if broken. */
    public Map<String, Object> readQuietly(Path file) {
        try {
            return source.load(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A path relative to the plugins folder, with / separators, for the report. */
    public String relative(Path file) {
        try {
            return settings.pluginsFolder.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    public void add(Finding finding) {
        findings.add(finding);
    }

    /**
     * Adds the most important findings of a list and sums up the rest in one line, so a check
     * that finds 300 small things doesn't flood the report.
     */
    public void addCapped(String check, List<Finding> list, String whatTheRestAre) {
        int max = Math.max(1, settings.maxFindingsPerCheck);
        for (int i = 0; i < list.size() && i < max; i++) findings.add(list.get(i));
        if (list.size() > max) {
            Severity worst = list.subList(max, list.size()).stream().map(Finding::severity)
                    .min(Enum::compareTo).orElse(Severity.INFO);
            findings.add(new Finding(check, worst, (list.size() - max) + " more " + whatTheRestAre,
                    "Only the first " + max + " are listed. Raise checks.max-findings-per-check to see all of them.",
                    "", ""));
        }
    }
}
