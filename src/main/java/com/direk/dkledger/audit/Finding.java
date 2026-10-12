package com.direk.dkledger.audit;

/**
 * One problem or fact found by a check.
 *
 * @param check    the check that found it, e.g. "A3"
 * @param severity how urgent it is
 * @param title    one line, shown in chat
 * @param detail   the numbers behind it
 * @param fix      what to change, or "" if nothing needs to change
 * @param where    the file (and key) it came from, or ""
 */
public record Finding(String check, Severity severity, String title, String detail, String fix, String where) {

    public static Finding critical(String check, String title, String detail, String fix, String where) {
        return new Finding(check, Severity.CRITICAL, title, detail, fix, where);
    }

    public static Finding warning(String check, String title, String detail, String fix, String where) {
        return new Finding(check, Severity.WARNING, title, detail, fix, where);
    }

    public static Finding info(String check, String title, String detail, String where) {
        return new Finding(check, Severity.INFO, title, detail, "", where);
    }
}
