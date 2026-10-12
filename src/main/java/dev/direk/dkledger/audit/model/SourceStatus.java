package dev.direk.dkledger.audit.model;

/**
 * Whether a plugin's files were found and read.
 *
 * @param name   what was looked for, e.g. "EconomyShopGUI shops"
 * @param path   where it was found, or the places that were tried
 * @param state  found, missing or broken
 * @param detail extra info, e.g. the error
 */
public record SourceStatus(String name, String path, State state, String detail) {

    public enum State { FOUND, MISSING, ERROR }
}
