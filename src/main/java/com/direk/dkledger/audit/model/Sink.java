package com.direk.dkledger.audit.model;

/**
 * A way money leaves the economy.
 *
 * @param plugin    the plugin that takes it
 * @param name      what it is
 * @param amount    the price or rate, in words
 * @param repeating true if players keep paying it, false if they pay once
 */
public record Sink(String plugin, String name, String amount, boolean repeating) {
}
