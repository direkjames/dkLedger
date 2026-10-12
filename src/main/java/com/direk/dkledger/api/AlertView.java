package com.direk.dkledger.api;

/**
 * One alert, as other plugins (like a Discord bot) see it.
 *
 * @param id         alert id, for {@link LedgerApi#resolveAlert}
 * @param time       epoch millis
 * @param check      "B1" to "B10"
 * @param severity   "CRITICAL", "WARNING" or "INFO"
 * @param subject    what it's about: a player, a source, an item or "server"
 * @param title      one line
 * @param detail     the numbers
 * @param open       false once staff resolved it
 * @param resolvedBy who resolved it, or null
 * @param note       their note, or null
 */
public record AlertView(long id, long time, String check, String severity, String subject, String title, String detail,
                        boolean open, String resolvedBy, String note) {
}
