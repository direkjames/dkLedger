package dev.direk.dkledger.audit.check;

import dev.direk.dkledger.audit.AuditContext;
import dev.direk.dkledger.audit.Check;
import dev.direk.dkledger.audit.Finding;
import dev.direk.dkledger.audit.Money;
import dev.direk.dkledger.audit.model.PriceEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A12: what changed in the prices since the last scan. The prices are saved after every scan in a
 * small tab-separated file (source, material, lowest buy, highest sell).
 */
public final class PriceChangeCheck implements Check {

    @Override
    public String id() {
        return "A12";
    }

    record Price(double buy, double sell) {
    }

    @Override
    public void run(AuditContext ctx) {
        Path file = ctx.settings.snapshotFile;
        if (file == null) return;
        Map<String, Price> now = snapshot(ctx);
        Map<String, Price> before = load(file);
        try {
            save(file, now);
        } catch (IOException e) {
            ctx.notes.add("A12: couldn't save the price snapshot: " + e.getMessage());
        }
        if (before == null) {
            ctx.add(Finding.info(id(), "Prices saved as the starting point",
                    now.size() + " prices saved. The next scan lists what changed.", ""));
            return;
        }
        List<Finding> changes = new ArrayList<>();
        int added = 0;
        int removed = 0;
        for (Map.Entry<String, Price> e : now.entrySet()) {
            Price old = before.get(e.getKey());
            if (old == null) {
                added++;
                continue;
            }
            Price p = e.getValue();
            String[] key = e.getKey().split("\t");
            List<String> parts = new ArrayList<>();
            if (differs(old.buy, p.buy)) parts.add("buy " + text(old.buy) + " → " + text(p.buy));
            if (differs(old.sell, p.sell)) parts.add("sell " + text(old.sell) + " → " + text(p.sell));
            if (!parts.isEmpty()) {
                changes.add(Finding.info(id(), key[1] + " in " + key[0] + ": " + String.join(", ", parts), "", key[0]));
            }
        }
        for (String key : before.keySet()) if (!now.containsKey(key)) removed++;
        if (changes.isEmpty() && added == 0 && removed == 0) {
            ctx.add(Finding.info(id(), "No price changes since the last scan", "", ""));
            return;
        }
        ctx.add(Finding.info(id(), changes.size() + (changes.size() == 1 ? " price" : " prices") + " changed, " + added + " added, "
                        + removed + " removed since the last scan",
                "Changes are listed below. Added and removed items are items that started or stopped being in a shop.", ""));
        ctx.addCapped(id(), changes, "price changes");
    }

    private static Map<String, Price> snapshot(AuditContext ctx) {
        Map<String, Price> map = new TreeMap<>();
        for (PriceEntry e : ctx.prices.all()) {
            String key = e.source() + "\t" + e.material();
            Price old = map.get(key);
            double buy = e.buyable() ? e.buy() : -1;
            double sell = e.sellable() ? e.sell() : -1;
            if (old != null) {
                buy = old.buy > 0 && (buy <= 0 || old.buy < buy) ? old.buy : buy;
                sell = Math.max(old.sell, sell);
            }
            map.put(key, new Price(buy, sell));
        }
        return map;
    }

    static Map<String, Price> load(Path file) {
        if (!Files.exists(file)) return null;
        Map<String, Price> map = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length != 4 || line.startsWith("#")) continue;
                map.put(p[0] + "\t" + p[1], new Price(Double.parseDouble(p[2]), Double.parseDouble(p[3])));
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }
        return map;
    }

    static void save(Path file, Map<String, Price> map) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder out = new StringBuilder("# dkLedger price snapshot: source, material, lowest buy, highest sell (-1 = not possible)\n");
        for (Map.Entry<String, Price> e : map.entrySet()) {
            out.append(e.getKey()).append('\t').append(e.getValue().buy).append('\t').append(e.getValue().sell).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, out, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean differs(double a, double b) {
        return Math.abs(a - b) > 0.0001;
    }

    private static String text(double price) {
        return price > 0 ? Money.format(price) : "off";
    }
}
