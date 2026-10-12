package com.direk.dkledger.audit.check;

import com.direk.dkledger.audit.AuditContext;
import com.direk.dkledger.audit.Check;
import com.direk.dkledger.audit.Finding;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.model.Faucet;
import com.direk.dkledger.audit.model.Sink;
import com.direk.dkledger.audit.model.Table;
import com.direk.dkledger.audit.yaml.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A9: what each dkBank tier pays at its cap. dkBank pays the online rate per online period on the
 * balance up to the cap, and the offline rate per offline period (AFK time also earns the offline rate).
 */
public final class BankCheck implements Check {

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([smhd])");

    @Override
    public String id() {
        return "A9";
    }

    record Tier(String id, double cost, boolean buyable, double cap, double onlineRate, double offlineRate) {
    }

    @Override
    public void run(AuditContext ctx) {
        Path folder = ctx.find(ctx.settings.dkBankFolder);
        if (folder == null) {
            ctx.read("dkBank", ctx.settings.dkBankFolder.stream().map(f -> f + "/tiers.yml").toList());
            return;
        }
        Map<String, Object> config = ctx.read("dkBank config", folder.resolve("config.yml"));
        Map<String, Object> tiersFile = ctx.read("dkBank tiers", folder.resolve("tiers.yml"));
        if (tiersFile == null) return;

        boolean onlineOn = Node.bool(Node.at(config, "interest.online.enabled"), true);
        boolean offlineOn = Node.bool(Node.at(config, "interest.offline.enabled"), true);
        double onlinePeriodH = seconds(Node.str(Node.at(config, "interest.online.period"), "1h")) / 3600.0;
        double offlinePeriodD = seconds(Node.str(Node.at(config, "interest.offline.period"), "1d")) / 86400.0;

        List<Tier> tiers = new ArrayList<>();
        for (Map.Entry<String, Object> e : Node.map(Node.child(tiersFile, "tiers")).entrySet()) {
            Object t = e.getValue();
            double cap = Node.num(Node.at(t, "interest.cap"), -1);
            double online = Node.num(Node.at(t, "interest.online-rate"), 0);
            double offline = Node.num(Node.at(t, "interest.offline-rate"), 0);
            double[] o = ctx.settings.bankOverrides.get(e.getKey().toLowerCase(java.util.Locale.ROOT));
            if (o != null) {
                if (!Double.isNaN(o[0])) online = o[0];
                if (!Double.isNaN(o[1])) offline = o[1];
                if (!Double.isNaN(o[2])) cap = o[2];
            }
            tiers.add(new Tier(e.getKey(), Node.num(Node.child(t, "upgrade-cost"), 0),
                    Node.bool(Node.child(t, "buyable"), true), cap, online, offline));
        }
        if (tiers.isEmpty()) return;

        List<List<String>> rows = new ArrayList<>();
        for (Tier t : tiers) {
            double perHour = onlineOn ? onlinePerHour(t, onlinePeriodH) : 0;
            double perDayOff = offlineOn ? offlinePerDay(t, offlinePeriodD) : 0;
            rows.add(List.of(t.id, t.buyable ? Money.format(t.cost) : "not buyable",
                    t.cap > 0 ? Money.format(t.cap) : "NO CAP", t.onlineRate + "%", Money.format(perHour),
                    t.offlineRate + "%", Money.format(perDayOff)));
            String where = "dkBank/tiers.yml: tiers." + t.id;
            if (t.cap <= 0 && (t.onlineRate > 0 || t.offlineRate > 0)) {
                ctx.add(Finding.critical(id(), "Bank tier " + t.id + " has no interest cap",
                        "Without a cap, interest grows with the balance and snowballs: dkBank's own tests show over 1,000× in a year.",
                        "Set interest.cap for " + t.id + ".", where));
            } else if (perHour > ctx.settings.bankOnlinePerHourWarn) {
                ctx.add(Finding.warning(id(), "Bank tier " + t.id + " pays " + Money.format(perHour) + " per online hour",
                        "At its " + Money.format(t.cap) + " cap, " + t.onlineRate + "% per " + period(onlinePeriodH) + " pays "
                                + Money.format(perHour) + "/h online and " + Money.format(perDayOff) + "/day offline. Warning starts at "
                                + Money.format(ctx.settings.bankOnlinePerHourWarn) + "/h (checks.bank.online-per-hour-warn).",
                        "Lower the online rate or the cap of " + t.id + ".", where));
            }
        }
        // Paid tiers shouldn't out-earn tiers players buy with in-game money.
        for (Tier paid : tiers) {
            if (paid.buyable || paid.cap <= 0) continue;
            double paidPerHour = onlinePerHour(paid, onlinePeriodH);
            List<String> beaten = new ArrayList<>();
            for (Tier bought : tiers) {
                if (bought.buyable && bought.cost > 0 && paidPerHour > onlinePerHour(bought, onlinePeriodH)) {
                    beaten.add(bought.id + " (" + Money.format(bought.cost) + ")");
                }
            }
            if (!beaten.isEmpty()) {
                ctx.add(Finding.info(id(), "Tier " + paid.id + " (not buyable) earns more per hour than " + String.join(", ", beaten),
                        paid.id + " pays " + Money.format(paidPerHour) + "/h at its cap. If " + paid.id
                                + " comes with a paid rank, that's more interest than tiers players buy with in-game money.",
                        "dkBank/tiers.yml: tiers." + paid.id));
            }
        }
        if (config != null && !Node.bool(Node.at(config, "alt-limit.enabled"), false)) {
            ctx.add(Finding.warning(id(), "dkBank's alt limit is off",
                    "Alt accounts can each earn interest, and also collect login, playtime and vote rewards.",
                    "Set alt-limit.enabled: true in dkBank's config.yml.", "dkBank/config.yml: alt-limit.enabled"));
        }
        ctx.tables.add(new Table("dkBank tiers (balance at the cap)",
                List.of("Tier", "Upgrade cost", "Cap", "Online rate", "Online $/h", "Offline rate", "Offline $/day"), rows));

        // Faucet: a casual player at the cheapest tier, a grinder at the most expensive buyable tier.
        Tier low = tiers.stream().filter(Tier::buyable).min((a, b) -> Double.compare(a.cost, b.cost)).orElse(tiers.getFirst());
        Tier high = tiers.stream().filter(Tier::buyable).max((a, b) -> Double.compare(a.cost, b.cost)).orElse(low);
        ctx.faucets.add(new Faucet("dkBank", "Bank interest", "hourly online, daily offline",
                perDay(low, ctx.settings.casual.activeHours(), onlineOn, offlineOn, onlinePeriodH, offlinePeriodD),
                perDay(high, ctx.settings.grinder.activeHours(), onlineOn, offlineOn, onlinePeriodH, offlinePeriodD),
                "casual at " + low.id + ", grinder at " + high.id + ", both at the tier's cap"));

        // Sinks: upgrades and fees.
        for (Tier t : tiers) {
            if (t.buyable && t.cost > 0) ctx.sinks.add(new Sink("dkBank", "Upgrade to " + t.id, Money.format(t.cost), false));
        }
        double withdrawFee = Node.num(Node.at(config, "fees.withdraw-percent"), 0);
        double transferFee = Node.num(Node.at(config, "fees.transfer-percent"), 0);
        if (withdrawFee > 0) ctx.sinks.add(new Sink("dkBank", "Withdraw fee", withdrawFee + "% of each withdrawal", true));
        if (transferFee > 0) ctx.sinks.add(new Sink("dkBank", "Transfer fee", transferFee + "% of each transfer", true));
        if (transferFee > 0 && ctx.server.commands().contains("pay")) {
            ctx.add(Finding.info(id(), "/pay skips dkBank's " + transferFee + "% transfer fee",
                    "Players can send money with /pay (or a trade plugin) for free instead of a bank transfer.",
                    "dkBank/config.yml: fees.transfer-percent"));
        }
    }

    static double onlinePerHour(Tier t, double periodHours) {
        return t.cap <= 0 ? 0 : t.cap * t.onlineRate / 100 / Math.max(1e-9, periodHours);
    }

    static double offlinePerDay(Tier t, double periodDays) {
        return t.cap <= 0 ? 0 : t.cap * t.offlineRate / 100 / Math.max(1e-9, periodDays);
    }

    private static double perDay(Tier t, double activeHours, boolean on, boolean off, double onP, double offP) {
        double online = on ? onlinePerHour(t, onP) * activeHours : 0;
        double offline = off ? offlinePerDay(t, offP) * Math.max(0, 24 - activeHours) / 24 : 0;
        return online + offline;
    }

    /** "1h" -> 3600, "1d12h" -> 129600, "30m" -> 1800. */
    static long seconds(String text) {
        Matcher m = DURATION.matcher(text.toLowerCase());
        double total = 0;
        while (m.find()) {
            double n = Double.parseDouble(m.group(1));
            total += switch (m.group(2)) {
                case "s" -> n;
                case "m" -> n * 60;
                case "h" -> n * 3600;
                default -> n * 86400;
            };
        }
        return total <= 0 ? 3600 : (long) total;
    }

    private static String period(double hours) {
        return hours == 1 ? "hour" : Money.hours(hours);
    }
}
