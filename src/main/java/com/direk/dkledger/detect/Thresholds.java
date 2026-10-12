package com.direk.dkledger.detect;

/**
 * Every alert threshold from config.yml (alerts section). Defaults are the safe values we agreed on.
 */
public record Thresholds(
        double warmupDays,
        double supplyGrowthWarn, double supplyGrowthCrit,
        double perPlayerGrowthWarn, double perPlayerGrowthCrit,
        double sourceShareWarn, double sourceShareCrit, double sourceShareMinNewMoney,
        double sinkRatioWarn, double sinkRatioCrit,
        double medianWarn, double medianCrit, double medianMinEarned, int medianMinEarners,
        double perHourWarn, double perHourCrit,
        int loopWarn, int loopCrit, long loopWindowMillis,
        boolean newSource,
        double staffMoneyWarn,
        double topShareWarn, double topShareCrit, int topShareMinAccounts,
        double priceChangeWarn, double priceChangeCrit, int priceChangeDays,
        double altTransferWarn, double altTransferCrit,
        double bigTransferWarn, double bigTransferCrit) {

    public static Thresholds defaults() {
        return new Thresholds(14,
                0.05, 0.10,
                0.15, 0.30,
                0.40, 0.60, 10_000,
                0.30, 0.15,
                8, 15, 5_000, 5,
                10_000, 25_000,
                20, 50, 10 * 60_000L,
                true,
                10_000,
                0.60, 0.75, 10,
                0.30, 0.60, 7,
                5_000, 20_000,
                25_000, 100_000);
    }
}
