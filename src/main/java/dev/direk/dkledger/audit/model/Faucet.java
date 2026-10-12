package dev.direk.dkledger.audit.model;

/**
 * A way money enters the economy, with an estimate of how much one player gets per day.
 *
 * @param plugin   the plugin that pays it
 * @param name     what it is, e.g. "Daily login"
 * @param schedule how often it pays, in words
 * @param casual   money per day for a casual player
 * @param grinder  money per day for a grinder
 * @param note     how it was worked out
 */
public record Faucet(String plugin, String name, String schedule, double casual, double grinder, String note) {
}
