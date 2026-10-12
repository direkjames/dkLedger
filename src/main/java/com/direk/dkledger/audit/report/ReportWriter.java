package com.direk.dkledger.audit.report;

import com.direk.dkledger.audit.AuditResult;
import com.direk.dkledger.audit.Checks;
import com.direk.dkledger.audit.Finding;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.audit.model.SourceStatus;
import com.direk.dkledger.audit.model.Table;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Turns a scan into a Markdown report (readable as plain text, nicer in any Markdown viewer). */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static String markdown(AuditResult r, ZoneId zone) {
        StringBuilder md = new StringBuilder();
        String time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(zone).format(r.time());
        md.append("# dkLedger config audit\n\n");
        md.append("Scanned ").append(time).append(" in ").append(r.millis()).append(" ms. ")
                .append(r.count(Severity.CRITICAL)).append(" critical, ")
                .append(r.count(Severity.WARNING)).append(" warnings, ")
                .append(r.count(Severity.INFO)).append(" info.\n\n");

        for (Severity severity : Severity.values()) {
            List<Finding> list = r.findings().stream().filter(f -> f.severity() == severity).toList();
            if (list.isEmpty()) continue;
            md.append("## ").append(heading(severity)).append(" (").append(list.size()).append(")\n\n");
            String lastCheck = "";
            for (Finding f : list) {
                if (!f.check().equals(lastCheck)) {
                    md.append("### ").append(f.check()).append(" · ").append(Checks.name(f.check())).append("\n\n");
                    lastCheck = f.check();
                }
                md.append("- **").append(escape(f.title())).append("**");
                if (!f.detail().isEmpty()) md.append("  \n  ").append(escape(f.detail()));
                if (!f.fix().isEmpty()) md.append("  \n  *Fix:* ").append(escape(f.fix()));
                if (!f.where().isEmpty()) md.append("  \n  `").append(f.where().replace("`", "'")).append('`');
                md.append("\n");
            }
            md.append("\n");
        }

        for (Table t : r.tables()) {
            md.append("## ").append(t.title()).append("\n\n");
            md.append("| ").append(String.join(" | ", t.headers())).append(" |\n");
            md.append("|").append("---|".repeat(t.headers().size())).append("\n");
            for (List<String> row : t.rows()) {
                md.append("| ").append(String.join(" | ", row.stream().map(ReportWriter::cell).toList())).append(" |\n");
            }
            md.append("\n");
        }

        md.append("## Files read\n\n");
        for (SourceStatus s : r.sources()) {
            md.append("- ").append(switch (s.state()) {
                case FOUND -> "✓";
                case MISSING -> "– not found:";
                case ERROR -> "✗ couldn't read:";
            }).append(' ').append(s.name()).append(" (`").append(s.path()).append("`)");
            if (!s.detail().isEmpty()) md.append(": ").append(escape(s.detail()));
            md.append("\n");
        }
        if (!r.notes().isEmpty()) {
            md.append("\n## Notes\n\n");
            for (String note : r.notes()) md.append("- ").append(escape(note)).append("\n");
        }
        md.append("""

                ## How to read the numbers

                - Prices are each plugin's base prices. EconomyShopGUI's dynamic pricing and Lodestock's stock-based prices move away from them as players trade.
                - Daily income counts rewards only, for the casual and grinder profiles in dkLedger's config.yml.
                - Spawner earnings are for one spawner with a player nearby.
                - Recipe checks look one step deep (A into B), not chains (A into B into C).
                """);
        return md.toString();
    }

    private static String heading(Severity s) {
        return switch (s) {
            case CRITICAL -> "Critical";
            case WARNING -> "Warnings";
            case INFO -> "Info";
        };
    }

    private static String escape(String text) {
        return text.replace("*", "\\*").replace("_", "\\_");
    }

    private static String cell(String text) {
        return escape(text).replace("|", "\\|");
    }
}
