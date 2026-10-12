package dev.direk.dkledger.audit.read;

import dev.direk.dkledger.audit.AuditContext;
import dev.direk.dkledger.audit.model.PriceEntry;
import dev.direk.dkledger.audit.model.SourceStatus;
import dev.direk.dkledger.audit.yaml.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads EconomyShopGUI's shop files: pages.*.items.* with material, buy and sell (-1 = off). */
public final class EsguiReader {

    public static final String SOURCE = "EconomyShopGUI";
    private static final Pattern SPAWNER_COMMAND = Pattern.compile("(?i)vspawner\\s+give\\s+\\S+\\s+([A-Z_]+)");

    private EsguiReader() {
    }

    public static void read(AuditContext ctx) {
        Path folder = ctx.find(ctx.settings.esguiShops);
        if (folder == null || !Files.isDirectory(folder)) {
            ctx.sources.add(new SourceStatus("EconomyShopGUI shops", String.join(" or ", ctx.settings.esguiShops),
                    SourceStatus.State.MISSING, ""));
            return;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            ctx.sources.add(new SourceStatus("EconomyShopGUI shops", ctx.relative(folder), SourceStatus.State.ERROR, e.getMessage()));
            return;
        }
        int items = 0;
        int broken = 0;
        for (Path file : files) {
            Map<String, Object> shop = ctx.readQuietly(file);
            if (shop == null) {
                broken++;
                continue;
            }
            String section = file.getFileName().toString().replace(".yml", "");
            for (Map.Entry<String, Object> page : Node.map(Node.child(shop, "pages")).entrySet()) {
                for (Map.Entry<String, Object> item : Node.map(Node.child(page.getValue(), "items")).entrySet()) {
                    items += readItem(ctx, section, page.getKey(), item.getKey(), item.getValue()) ? 1 : 0;
                }
            }
        }
        ctx.sources.add(new SourceStatus("EconomyShopGUI shops", ctx.relative(folder),
                broken == 0 ? SourceStatus.State.FOUND : SourceStatus.State.ERROR,
                files.size() + " shops, " + items + " items" + (broken > 0 ? ", " + broken + " files couldn't be read" : "")));
    }

    private static boolean readItem(AuditContext ctx, String section, String page, String id, Object item) {
        String material = Node.str(Node.child(item, "material"), null);
        if (material == null) return false;
        material = material.toUpperCase(Locale.ROOT);
        double buy = Node.num(Node.child(item, "buy"), -1);
        double sell = Node.num(Node.child(item, "sell"), -1);
        String where = "EconomyShopGUI-Premium/shops/" + section + ".yml: " + page + " item " + id;

        // Spawners are given by a command; remember their price for the spawner check.
        for (String command : Node.strings(Node.child(item, "buy-commands"))) {
            Matcher m = SPAWNER_COMMAND.matcher(command);
            if (m.find() && buy > 0) ctx.shopSpawnerPrices.put(m.group(1).toUpperCase(Locale.ROOT), buy);
        }
        // Potions, enchanted books and command items aren't the plain material: leave them out of the price book.
        if (Node.child(item, "potiontypes") != null || Node.child(item, "enchantments") != null
                || Node.child(item, "buy-commands") != null) {
            return true;
        }
        ctx.prices.add(new PriceEntry(SOURCE, material, buy, sell, where));
        return true;
    }
}
