package com.direk.dkledger.report;

import com.direk.dkledger.supply.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyReportTest {

    @Test
    void htmlHasBothChartsAndEscapesNames() {
        long day = 86_400_000L;
        WeeklyReport.Data d = new WeeklyReport.Data("2026-10-06", "2026-10-12",
                List.of(new Snapshot(0, 100_000, 50_000, 20, 8, 0.4, 0.5), new Snapshot(6 * day, 130_000, 60_000, 22, 9, 0.42, 0.5)),
                List.of(new WeeklyReport.SourceLine("VirtualSpawner", 40_000, 0, "faucet"),
                        new WeeklyReport.SourceLine("<script>", 0, 9_000, "sink")),
                List.of(new WeeklyReport.EarnerLine("Steve", 12_000, 3_000)),
                List.of(new WeeklyReport.AlertLine(day, "B1", "WARNING", "Money supply grows 5.5% a day", true)), "", 1);
        String html = WeeklyReport.html(d, ZoneId.of("UTC"));
        assertTrue(html.contains("aria-label=\"Money supply over the week\""));
        assertTrue(html.contains("aria-label=\"Net money by source\""));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
        assertTrue(WeeklyReport.markdown(d, ZoneId.of("UTC")).contains("up 26.7% this week"));
    }
}
