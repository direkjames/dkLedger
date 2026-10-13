package com.direk.dkledger.summary;

import com.direk.dkledger.ledger.Sources;

import java.util.List;
import java.util.Map;

/**
 * What changed in the economy while a staff member was away. Plain Java so it can be tested.
 *
 * @param since          when they were last online (epoch millis)
 * @param supplyNow      money on the server now, or NaN if not counted yet
 * @param supplyThen     money when they left, or NaN if there's no count that old
 * @param created        money created since then (faucets, and staff giving money)
 * @param removed        money removed since then (sinks and fees)
 * @param alerts         alerts raised since then that are still open
 * @param criticals      how many of those are critical
 * @param worstAlert     the title of the most serious one, or null
 * @param newSources     plugins that moved money for the first time since then
 * @param newPlayers     players who joined for the first time since then
 * @param newlyFrozen    players frozen since then
 */
public record Summary(long since, double supplyNow, double supplyThen, double created, double removed, int alerts,
                      int criticals, String worstAlert, List<String> newSources, int newPlayers, List<String> newlyFrozen) {

    /** The change of the money supply (0.05 = +5%), or NaN if it can't be known. */
    public double change() {
        if (Double.isNaN(supplyNow) || Double.isNaN(supplyThen) || supplyThen <= 0) return Double.NaN;
        return supplyNow / supplyThen - 1;
    }

    /**
     * True if something deserves a look: a new alert, a new money source, a freeze, or the money supply moving at
     * least {@code minChange}. New players alone don't count (they're good news, not a reason to check).
     */
    public boolean worthSending(double minChange) {
        double c = change();
        return alerts > 0 || !newSources.isEmpty() || !newlyFrozen.isEmpty() || (!Double.isNaN(c) && Math.abs(c) >= minChange);
    }

    /**
     * Money created and removed from per-source totals ({gained, lost}). Transfers between players and moves between a
     * player's wallet and bank only count their fees (as removed); staff changes count either way.
     */
    public static double[] flow(Map<String, double[]> bySource, Sources rules) {
        double created = 0;
        double removed = 0;
        for (Map.Entry<String, double[]> e : bySource.entrySet()) {
            double net = e.getValue()[0] - e.getValue()[1];
            boolean moves = rules.isTransfer(e.getKey()) || rules.isInternal(e.getKey());
            if (net > 0 && !moves) created += net;
            else if (net < 0) removed += -net;
        }
        return new double[]{created, removed};
    }
}
