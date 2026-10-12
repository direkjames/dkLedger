package com.direk.dkledger.audit;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats amounts and finds money in reward commands. */
public final class Money {

    /**
     * "eco give %player% 500", "[console] economy add <player> 1,000", "/money give {player} 25.5"...
     * Group 1 is the amount. The player argument can be any placeholder.
     */
    private static final Pattern GIVE = Pattern.compile(
            "(?i)(?:^|[\\s\\]/:])(?:essentials:)?(?:eco|economy|money|eeco|emoney)\\s+(?:give|add|deposit)\\s+\\S+\\s+\\$?([0-9][0-9,_]*(?:\\.[0-9]+)?)");

    /** "[luck] {10} ..." in NDailyRewards: a 10% chance. */
    private static final Pattern LUCK = Pattern.compile("(?i)^\\s*\\[luck]\\s*\\{([0-9.]+)}");

    private Money() {
    }

    /** The amount a command gives, if it's a money-giving command. */
    public static OptionalDouble givenBy(String command) {
        Matcher m = GIVE.matcher(command);
        if (!m.find()) return OptionalDouble.empty();
        try {
            return OptionalDouble.of(Double.parseDouble(m.group(1).replace(",", "").replace("_", "")));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** The chance (0-1) of a "[luck] {N}" line, or 1 if it has no luck prefix. */
    public static double luckChance(String command) {
        Matcher m = LUCK.matcher(command);
        if (!m.find()) return 1;
        try {
            return Math.max(0, Math.min(1, Double.parseDouble(m.group(1)) / 100));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Money given by a list of commands, counting "[luck]" chances. */
    public static double givenByAll(Iterable<String> commands) {
        double total = 0;
        for (String c : commands) {
            OptionalDouble amount = givenBy(c);
            if (amount.isPresent()) total += amount.getAsDouble() * luckChance(c);
        }
        return total;
    }

    /** $1,234 for big amounts, $12.50 for small ones. */
    public static String format(double amount) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        double abs = Math.abs(amount);
        String pattern = abs >= 100 ? "#,##0" : "#,##0.00";
        return (amount < 0 ? "-$" : "$") + new DecimalFormat(pattern, symbols).format(abs);
    }

    /** 1.5 -> "1.5×", 12.0 -> "12×". */
    public static String times(double ratio) {
        DecimalFormat f = new DecimalFormat(ratio >= 10 ? "#,##0" : "0.0#", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(ratio) + "×";
    }

    /** 0.25 -> "25%". */
    public static String percent(double fraction) {
        return new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US)).format(fraction * 100) + "%";
    }

    /** Seconds as "45s", "10 min", "2h". */
    public static String duration(double seconds) {
        DecimalFormat f = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.US));
        if (seconds < 60) return f.format(seconds) + "s";
        if (seconds < 3600) return f.format(seconds / 60) + " min";
        return hours(seconds / 3600);
    }

    /** Hours as "120h" or "5.2 days" when long. */
    public static String hours(double hours) {
        if (Double.isInfinite(hours) || Double.isNaN(hours)) return "never";
        DecimalFormat f = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.US));
        if (hours >= 72) return f.format(hours) + "h (" + f.format(hours / 24) + " days)";
        return f.format(hours) + "h";
    }
}
