package com.direk.dkledger.whatif;

import com.direk.dkledger.audit.AuditResult;
import com.direk.dkledger.audit.AuditSettings;
import com.direk.dkledger.audit.Auditor;
import com.direk.dkledger.audit.Finding;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.ServerFacts;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.audit.model.PriceEntry;
import com.direk.dkledger.audit.model.Table;
import com.direk.dkledger.audit.read.EsguiReader;
import com.direk.dkledger.audit.read.SpawnerData;
import com.direk.dkledger.audit.read.VirtualSpawnerReader;
import com.direk.dkledger.audit.yaml.ConfigSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The "what if" simulator: runs the config audit twice, as it is and with one change, and says what the change
 * would do. Nothing is ever written to any plugin's files; the result says which line to change by hand.
 * <pre>
 * price &lt;MATERIAL&gt; &lt;buy|sell&gt; &lt;amount&gt; [plugin]
 * spawner &lt;MOB&gt; &lt;MATERIAL&gt; &lt;drop sell price&gt;
 * spawnerprice &lt;MOB&gt; &lt;price&gt;
 * bank &lt;tier&gt; &lt;online|offline|cap&gt; &lt;value&gt;
 * reward &lt;reward name&gt; &lt;multiplier&gt;     e.g. reward afk_zone 0.5
 * </pre>
 */
public final class WhatIf {

    public static final String USAGE = "price <MATERIAL> <buy|sell> <amount> [plugin] | spawner <MOB> <MATERIAL> <price> | "
            + "spawnerprice <MOB> <price> | bank <tier> <online|offline|cap> <value> | reward <name> <multiplier>";

    /**
     * One change to simulate.
     *
     * @param description what's being changed, in words
     * @param apply       makes the change in a copy of the settings
     * @param paste       which file and line to change to make it real
     * @param liveSource  the ledger source whose live money this change scales (null if it can't be measured)
     * @param liveScale   how much that source's money scales (NaN = worked out from the daily income estimate)
     */
    public record Change(String description, Consumer<AuditSettings> apply, String paste, String liveSource, double liveScale) {
    }

    /**
     * Live numbers from the ledger, to project the money supply.
     *
     * @param supply      money supply now
     * @param netPerDay   new money per day over the last 7 days (all sources, staff left out)
     * @param sourcePerDay new money per day by source over the last 7 days
     */
    public record Live(double supply, double netPerDay, Map<String, Double> sourcePerDay) {
    }

    private WhatIf() {
    }

    /** @throws IllegalArgumentException with a message for the player if the arguments are wrong */
    public static Change parse(String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: " + USAGE);
        String kind = args[0].toLowerCase(Locale.ROOT);
        switch (kind) {
            case "price" -> {
                if (args.length < 4) throw new IllegalArgumentException("Usage: price <MATERIAL> <buy|sell> <amount> [plugin]");
                String material = args[1].toUpperCase(Locale.ROOT);
                String side = args[2].toLowerCase(Locale.ROOT);
                if (!side.equals("buy") && !side.equals("sell")) throw new IllegalArgumentException("Use buy or sell, not " + args[2] + ".");
                double value = number(args[3]);
                String source = args.length > 4 ? args[4] : EsguiReader.SOURCE;
                double buy = side.equals("buy") ? value : Double.NaN;
                double sell = side.equals("sell") ? value : Double.NaN;
                return new Change(material + " " + side + " price in " + source + " → " + text(value),
                        s -> chain(s, ctx -> ctx.prices.change(material, source, buy, sell)),
                        "Set " + side + ": " + (value > 0 ? value : -1) + " for " + material + " in " + source + "'s files"
                                + " (the files read are listed in /ledger report).", null, Double.NaN);
            }
            case "spawner" -> {
                if (args.length < 4) throw new IllegalArgumentException("Usage: spawner <MOB> <MATERIAL> <drop sell price>");
                String mob = args[1].toUpperCase(Locale.ROOT);
                String material = args[2].toUpperCase(Locale.ROOT);
                double price = number(args[3]);
                return new Change(material + " from " + mob + " spawners sells for " + text(price) + " in Virtual Spawner",
                        s -> chain(s, ctx -> {
                            if (ctx.spawners == null) return;
                            List<SpawnerData.Mob> mobs = new ArrayList<>();
                            for (SpawnerData.Mob m : ctx.spawners.mobs()) {
                                if (!m.type().equals(mob)) {
                                    mobs.add(m);
                                    continue;
                                }
                                List<SpawnerData.Drop> drops = new ArrayList<>();
                                for (SpawnerData.Drop d : m.drops()) {
                                    drops.add(d.material().equals(material) ? new SpawnerData.Drop(d.material(), d.perSpawn(), price) : d);
                                }
                                mobs.add(new SpawnerData.Mob(m.type(), m.secondsPerSpawn(), m.spawnerPrice(), drops, m.file()));
                            }
                            ctx.spawners = new SpawnerData(ctx.spawners.stackLimit(), ctx.spawners.chunkLimit(), ctx.spawners.sellDisabled(), mobs);
                            ctx.prices.replace(e -> e.source().equals(VirtualSpawnerReader.SOURCE) && e.material().equals(material)
                                            && e.where().contains("(" + mob + " drop)"),
                                    e -> new PriceEntry(e.source(), e.material(), e.buy(), price, e.where()));
                        }),
                        "In Virtual Spawner's mobs file for " + mob + ", set the sell-price of the " + material + " drop to " + price + ".",
                        null, Double.NaN);
            }
            case "spawnerprice" -> {
                String mob = args[1].toUpperCase(Locale.ROOT);
                double price = number(args[2]);
                return new Change(mob + " spawner costs " + text(price) + " in the shop",
                        s -> chain(s, ctx -> ctx.shopSpawnerPrices.put(mob, price)),
                        "In EconomyShopGUI's Spawners shop, set buy: " + price + " for the " + mob + " spawner.", null, Double.NaN);
            }
            case "bank" -> {
                if (args.length < 4) throw new IllegalArgumentException("Usage: bank <tier> <online|offline|cap> <value>");
                String tier = args[1].toLowerCase(Locale.ROOT);
                String what = args[2].toLowerCase(Locale.ROOT);
                double value = number(args[3]);
                int slot = switch (what) {
                    case "online" -> 0;
                    case "offline" -> 1;
                    case "cap" -> 2;
                    default -> throw new IllegalArgumentException("Use online, offline or cap, not " + args[2] + ".");
                };
                String key = slot == 0 ? "interest.online-rate" : slot == 1 ? "interest.offline-rate" : "interest.cap";
                return new Change("dkBank tier " + tier + " " + what + " → " + (slot == 2 ? text(value) : value + "%"),
                        s -> {
                            double[] o = s.bankOverrides.computeIfAbsent(tier, k -> new double[]{Double.NaN, Double.NaN, Double.NaN});
                            o[slot] = value;
                        },
                        "In dkBank's tiers.yml, set tiers." + tier + "." + key + ": " + value + ".", "dkBank interest", Double.NaN);
            }
            case "reward" -> {
                String name = args[1];
                double scale = number(args[2]);
                return new Change(name.replace('_', ' ') + " pays × " + scale,
                        s -> s.faucetScale.put(AuditSettings.key(name), scale),
                        "Multiply every money amount of " + name.replace('_', ' ') + " by " + scale + " in its plugin's config.",
                        name, scale);
            }
            default -> throw new IllegalArgumentException("Unknown change '" + args[0] + "'. Use: " + USAGE);
        }
    }

    /** Runs the audit without and with the change, and describes the difference. Off the main thread. */
    public static List<String> run(Change change, AuditSettings settings, ConfigSource source, ServerFacts server, Live live) {
        AuditSettings base = settings.copyForSimulation();
        AuditSettings changed = settings.copyForSimulation();
        change.apply().accept(changed);
        AuditResult before = Auditor.run(base, source, server);
        AuditResult after = Auditor.run(changed, source, server);
        return compare(change, before, after, live);
    }

    public static List<String> compare(Change change, AuditResult before, AuditResult after, Live live) {
        List<String> out = new ArrayList<>();
        out.add("= What if: " + change.description());
        out.add("  Findings: " + counts(before) + "  →  " + counts(after));

        // Findings are matched with their numbers blanked out, so "pays $1,400" -> "pays $800" is one change.
        Map<String, Finding> was = byShape(before.findings());
        Map<String, Finding> now = byShape(after.findings());
        List<String> fixed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Finding> e : was.entrySet()) {
            Finding f = e.getValue();
            if (f.severity() == Severity.INFO) continue;
            Finding a = now.get(e.getKey());
            if (a == null) fixed.add("+ fixed: [" + f.check() + "] " + f.title());
            else if (!a.title().equals(f.title())) changed.add("~ [" + f.check() + "] " + f.title() + "  →  " + a.title());
        }
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, Finding> e : now.entrySet()) {
            Finding f = e.getValue();
            if (f.severity() != Severity.INFO && !was.containsKey(e.getKey())) added.add("- new: [" + f.check() + "] " + f.title());
        }
        limit(out, fixed, 8);
        limit(out, changed, 8);
        limit(out, added, 8);
        if (fixed.isEmpty() && added.isEmpty() && changed.isEmpty()) out.add("  No warnings or critical findings change.");

        double casualBefore = before.casualPerDay();
        double casualAfter = after.casualPerDay();
        double grinderBefore = before.grinderPerDay();
        double grinderAfter = after.grinderPerDay();
        if (Math.abs(casualAfter - casualBefore) > 0.005 || Math.abs(grinderAfter - grinderBefore) > 0.005) {
            out.add("  Reward income per day: casual " + Money.format(casualBefore) + " → " + Money.format(casualAfter)
                    + ", grinder " + Money.format(grinderBefore) + " → " + Money.format(grinderAfter));
        }
        out.addAll(tableChanges(before, after));

        if (live != null && live.supply() > 0) {
            double nowPerDay = live.netPerDay();
            Map.Entry<String, Double> match = change.liveSource() == null ? null : findSource(live.sourcePerDay(), change.liveSource());
            Double sourcePerDay = match == null ? null : match.getValue();
            if (sourcePerDay != null) {
                double scale = Double.isNaN(change.liveScale()) ? ratio(before, after) : change.liveScale();
                double newPerDay = nowPerDay + sourcePerDay * (scale - 1);
                out.add("  Live (last 7 days): " + match.getKey() + " adds " + Money.format(sourcePerDay) + "/day → "
                        + Money.format(sourcePerDay * scale) + "/day");
                out.add("  Money supply in 30 days: " + Money.format(live.supply() + 30 * nowPerDay) + " now → "
                        + Money.format(live.supply() + 30 * newPerDay) + " with this change");
            } else if (change.liveSource() == null) {
                out.add("  Live effect: payments don't say which item was traded, so the ledger can't measure one price. "
                        + "At today's pace the supply reaches " + Money.format(live.supply() + 30 * nowPerDay) + " in 30 days.");
            }
        }
        out.add("  To make it real: " + change.paste());
        return out;
    }

    private static List<String> tableChanges(AuditResult before, AuditResult after) {
        List<String> out = new ArrayList<>();
        Map<String, Table> old = new LinkedHashMap<>();
        for (Table t : before.tables()) old.put(t.title(), t);
        for (Table t : after.tables()) {
            Table o = old.get(t.title());
            if (o == null || t.title().startsWith("Money sinks")) continue;
            Map<String, List<String>> oldRows = new LinkedHashMap<>();
            for (List<String> r : o.rows()) if (!r.isEmpty()) oldRows.put(r.getFirst(), r);
            int shown = 0;
            for (List<String> r : t.rows()) {
                if (r.isEmpty()) continue;
                List<String> was = oldRows.get(r.getFirst());
                if (was == null || was.equals(r)) continue;
                List<String> parts = new ArrayList<>();
                for (int i = 1; i < r.size() && i < was.size() && i < t.headers().size(); i++) {
                    if (!r.get(i).equals(was.get(i))) parts.add(t.headers().get(i) + " " + was.get(i) + " → " + r.get(i));
                }
                if (parts.isEmpty()) continue;
                if (shown == 0) out.add("  " + t.title() + ":");
                if (shown++ < 6) out.add("    " + r.getFirst() + ": " + String.join(", ", parts));
            }
        }
        return out;
    }

    private static double ratio(AuditResult before, AuditResult after) {
        double b = before.grinderPerDay();
        return b > 0 ? after.grinderPerDay() / b : 1;
    }

    private static Map.Entry<String, Double> findSource(Map<String, Double> sources, String name) {
        String key = AuditSettings.key(name);
        for (Map.Entry<String, Double> e : sources.entrySet()) {
            if (AuditSettings.key(e.getKey()).equals(key)) return e;
        }
        for (Map.Entry<String, Double> e : sources.entrySet()) {
            String k = AuditSettings.key(e.getKey());
            if (k.contains(key) || key.contains(k)) return e;
        }
        return null;
    }

    private static void chain(AuditSettings s, Consumer<com.direk.dkledger.audit.AuditContext> step) {
        Consumer<com.direk.dkledger.audit.AuditContext> old = s.mutator;
        s.mutator = old == null ? step : old.andThen(step);
    }

    private static String counts(AuditResult r) {
        return r.count(Severity.CRITICAL) + " critical, " + r.count(Severity.WARNING) + " warnings";
    }

    private static Map<String, Finding> byShape(List<Finding> list) {
        Map<String, Finding> out = new LinkedHashMap<>();
        for (Finding f : list) out.putIfAbsent(shape(f), f);
        return out;
    }

    /** The finding with every number blanked out. */
    static String shape(Finding f) {
        return f.check() + "|" + f.severity() + "|" + f.title().replaceAll("[$]?[0-9][0-9.,]*(%|h|×)?", "#");
    }

    private static void limit(List<String> out, List<String> lines, int max) {
        for (int i = 0; i < lines.size() && i < max; i++) out.add("  " + lines.get(i));
        if (lines.size() > max) out.add("  … and " + (lines.size() - max) + " more");
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text.replace(",", "").replace("$", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' isn't a number.");
        }
    }

    private static String text(double value) {
        return value <= 0 ? "off" : Money.format(value);
    }
}
