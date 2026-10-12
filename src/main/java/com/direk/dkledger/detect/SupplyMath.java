package com.direk.dkledger.detect;

import java.util.Arrays;

/** Statistics on everyone's money. */
public final class SupplyMath {

    private SupplyMath() {
    }

    /** Share (0-1) of all money held by the richest {@code fraction} of accounts (at least one account). */
    public static double topShare(double[] balances, double fraction) {
        double total = Arrays.stream(balances).filter(b -> b > 0).sum();
        if (total <= 0) return 0;
        double[] sorted = balances.clone();
        Arrays.sort(sorted);
        int n = Math.max(1, (int) Math.ceil(sorted.length * fraction));
        double top = 0;
        for (int i = sorted.length - 1; i >= sorted.length - n && i >= 0; i--) top += Math.max(0, sorted[i]);
        return top / total;
    }

    /** Gini coefficient: 0 = everyone has the same, 1 = one player has everything. */
    public static double gini(double[] balances) {
        double[] sorted = Arrays.stream(balances).map(b -> Math.max(0, b)).sorted().toArray();
        int n = sorted.length;
        double total = Arrays.stream(sorted).sum();
        if (n < 2 || total <= 0) return 0;
        double weighted = 0;
        for (int i = 0; i < n; i++) weighted += (i + 1) * sorted[i];
        return (2 * weighted) / (n * total) - (n + 1.0) / n;
    }

    public static double median(double[] values) {
        if (values.length == 0) return 0;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
    }

    /** Average growth per day between two totals {@code days} apart, e.g. 0.05 = 5% a day. */
    public static double dailyGrowth(double before, double now, double days) {
        if (before <= 0 || now <= 0 || days <= 0) return 0;
        return Math.pow(now / before, 1 / days) - 1;
    }
}
