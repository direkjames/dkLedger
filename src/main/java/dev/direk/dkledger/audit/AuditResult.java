package dev.direk.dkledger.audit;

import dev.direk.dkledger.audit.model.Faucet;
import dev.direk.dkledger.audit.model.Sink;
import dev.direk.dkledger.audit.model.SourceStatus;
import dev.direk.dkledger.audit.model.Table;

import java.time.Instant;
import java.util.List;

/** The outcome of one scan. */
public record AuditResult(Instant time, long millis, List<Finding> findings, List<Faucet> faucets,
                          List<Sink> sinks, List<Table> tables, List<SourceStatus> sources, List<String> notes) {

    public long count(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    public double casualPerDay() {
        return faucets.stream().mapToDouble(Faucet::casual).sum();
    }

    public double grinderPerDay() {
        return faucets.stream().mapToDouble(Faucet::grinder).sum();
    }
}
