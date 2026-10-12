package com.direk.dkledger.ledger;

import java.util.UUID;

/**
 * One change to one player's money.
 *
 * @param id      database id, 0 until saved
 * @param time    epoch millis
 * @param player  whose money changed
 * @param name    their name, or "" if unknown
 * @param account wallet or bank
 * @param amount  the change: positive = they got money, negative = they lost money
 * @param source  what caused it: a plugin name, "staff:Name", "console", "Essentials /pay", "dkBank interest"...
 * @param detail  extra info: the command, the method, the bank transaction kind
 * @param other   for transfers, the other player; otherwise null
 * @param balance the balance afterwards, or NaN if unknown
 * @param reason  why, as reported by the plugin through the API ("quest: daily_3"), or ""
 */
public record LedgerEntry(long id, long time, UUID player, String name, Account account, double amount,
                          String source, String detail, UUID other, double balance, String reason) {

    public LedgerEntry {
        if (reason == null) reason = "";
    }

    /** An entry without a reason. */
    public LedgerEntry(long id, long time, UUID player, String name, Account account, double amount,
                       String source, String detail, UUID other, double balance) {
        this(id, time, player, name, account, amount, source, detail, other, balance, "");
    }

    public LedgerEntry withId(long newId) {
        return new LedgerEntry(newId, time, player, name, account, amount, source, detail, other, balance, reason);
    }

    public LedgerEntry withOther(UUID otherPlayer) {
        return new LedgerEntry(id, time, player, name, account, amount, source, detail, otherPlayer, balance, reason);
    }
}
