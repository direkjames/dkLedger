package com.direk.dkledger.supply;

/**
 * Everyone's money at one moment.
 *
 * @param time     epoch millis
 * @param wallet   money in wallets
 * @param bank     money in dkBank
 * @param accounts players counted
 * @param active   players seen in the last 24 hours
 * @param topShare share (0-1) held by the richest 10% of players
 * @param gini     inequality, 0 (equal) to 1 (one player has everything)
 */
public record Snapshot(long time, double wallet, double bank, int accounts, int active, double topShare, double gini) {

    public double total() {
        return wallet + bank;
    }

    /** Money per active player (or per account if nobody was active). */
    public double perActive() {
        int n = active > 0 ? active : Math.max(1, accounts);
        return total() / n;
    }
}
