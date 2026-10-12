package com.direk.dkledger.ledger;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Source names and what kind of money movement each one is.
 * <ul>
 *   <li><b>transfer</b> sources move money between players (/pay, trades, auctions, player shops): they
 *   neither create nor destroy money (except fees), and don't count as earnings.</li>
 *   <li><b>internal</b> sources move money between one player's own wallet and bank (dkBank deposits and
 *   withdrawals): only their fees matter.</li>
 *   <li><b>staff</b> sources are staff commands and the console.</li>
 *   <li>everything else creates money (net positive: a faucet) or destroys it (net negative: a sink).</li>
 * </ul>
 */
public final class Sources {

    public static final String STAFF_PREFIX = "staff:";
    public static final String CONSOLE = "console";
    public static final String UNKNOWN = "unknown";
    public static final String ESSENTIALS_PAY = "Essentials /pay";
    public static final String ESSENTIALS_SELL = "Essentials /sell";
    public static final String ESSENTIALS = "Essentials";
    public static final String BANK = "dkBank";
    public static final String BANK_TRANSFERS = "dkBank transfers";
    public static final String BANK_INTEREST = "dkBank interest";
    public static final String BANK_UPGRADES = "dkBank upgrades";

    private final Set<String> transfer;
    private final Set<String> internal;

    public Sources(Iterable<String> transfer, Iterable<String> internal) {
        this.transfer = lower(transfer);
        this.internal = lower(internal);
    }

    public boolean isTransfer(String source) {
        return transfer.contains(source.toLowerCase(Locale.ROOT));
    }

    public boolean isInternal(String source) {
        return internal.contains(source.toLowerCase(Locale.ROOT));
    }

    public static boolean isStaff(String source) {
        return source.startsWith(STAFF_PREFIX) || source.equals(CONSOLE);
    }

    /** True if money from this source counts as a player's own earnings (not moved from another player or their bank). */
    public boolean isEarning(String source) {
        return !isTransfer(source) && !isInternal(source) && !isStaff(source);
    }

    public static String staff(String name) {
        return STAFF_PREFIX + name;
    }

    private static Set<String> lower(Iterable<String> names) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (String n : names) out.add(n.toLowerCase(Locale.ROOT));
        return out.stream().collect(Collectors.toUnmodifiableSet());
    }
}
