package dev.direk.dkledger.audit.check;

import dev.direk.dkledger.audit.AuditContext;
import dev.direk.dkledger.audit.Check;
import dev.direk.dkledger.audit.Finding;
import dev.direk.dkledger.audit.Money;
import dev.direk.dkledger.audit.model.Table;
import dev.direk.dkledger.audit.yaml.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A10: what breaking the OneBlock pays in each phase, valuable fixed blocks and chest loot,
 * and whether island resets let players farm the first blocks again.
 */
public final class OneBlockCheck implements Check {

    @Override
    public String id() {
        return "A10";
    }

    private record Phase(String file, String section, String name, int length) {
    }

    @Override
    public void run(AuditContext ctx) {
        Path folder = ctx.find(ctx.settings.aOneBlockFolder);
        if (folder == null) {
            ctx.read("AOneBlock", ctx.settings.aOneBlockFolder.stream().map(f -> f + "/config.yml").toList());
            return;
        }
        Map<String, Object> config = ctx.read("AOneBlock config", folder.resolve("config.yml"));
        int resetLimit = (int) Node.num(Node.at(config, "island.reset.reset-limit"), -1);
        Map<String, Object> index = Files.exists(folder.resolve("phases_index.yml"))
                ? ctx.read("AOneBlock phases index", folder.resolve("phases_index.yml")) : null;
        int loopFrom = (int) Node.num(Node.child(index, "gotoAtEnd"), -1);
        List<Phase> phases = phases(ctx, folder, index);

        List<List<String>> rows = new ArrayList<>();
        List<Finding> fixed = new ArrayList<>();
        List<Finding> rich = new ArrayList<>();
        List<Finding> chests = new ArrayList<>();
        for (Phase phase : phases) {
            Path file = folder.resolve("phases").resolve(phase.file + ".yml");
            Map<String, Object> content = ctx.readQuietly(file);
            Object data = Node.child(content, phase.section);
            if (data == null) continue;
            String name = Node.str(Node.child(data, "name"), phase.name);
            boolean loops = loopFrom >= 0 && parse(phase.section) >= loopFrom;

            // Average sell value per block broken.
            double weight = 0;
            double value = 0;
            List<String[]> contributions = new ArrayList<>();
            for (Map.Entry<String, Object> block : Node.map(Node.child(data, "blocks")).entrySet()) {
                double w = Node.num(block.getValue(), 0);
                double sell = ctx.prices.sellValue(block.getKey().toUpperCase(Locale.ROOT));
                weight += w;
                value += w * sell;
                if (sell > 0) contributions.add(new String[]{block.getKey(), String.valueOf(w * sell), Money.format(sell)});
            }
            double avg = weight > 0 ? value / weight : 0;
            contributions.sort(Comparator.comparingDouble((String[] c) -> Double.parseDouble(c[1])).reversed());
            List<String> top = new ArrayList<>();
            for (int i = 0; i < Math.min(3, contributions.size()); i++) {
                top.add(contributions.get(i)[0] + " " + contributions.get(i)[2]);
            }
            rows.add(List.of(phase.section, name + (loops ? " (repeats)" : ""), phase.length > 0 ? String.valueOf(phase.length) : "?",
                    Money.format(avg), phase.length > 0 ? Money.format(avg * phase.length) : "?", String.join(", ", top)));
            if (avg >= ctx.settings.oneBlockPhaseAverageWarn) {
                rich.add(Finding.warning(id(), name + " pays " + Money.format(avg) + " per block broken",
                        "Selling every block of this phase averages " + Money.format(avg) + " per block"
                                + (phase.length > 0 ? " (" + Money.format(avg * phase.length) + " for the whole phase)" : "")
                                + ". Biggest earners: " + String.join(", ", top) + "." + (loops ? " This phase repeats at the end." : ""),
                        "Lower the sell prices of the top blocks. Warning starts at "
                                + Money.format(ctx.settings.oneBlockPhaseAverageWarn) + " (checks.oneblock.phase-average-warn).",
                        ctx.relative(file)));
            }

            // Fixed blocks: always the same block at the same count, so resets can farm them.
            for (Map.Entry<String, Object> f : Node.map(Node.child(data, "fixedBlocks")).entrySet()) {
                String material = Node.str(f.getValue(), "").toUpperCase(Locale.ROOT);
                double sell = ctx.prices.sellValue(material);
                if (sell < ctx.settings.oneBlockFixedValue) continue;
                String title = name + " always gives " + material + " (" + Money.format(sell) + ") at block " + f.getKey();
                String where = ctx.relative(file) + ": fixedBlocks." + f.getKey();
                if (resetLimit < 0) {
                    fixed.add(Finding.critical(id(), title,
                            "Island resets are unlimited (reset-limit: -1), so players can reset, break " + f.getKey()
                                    + " blocks and sell another " + material + " every time.",
                            "Limit resets (island.reset.reset-limit in AOneBlock's config.yml) or stop " + material + " being sold.", where));
                } else {
                    fixed.add(Finding.info(id(), title,
                            "Resets are limited to " + resetLimit + ", so a player gets at most " + (resetLimit + 1) + ".", where));
                }
            }

            // Chest loot worth a lot when sold.
            Path chestFile = folder.resolve("phases").resolve(phase.file + "_chests.yml");
            Object chestData = Node.child(ctx.readQuietly(chestFile), phase.section);
            List<String> valuable = new ArrayList<>();
            for (Map.Entry<String, Object> chest : Node.map(Node.child(chestData, "chests")).entrySet()) {
                String rarity = Node.str(Node.child(chest.getValue(), "rarity"), "?");
                for (Object item : Node.map(Node.child(chest.getValue(), "contents")).values()) {
                    String material = Node.str(Node.child(item, "type"), "").toUpperCase(Locale.ROOT);
                    double amount = Node.num(Node.child(item, "amount"), 1);
                    double worth = amount * ctx.prices.sellValue(material);
                    if (worth >= ctx.settings.oneBlockChestValue) {
                        valuable.add((long) amount + "× " + material + " " + Money.format(worth) + " (" + rarity.toLowerCase(Locale.ROOT) + ")");
                    }
                }
            }
            if (!valuable.isEmpty()) {
                chests.add(Finding.info(id(), name + " chests can hold loot that sells for a lot",
                        String.join(", ", valuable) + ". Fine as rare jackpots; lower the sell prices if it should stay a trophy.",
                        ctx.relative(chestFile)));
            }
        }
        if (resetLimit < 0) {
            ctx.add(Finding.warning(id(), "Island resets are unlimited",
                    "Every reset starts the first phase again: its fixed blocks and chests can be farmed.",
                    "Set island.reset.reset-limit in AOneBlock's config.yml.", "BentoBox/addons/AOneBlock/config.yml: island.reset.reset-limit"));
        }
        ctx.addCapped(id(), fixed, "valuable fixed blocks");
        ctx.addCapped(id(), rich, "phases that pay a lot per block");
        ctx.addCapped(id(), chests, "phases with valuable chest loot");
        if (!rows.isEmpty()) {
            ctx.tables.add(new Table("OneBlock phases (selling every block at its best price)",
                    List.of("Starts at", "Phase", "Blocks", "Avg $/block", "$ per phase", "Top blocks"), rows));
        }
    }

    private static List<Phase> phases(AuditContext ctx, Path folder, Map<String, Object> index) {
        List<Phase> list = new ArrayList<>();
        for (Object p : Node.list(Node.child(index, "phases"))) {
            String file = Node.str(Node.child(p, "file"), null);
            if (file == null) continue;
            list.add(new Phase(file, Node.str(Node.child(p, "section"), file.split("_")[0]),
                    Node.str(Node.child(p, "name"), file), (int) Node.num(Node.child(p, "length"), 0)));
        }
        if (!list.isEmpty()) return list;
        // No index: every phases/<start>_<name>.yml that isn't a chest file.
        try (Stream<Path> files = Files.list(folder.resolve("phases"))) {
            files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".yml") && !n.endsWith("_chests.yml"))
                    .sorted(Comparator.comparingInt(n -> parse(n.split("_")[0])))
                    .forEach(n -> list.add(new Phase(n.substring(0, n.length() - 4), n.split("_")[0], n, 0)));
        } catch (IOException e) {
            ctx.notes.add("A10: couldn't list AOneBlock phases: " + e.getMessage());
        }
        return list;
    }

    private static int parse(String number) {
        try {
            return Integer.parseInt(number.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
