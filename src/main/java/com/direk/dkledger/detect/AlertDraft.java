package com.direk.dkledger.detect;

import com.direk.dkledger.audit.Severity;

/**
 * An alert a check wants to raise. The alert service decides whether it's new or a repeat.
 *
 * @param check    "B1" to "B10"
 * @param severity warning or critical (info for new sources)
 * @param subject  what it's about: a player name, a source, an item, or "server"
 * @param title    one line
 * @param detail   the numbers
 */
public record AlertDraft(String check, Severity severity, String subject, String title, String detail) {

    /** Alerts with the same key are the same problem: they're not repeated within the repeat time. */
    public String key() {
        return check + "|" + subject;
    }
}
