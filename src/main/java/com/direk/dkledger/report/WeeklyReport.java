package com.direk.dkledger.report;

import com.direk.dkledger.audit.Money;
import com.direk.dkledger.supply.Snapshot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The weekly economy report, as Markdown and as an HTML page with two charts (money supply over the week,
 * and the biggest money sources and sinks). Pure: everything comes in as {@link Data}.
 */
public final class WeeklyReport {

    public record SourceLine(String name, double gained, double lost, String kind) {
        public double net() {
            return gained - lost;
        }
    }

    public record EarnerLine(String name, double earned, double spent) {
    }

    public record AlertLine(long time, String check, String severity, String title, boolean open) {
    }

    /**
     * @param from      first day of the week ("2026-10-05")
     * @param to        last day
     * @param snapshots supply snapshots of the week, oldest first
     * @param sources   money per source over the week
     * @param earners   top earners of the week
     * @param alerts    alerts raised this week
     * @param scan      a one-line summary of the last config scan, or ""
     * @param frozen    players frozen right now
     */
    public record Data(String from, String to, List<Snapshot> snapshots, List<SourceLine> sources, List<EarnerLine> earners,
                       List<AlertLine> alerts, String scan, int frozen) {
    }

    private WeeklyReport() {
    }

    // ------------------------------------------------------------------ Markdown

    public static String markdown(Data d, ZoneId zone) {
        StringBuilder md = new StringBuilder("# dkLedger weekly report\n\n").append(d.from()).append(" to ").append(d.to()).append("\n\n");
        md.append("## Money supply\n\n").append(supplyText(d)).append("\n\n");
        md.append("## Where money came from and went\n\n| Source | Kind | In | Out | Net |\n|---|---|---|---|---|\n");
        for (SourceLine s : sorted(d.sources())) {
            md.append("| ").append(s.name()).append(" | ").append(s.kind()).append(" | ").append(Money.format(s.gained()))
                    .append(" | ").append(Money.format(s.lost())).append(" | ").append(Money.format(s.net())).append(" |\n");
        }
        md.append("\n## Top earners\n\n");
        if (d.earners().isEmpty()) md.append("Nobody earned money this week.\n");
        for (int i = 0; i < d.earners().size(); i++) {
            EarnerLine e = d.earners().get(i);
            md.append(i + 1).append(". ").append(e.name()).append(": earned ").append(Money.format(e.earned()))
                    .append(", spent ").append(Money.format(e.spent())).append("\n");
        }
        md.append("\n## Alerts\n\n");
        if (d.alerts().isEmpty()) md.append("No alerts this week.\n");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US).withZone(zone);
        for (AlertLine a : d.alerts()) {
            md.append("- ").append(f.format(Instant.ofEpochMilli(a.time()))).append(" **").append(a.severity().toLowerCase(Locale.ROOT))
                    .append("** [").append(a.check()).append("] ").append(a.title()).append(a.open() ? " (open)" : "").append("\n");
        }
        if (!d.scan().isEmpty()) md.append("\n## Config audit\n\n").append(d.scan()).append("\n");
        md.append("\nFrozen players: ").append(d.frozen()).append("\n");
        return md.toString();
    }

    private static String supplyText(Data d) {
        if (d.snapshots().isEmpty()) return "No snapshots yet.";
        Snapshot first = d.snapshots().getFirst();
        Snapshot last = d.snapshots().getLast();
        double change = first.total() > 0 ? last.total() / first.total() - 1 : 0;
        return "Total " + Money.format(last.total()) + " (wallets " + Money.format(last.wallet()) + ", banks " + Money.format(last.bank())
                + "), " + (change >= 0 ? "up " : "down ") + Money.percent(Math.abs(change)) + " this week. "
                + Money.format(last.perActive()) + " per active player. The richest 10% hold " + Money.percent(last.topShare()) + ".";
    }

    private static List<SourceLine> sorted(List<SourceLine> sources) {
        List<SourceLine> list = new ArrayList<>(sources);
        list.sort(Comparator.comparingDouble((SourceLine s) -> Math.abs(s.net())).reversed());
        return list;
    }

    // ------------------------------------------------------------------ HTML with charts

    public static String html(Data d, ZoneId zone) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>dkLedger weekly ").append(esc(d.to())).append("</title><style>")
                .append(":root{--bg:#fbfaf7;--fg:#1d1b18;--muted:#6b665e;--line:#e4e0d8;--accent:#3b6fd8;--up:#2f8f5b;--down:#c4473a}")
                .append("@media (prefers-color-scheme:dark){:root{--bg:#17161a;--fg:#ecebe8;--muted:#a19d96;--line:#2e2c33;--accent:#7aa2ff;--up:#4cc285;--down:#ef6f61}}")
                .append("body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif}")
                .append("main{max-width:860px;margin:0 auto;padding:24px 16px}h1{margin:0 0 4px;font-size:26px}")
                .append("h2{font-size:17px;margin:32px 0 10px}.muted{color:var(--muted)}svg{width:100%;height:auto;display:block}")
                .append("table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}")
                .append("td,th{padding:6px 8px;border-bottom:1px solid var(--line);text-align:left}td.n,th.n{text-align:right}")
                .append(".up{color:var(--up)}.down{color:var(--down)}.wrap{overflow-x:auto}</style></head><body><main>");
        h.append("<h1>dkLedger weekly report</h1><div class=\"muted\">").append(esc(d.from())).append(" to ").append(esc(d.to())).append("</div>");
        h.append("<h2>Money supply</h2><p>").append(esc(supplyText(d))).append("</p>").append(supplyChart(d.snapshots(), zone));
        h.append("<h2>Biggest sources and sinks (net)</h2>").append(sourceChart(sorted(d.sources())));
        h.append("<div class=\"wrap\"><table><tr><th>Source</th><th>Kind</th><th class=\"n\">In</th><th class=\"n\">Out</th><th class=\"n\">Net</th></tr>");
        for (SourceLine s : sorted(d.sources())) {
            h.append("<tr><td>").append(esc(s.name())).append("</td><td class=\"muted\">").append(esc(s.kind())).append("</td><td class=\"n\">")
                    .append(Money.format(s.gained())).append("</td><td class=\"n\">").append(Money.format(s.lost()))
                    .append("</td><td class=\"n ").append(s.net() >= 0 ? "up" : "down").append("\">").append(Money.format(s.net())).append("</td></tr>");
        }
        h.append("</table></div><h2>Top earners</h2><div class=\"wrap\"><table><tr><th>#</th><th>Player</th><th class=\"n\">Earned</th><th class=\"n\">Spent</th></tr>");
        for (int i = 0; i < d.earners().size(); i++) {
            EarnerLine e = d.earners().get(i);
            h.append("<tr><td>").append(i + 1).append("</td><td>").append(esc(e.name())).append("</td><td class=\"n\">")
                    .append(Money.format(e.earned())).append("</td><td class=\"n\">").append(Money.format(e.spent())).append("</td></tr>");
        }
        h.append("</table></div><h2>Alerts</h2>");
        if (d.alerts().isEmpty()) h.append("<p class=\"muted\">No alerts this week.</p>");
        else {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US).withZone(zone);
            h.append("<div class=\"wrap\"><table><tr><th>When</th><th>Level</th><th>Check</th><th>Alert</th></tr>");
            for (AlertLine a : d.alerts()) {
                h.append("<tr><td class=\"muted\">").append(f.format(Instant.ofEpochMilli(a.time()))).append("</td><td class=\"")
                        .append(a.severity().equals("CRITICAL") ? "down" : "").append("\">").append(a.severity().toLowerCase(Locale.ROOT))
                        .append("</td><td>").append(esc(a.check())).append("</td><td>").append(esc(a.title()))
                        .append(a.open() ? " <span class=\"muted\">(open)</span>" : "").append("</td></tr>");
            }
            h.append("</table></div>");
        }
        if (!d.scan().isEmpty()) h.append("<h2>Config audit</h2><p>").append(esc(d.scan())).append("</p>");
        h.append("<p class=\"muted\">Frozen players: ").append(d.frozen()).append(". Made by dkLedger.</p></main></body></html>");
        return h.toString();
    }

    /** A line chart of the total money supply. */
    static String supplyChart(List<Snapshot> snapshots, ZoneId zone) {
        if (snapshots.size() < 2) return "<p class=\"muted\">Not enough snapshots for a chart yet.</p>";
        int w = 800, h = 240, left = 70, right = 16, top = 12, bottom = 28;
        long t0 = snapshots.getFirst().time();
        long t1 = Math.max(t0 + 1, snapshots.getLast().time());
        double min = snapshots.stream().mapToDouble(Snapshot::total).min().orElse(0);
        double max = snapshots.stream().mapToDouble(Snapshot::total).max().orElse(1);
        if (max - min < 1) {
            max += 1;
            min -= 1;
        }
        double pad = (max - min) * 0.08;
        min = Math.max(0, min - pad);
        max += pad;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < snapshots.size(); i++) {
            Snapshot s = snapshots.get(i);
            double x = left + (w - left - right) * (s.time() - t0) / (double) (t1 - t0);
            double y = top + (h - top - bottom) * (1 - (s.total() - min) / (max - min));
            path.append(i == 0 ? "M" : "L").append(fmt(x)).append(',').append(fmt(y));
        }
        StringBuilder svg = new StringBuilder("<svg viewBox=\"0 0 " + w + " " + h + "\" role=\"img\" aria-label=\"Money supply over the week\">");
        for (int i = 0; i <= 3; i++) {
            double v = min + (max - min) * i / 3;
            double y = top + (h - top - bottom) * (1 - i / 3.0);
            svg.append("<line x1=\"").append(left).append("\" x2=\"").append(w - right).append("\" y1=\"").append(fmt(y)).append("\" y2=\"")
                    .append(fmt(y)).append("\" stroke=\"var(--line)\"/>")
                    .append("<text x=\"").append(left - 8).append("\" y=\"").append(fmt(y + 4)).append("\" text-anchor=\"end\" font-size=\"11\" fill=\"var(--muted)\">")
                    .append(esc(Money.format(v))).append("</text>");
        }
        DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE", Locale.US).withZone(zone);
        svg.append("<text x=\"").append(left).append("\" y=\"").append(h - 8).append("\" font-size=\"11\" fill=\"var(--muted)\">")
                .append(day.format(Instant.ofEpochMilli(t0))).append("</text>")
                .append("<text x=\"").append(w - right).append("\" y=\"").append(h - 8).append("\" text-anchor=\"end\" font-size=\"11\" fill=\"var(--muted)\">")
                .append(day.format(Instant.ofEpochMilli(t1))).append("</text>");
        svg.append("<path d=\"").append(path).append("\" fill=\"none\" stroke=\"var(--accent)\" stroke-width=\"2.5\" stroke-linejoin=\"round\"/>");
        return svg.append("</svg>").toString();
    }

    /** Horizontal bars: money each source added (right, green) or removed (left, red). */
    static String sourceChart(List<SourceLine> sources) {
        List<SourceLine> top = sources.stream().filter(s -> Math.abs(s.net()) >= 0.01).limit(10).toList();
        if (top.isEmpty()) return "<p class=\"muted\">No money moved this week.</p>";
        int w = 800, row = 26, labelW = 200, valueW = 90;
        int h = top.size() * row + 8;
        double maxAbs = top.stream().mapToDouble(s -> Math.abs(s.net())).max().orElse(1);
        boolean anyNegative = top.stream().anyMatch(s -> s.net() < 0);
        double zero = labelW + (anyNegative ? (w - labelW - valueW) / 2.0 : 0);
        double span = anyNegative ? (w - labelW - valueW) / 2.0 : (w - labelW - valueW);
        StringBuilder svg = new StringBuilder("<svg viewBox=\"0 0 " + w + " " + h + "\" role=\"img\" aria-label=\"Net money by source\">");
        for (int i = 0; i < top.size(); i++) {
            SourceLine s = top.get(i);
            double y = 4 + i * row;
            double len = span * Math.abs(s.net()) / maxAbs;
            double x = s.net() >= 0 ? zero : zero - len;
            svg.append("<text x=\"").append(labelW - 10).append("\" y=\"").append(fmt(y + 16)).append("\" text-anchor=\"end\" font-size=\"12\" fill=\"var(--fg)\">")
                    .append(esc(s.name().length() > 28 ? s.name().substring(0, 27) + "…" : s.name())).append("</text>")
                    .append("<rect x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y + 4)).append("\" width=\"").append(fmt(Math.max(1, len)))
                    .append("\" height=\"16\" rx=\"3\" fill=\"var(--").append(s.net() >= 0 ? "up" : "down").append(")\"/>")
                    .append("<text x=\"").append(fmt(s.net() >= 0 ? x + len + 6 : x - 6)).append("\" y=\"").append(fmt(y + 16))
                    .append("\" font-size=\"11\" fill=\"var(--muted)\" text-anchor=\"").append(s.net() >= 0 ? "start" : "end").append("\">")
                    .append(esc(Money.format(s.net()))).append("</text>");
        }
        if (anyNegative) {
            svg.append("<line x1=\"").append(fmt(zero)).append("\" x2=\"").append(fmt(zero)).append("\" y1=\"0\" y2=\"").append(h)
                    .append("\" stroke=\"var(--line)\"/>");
        }
        return svg.append("</svg>").toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
