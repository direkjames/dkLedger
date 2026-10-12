package com.direk.dkledger.audit.check;

import com.direk.dkledger.audit.AuditContext;
import com.direk.dkledger.audit.AuditSettings;
import com.direk.dkledger.audit.Check;
import com.direk.dkledger.audit.Finding;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.model.Faucet;
import com.direk.dkledger.audit.model.Table;
import com.direk.dkledger.audit.read.ConfigScan;
import com.direk.dkledger.audit.yaml.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A7: every way rewards pay money, and how much a casual player and a grinder get per day.
 * Plugins dkLedger knows (NDailyRewards, RewardsLite, AxAFKZone, ODailyQuests, PinataParty, dkBank via A9)
 * get a real estimate. Money commands in any other plugin are listed so new reward plugins never go unnoticed.
 */
public final class RewardCheck implements Check {

    @Override
    public String id() {
        return "A7";
    }

    @Override
    public void run(AuditContext ctx) {
        Set<String> known = new HashSet<>();
        known.add(pluginOf(ctx, ctx.settings.dkBankFolder));
        dailyLogin(ctx, known);
        playtime(ctx, known);
        afkZone(ctx, known);
        quests(ctx, known);
        votes(ctx, known);
        unknownSchedules(ctx, known);
        shares(ctx);
    }

    // ---------------------------------------------------------------- NDailyRewards

    private void dailyLogin(AuditContext ctx, Set<String> known) {
        Path folder = ctx.find(ctx.settings.nDailyRewardsFolder);
        if (folder == null) return;
        known.add(ctx.relative(folder).split("/")[0]);
        Map<String, Object> config = ctx.read("NDailyRewards config", folder.resolve("config.yml"));
        Path extraFile = folder.resolve("ndailyrewards-rewards.yml");
        Map<String, Object> extra = Files.exists(extraFile) ? ctx.read("NDailyRewards rewards file", extraFile) : null;

        Map<String, Object> days = Node.map(Node.at(config, "rewards.days"));
        Map<String, Object> extraDays = Node.map(Node.at(extra, "rewards.days"));
        if (!days.isEmpty() && !extraDays.isEmpty() && Math.abs(cycle(days) - cycle(extraDays)) > 0.01) {
            ctx.add(Finding.warning(id(), "NDailyRewards has two different reward lists",
                    "config.yml pays " + Money.format(cycle(days)) + " per cycle, ndailyrewards-rewards.yml pays "
                            + Money.format(cycle(extraDays)) + ". Only one of them is used.",
                    "Keep the list NDailyRewards loads and delete the other.", "NDailyRewards/"));
        }
        if (days.isEmpty()) days = extraDays;
        if (days.isEmpty()) return;
        double perDay = cycle(days) / days.size();
        ctx.faucets.add(new Faucet("NDailyRewards", "Daily login", "once a day, " + days.size() + "-day cycle",
                perDay, perDay, Money.format(cycle(days)) + " per " + days.size() + "-day cycle"));
    }

    private static double cycle(Map<String, Object> days) {
        double total = 0;
        for (Object day : days.values()) total += Money.givenByAll(Node.strings(Node.child(day, "actions")));
        return total;
    }

    // ---------------------------------------------------------------- RewardsLite

    private void playtime(AuditContext ctx, Set<String> known) {
        Path folder = ctx.find(ctx.settings.rewardsLiteFolder);
        if (folder == null || !Files.isDirectory(folder)) return;
        known.add(ctx.relative(folder).split("/")[0]);
        record Milestone(String name, double minutes, double money, boolean loop, boolean permission, boolean countAfk) {
        }
        List<Milestone> milestones = new ArrayList<>();
        for (Path file : ymlFiles(folder)) {
            Map<String, Object> r = ctx.readQuietly(file);
            if (r == null) continue;
            milestones.add(new Milestone(file.getFileName().toString().replace(".yml", ""),
                    Node.num(Node.child(r, "requiredTime"), 0), Money.givenByAll(Node.strings(Node.child(r, "rewards"))),
                    Node.bool(Node.child(r, "loop"), false), Node.bool(Node.child(r, "usePermission"), false),
                    Node.bool(Node.child(r, "countAfk"), false)));
        }
        if (milestones.isEmpty()) return;
        milestones.sort(Comparator.comparingDouble(Milestone::minutes));

        double casual = 0;
        double grinder = 0;
        double total = 0;
        double cumulative = 0;
        List<List<String>> rows = new ArrayList<>();
        List<String> loops = new ArrayList<>();
        int days = Math.max(1, ctx.settings.milestoneDays);
        for (Milestone m : milestones) {
            if (m.loop) {
                if (m.money > 0) loops.add(m.name + " (" + Money.format(m.money) + " every " + Money.duration(m.minutes * 60)
                        + (m.permission ? ", needs a permission" : "") + ")");
                if (!m.permission && m.minutes > 0) {
                    casual += m.money * hours(ctx.settings.casual, m.countAfk) * 60 / m.minutes;
                    grinder += m.money * hours(ctx.settings.grinder, m.countAfk) * 60 / m.minutes;
                }
                continue;
            }
            total += m.money;
            cumulative += m.money;
            rows.add(List.of(m.name, Money.hours(m.minutes / 60), Money.format(m.money), Money.format(cumulative)));
            if (m.minutes / 60 <= hours(ctx.settings.casual, m.countAfk) * days) casual += m.money / days;
            if (m.minutes / 60 <= hours(ctx.settings.grinder, m.countAfk) * days) grinder += m.money / days;
        }
        ctx.tables.add(new Table("Playtime milestones (RewardsLite)", List.of("Milestone", "Playtime", "Money", "Total so far"), rows));
        ctx.faucets.add(new Faucet("RewardsLite", "Playtime milestones", "one-time, averaged over " + days + " days",
                casual, grinder, Money.format(total) + " in milestones" + (loops.isEmpty() ? "" : "; repeating: " + String.join(", ", loops))));
    }

    private static double hours(AuditSettings.Profile p, boolean countAfk) {
        return p.activeHours() + (countAfk ? p.afkHours() : 0);
    }

    // ---------------------------------------------------------------- AxAFKZone

    private void afkZone(AuditContext ctx, Set<String> known) {
        Map<String, Object> config = ctx.read("AxAFKZone", ctx.settings.afkZoneConfig);
        if (config == null) return;
        Path file = ctx.find(ctx.settings.afkZoneConfig);
        known.add(ctx.relative(file).split("/")[0]);
        double seconds = Node.num(Node.child(config, "reward-time-seconds"), 600);
        double rolls = Node.num(Node.child(config, "roll-amount"), 1);
        boolean independent = "independent".equalsIgnoreCase(ctx.settings.afkZoneRollMode);

        double chanceTotal = 0;
        double cash = 0;
        double items = 0;
        for (Object reward : Node.list(Node.child(config, "rewards"))) {
            double chance = Node.num(Node.child(reward, "chance"), 0);
            chanceTotal += chance;
            double money = Money.givenByAll(Node.strings(Node.child(reward, "commands")));
            double itemValue = 0;
            for (Object item : Node.list(Node.child(reward, "items"))) {
                String material = Node.str(Node.child(item, "material"), "").toUpperCase(Locale.ROOT);
                itemValue += Node.num(Node.child(item, "amount"), 1) * ctx.prices.sellValue(material);
            }
            double weight = independent ? chance / 100 : chance;
            cash += weight * money;
            items += weight * itemValue;
        }
        if (!independent && chanceTotal > 0) {
            cash /= chanceTotal;
            items /= chanceTotal;
        }
        double perHour = (cash + items) * rolls * 3600 / Math.max(1, seconds);
        ctx.faucets.add(new Faucet("AxAFKZone", "AFK zone", "every " + Money.duration(seconds) + " while AFK",
                perHour * ctx.settings.casual.afkHours(), perHour * ctx.settings.grinder.afkHours(),
                Money.format(perHour) + "/h (" + Money.format(cash * rolls * 3600 / Math.max(1, seconds)) + " cash + "
                        + Money.format(items * rolls * 3600 / Math.max(1, seconds)) + " in items at their best sell price), "
                        + ctx.settings.afkZoneRollMode + " rolls"));
    }

    // ---------------------------------------------------------------- ODailyQuests

    private void quests(AuditContext ctx, Set<String> known) {
        Path folder = ctx.find(ctx.settings.oDailyQuestsFolder);
        if (folder == null) return;
        known.add(ctx.relative(folder).split("/")[0]);
        Map<String, Object> config = ctx.read("ODailyQuests config", folder.resolve("config.yml"));
        if (config == null) return;

        double avgPerDay = 0;
        double bestPerDay = 0;
        int questsPerDay = 0;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> category : Node.map(Node.child(config, "quests_per_category")).entrySet()) {
            int count = (int) Node.num(category.getValue(), 0);
            if (count <= 0) continue;
            Path file = folder.resolve("quests").resolve(category.getKey() + ".yml");
            Map<String, Object> questFile = Files.exists(file) ? ctx.read("ODailyQuests " + category.getKey() + " quests", file) : null;
            List<Double> rewards = new ArrayList<>();
            for (Object quest : Node.map(Node.child(questFile, "quests")).values()) rewards.add(questMoney(quest));
            if (rewards.isEmpty()) continue;
            double avg = rewards.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double best = rewards.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double bonus = Money.givenByAll(Node.strings(Node.at(config, "categories_rewards." + category.getKey() + ".commands")));
            avgPerDay += count * avg + bonus;
            bestPerDay += count * best + bonus;
            questsPerDay += count;
            parts.add(count + " " + category.getKey() + " (avg " + Money.format(avg) + ", best " + Money.format(best) + ")");
        }
        if (questsPerDay == 0) return;
        if (Node.bool(Node.at(config, "global_reward.enabled"), false)) {
            double global = Money.givenByAll(Node.strings(Node.at(config, "global_reward.commands")));
            avgPerDay += global;
            bestPerDay += global;
            parts.add(Money.format(global) + " for finishing all");
        }
        boolean unlimitedRerolls = Node.num(Node.child(config, "reroll_maximum"), 0) < 0;
        ctx.faucets.add(new Faucet("ODailyQuests", "Daily quests", questsPerDay + " quests a day", avgPerDay,
                unlimitedRerolls ? bestPerDay : avgPerDay,
                String.join(", ", parts) + (unlimitedRerolls ? "; grinder rerolls for the best-paying quests" : "")));
        if (unlimitedRerolls) {
            ctx.add(Finding.warning(id(), "Daily quests can be rerolled without limit",
                    "Players with the reroll permission can keep rerolling until they get the best-paying quests: "
                            + Money.format(bestPerDay) + "/day instead of about " + Money.format(avgPerDay) + ".",
                    "Set reroll_maximum to 1-3 in ODailyQuests' config.yml.", "ODailyQuests/config.yml: reroll_maximum"));
        }
    }

    private static double questMoney(Object quest) {
        Object reward = Node.child(quest, "reward");
        if ("MONEY".equalsIgnoreCase(Node.str(Node.child(reward, "reward_type"), ""))) {
            return Node.num(Node.child(reward, "amount"), 0);
        }
        return Money.givenByAll(Node.strings(Node.child(reward, "commands")));
    }

    // ---------------------------------------------------------------- PinataParty

    private void votes(AuditContext ctx, Set<String> known) {
        Map<String, Object> config = ctx.read("PinataParty", ctx.settings.pinataPartyConfig);
        if (config == null) return;
        known.add(ctx.relative(ctx.find(ctx.settings.pinataPartyConfig)).split("/")[0]);
        double perVote = eventMoney(Node.at(config, "commands.player-vote"));
        if (perVote > 0) {
            ctx.faucets.add(new Faucet("PinataParty", "Voting", "per vote",
                    perVote * ctx.settings.casual.votes(), perVote * ctx.settings.grinder.votes(),
                    Money.format(perVote) + " per vote; casual " + ctx.settings.casual.votes() + " and grinder "
                            + ctx.settings.grinder.votes() + " votes a day"));
        }
        double lastHit = eventMoney(Node.at(config, "commands.last-hit"));
        if (lastHit > 0) {
            ctx.notes.add("PinataParty also pays " + Money.format(lastHit) + " to the last hitter of each pinata (not counted per player).");
        }
    }

    /** Money from one PinataParty event section: entries with a chance and command lines. */
    private static double eventMoney(Object section) {
        double total = 0;
        for (Object entry : Node.map(section).values()) {
            double chance = Node.num(Node.child(entry, "chance"), 100) / 100;
            List<String> lines = Node.strings(Node.child(entry, "command"));
            boolean randomLine = Node.bool(Node.child(entry, "random-line"), false);
            double money = Money.givenByAll(lines);
            if (randomLine && !lines.isEmpty()) money /= lines.size();
            total += chance * money;
        }
        return total;
    }

    // ---------------------------------------------------------------- everything else

    private void unknownSchedules(AuditContext ctx, Set<String> known) {
        Map<String, List<ConfigScan.MoneyCommand>> byPlugin = new LinkedHashMap<>();
        for (ConfigScan.MoneyCommand m : ctx.scan.money()) {
            if (known.contains(m.plugin())) continue;
            byPlugin.computeIfAbsent(m.plugin(), k -> new ArrayList<>()).add(m);
        }
        for (Map.Entry<String, List<ConfigScan.MoneyCommand>> e : byPlugin.entrySet()) {
            List<ConfigScan.MoneyCommand> list = e.getValue();
            double min = list.stream().mapToDouble(ConfigScan.MoneyCommand::amount).min().orElse(0);
            double max = list.stream().mapToDouble(ConfigScan.MoneyCommand::amount).max().orElse(0);
            StringBuilder examples = new StringBuilder();
            for (int i = 0; i < Math.min(5, list.size()); i++) {
                ConfigScan.MoneyCommand m = list.get(i);
                examples.append(i == 0 ? "" : "; ").append(m.file()).append(" ").append(m.key()).append(": ").append(m.raw());
            }
            ctx.add(Finding.info(id(), e.getKey() + " gives money (" + list.size() + " commands, "
                            + (min == max ? Money.format(min) : Money.format(min) + " to " + Money.format(max)) + ")",
                    "dkLedger doesn't know how often " + e.getKey() + " pays, so it isn't in the daily estimate. "
                            + "Examples: " + examples + ". The live ledger (phase 2) will measure it.",
                    e.getKey() + "/"));
        }
    }

    private void shares(AuditContext ctx) {
        if (!ctx.settings.faucetScale.isEmpty()) {
            ctx.faucets.replaceAll(f -> {
                Double scale = ctx.settings.faucetScale.get(AuditSettings.key(f.name()));
                if (scale == null) scale = ctx.settings.faucetScale.get(AuditSettings.key(f.plugin()));
                return scale == null ? f : new Faucet(f.plugin(), f.name(), f.schedule(), f.casual() * scale, f.grinder() * scale,
                        f.note() + " (× " + scale + " what if)");
            });
        }
        double casual = ctx.faucets.stream().mapToDouble(Faucet::casual).sum();
        double grinder = ctx.faucets.stream().mapToDouble(Faucet::grinder).sum();
        for (Faucet f : ctx.faucets) {
            String who = null;
            double share = 0;
            if (grinder > 0 && f.grinder() / grinder > ctx.settings.faucetShareWarn) {
                who = "a grinder's";
                share = f.grinder() / grinder;
            } else if (casual > 0 && f.casual() / casual > ctx.settings.faucetShareWarn) {
                who = "a casual player's";
                share = f.casual() / casual;
            }
            if (who != null) {
                ctx.add(Finding.warning(id(), f.name() + " is " + Money.percent(share) + " of " + who + " daily income",
                        f.name() + " (" + f.plugin() + ") pays about " + Money.format(f.casual()) + "/day casual and "
                                + Money.format(f.grinder()) + "/day grinder. One source this big drives inflation on its own.",
                        "Lower " + f.name() + ", or raise the others if it's meant to be the main income. Warning starts at "
                                + Money.percent(ctx.settings.faucetShareWarn) + " (checks.rewards.faucet-share-warn).",
                        f.plugin() + "/"));
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (Faucet f : ctx.faucets) {
            rows.add(List.of(f.name(), f.plugin(), f.schedule(), Money.format(f.casual()), Money.format(f.grinder()), f.note()));
        }
        rows.add(List.of("Total", "", "", Money.format(casual), Money.format(grinder), ""));
        AuditSettings.Profile c = ctx.settings.casual;
        AuditSettings.Profile g = ctx.settings.grinder;
        ctx.tables.addFirst(new Table("Daily income from rewards (casual: " + num(c.activeHours()) + "h active, "
                + num(c.afkHours()) + "h AFK; grinder: " + num(g.activeHours()) + "h active, " + num(g.afkHours()) + "h AFK)",
                List.of("Source", "Plugin", "Schedule", "Casual/day", "Grinder/day", "How"), rows));
        ctx.add(Finding.info(id(), "Reward income: about " + Money.format(casual) + "/day casual, " + Money.format(grinder) + "/day grinder",
                "Rewards only: selling items, spawners and OneBlock blocks come on top. See the daily income table in the report.", ""));
    }

    private static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String pluginOf(AuditContext ctx, List<String> candidates) {
        Path p = ctx.find(candidates);
        return p == null ? "" : ctx.relative(p).split("/")[0];
    }

    private static List<Path> ymlFiles(Path folder) {
        try (Stream<Path> list = Files.list(folder)) {
            return list.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
