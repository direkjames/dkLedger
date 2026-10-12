package com.direk.dkledger.audit.read;

import com.direk.dkledger.audit.AuditContext;
import com.direk.dkledger.audit.model.PriceEntry;
import com.direk.dkledger.audit.model.SourceStatus;
import com.direk.dkledger.audit.yaml.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Reads Virtual Spawner's config.yml and mobs/*.yml. Drop sell prices go into the price book. */
public final class VirtualSpawnerReader {

    public static final String SOURCE = "VirtualSpawner";

    private VirtualSpawnerReader() {
    }

    public static void read(AuditContext ctx) {
        Path folder = ctx.find(ctx.settings.virtualSpawnerFolder);
        if (folder == null) {
            ctx.sources.add(new SourceStatus("Virtual Spawner", String.join(" or ", ctx.settings.virtualSpawnerFolder),
                    SourceStatus.State.MISSING, ""));
            return;
        }
        Map<String, Object> config = ctx.read("Virtual Spawner config", folder.resolve("config.yml"));
        int stackLimit = (int) Node.num(Node.child(config, "stack-limit"), 1);
        int chunkLimit = (int) Node.num(Node.child(config, "spawner-limit-in-chunk"), 1);
        boolean sellDisabled = Node.bool(Node.child(config, "disable-sell-feature"), false);

        List<SpawnerData.Mob> mobs = new ArrayList<>();
        Path mobFolder = folder.resolve("mobs");
        if (Files.isDirectory(mobFolder)) {
            List<Path> files;
            try (Stream<Path> list = Files.list(mobFolder)) {
                files = list.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
            } catch (IOException e) {
                files = List.of();
            }
            for (Path file : files) {
                Map<String, Object> mob = ctx.readQuietly(file);
                if (mob != null) mobs.add(mob(ctx, mob, file, sellDisabled));
            }
        }
        ctx.sources.add(new SourceStatus("Virtual Spawner mobs", ctx.relative(mobFolder),
                mobs.isEmpty() ? SourceStatus.State.MISSING : SourceStatus.State.FOUND, mobs.size() + " mobs"));
        ctx.spawners = new SpawnerData(stackLimit, chunkLimit, sellDisabled, mobs);
    }

    private static SpawnerData.Mob mob(AuditContext ctx, Map<String, Object> mob, Path file, boolean sellDisabled) {
        String type = Node.str(Node.child(mob, "entity-type"), file.getFileName().toString().replace(".yml", ""))
                .toUpperCase(Locale.ROOT);
        double min = Node.num(Node.at(mob, "spawn-delays.min"), 10);
        double max = Node.num(Node.at(mob, "spawn-delays.max"), min);
        List<SpawnerData.Drop> drops = new ArrayList<>();
        for (Object drop : Node.map(Node.child(mob, "drops")).values()) {
            String material = Node.str(Node.child(drop, "material"), null);
            if (material == null) continue;
            material = material.toUpperCase(Locale.ROOT);
            double amountMin = Node.num(Node.at(drop, "amount.min"), 1);
            double amountMax = Node.num(Node.at(drop, "amount.max"), amountMin);
            double chance = Node.num(Node.child(drop, "chance"), 100) / 100;
            double price = Node.num(Node.child(drop, "sell-price"), 0);
            drops.add(new SpawnerData.Drop(material, chance * (amountMin + amountMax) / 2, price));
            if (!sellDisabled && price > 0) {
                ctx.prices.add(new PriceEntry(SOURCE, material, -1, price, ctx.relative(file) + " (" + type + " drop)"));
            }
        }
        return new SpawnerData.Mob(type, Math.max(0.05, (min + max) / 2), Node.num(Node.child(mob, "spawner-price"), -1),
                drops, ctx.relative(file));
    }
}
