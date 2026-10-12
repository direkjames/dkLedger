package com.direk.dkledger.whatif;

import com.direk.dkledger.audit.AuditSettings;
import com.direk.dkledger.audit.ServerFacts;
import com.direk.dkledger.audit.yaml.ConfigSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatIfTest {

    /** A tiny plugins folder: one shop file and dkBank's tiers. */
    private static final class Files2 implements ConfigSource {
        final Path root;
        final Map<Path, Map<String, Object>> content = new HashMap<>();

        Files2() throws IOException {
            root = Files.createTempDirectory("dkledger-whatif");
        }

        void put(String rel, Map<String, Object> map) throws IOException {
            Path p = root.resolve(rel);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "#");
            content.put(p.toAbsolutePath().normalize(), map);
        }

        @Override
        public Map<String, Object> load(Path file) throws IOException {
            Map<String, Object> m = content.get(file.toAbsolutePath().normalize());
            if (m == null) throw new IOException("not here");
            return m;
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Files2 setup() throws IOException {
        Files2 f = new Files2();
        f.put("EconomyShopGUI-Premium/shops/Blocks.yml", map("pages", map("page1", map("items", map(
                "1", map("material", "BASALT", "buy", 40.0, "sell", 10.0),
                "2", map("material", "MOSSY_STONE_BRICK_SLAB", "buy", 0.4, "sell", 0.45))))));
        f.put("dkBank/config.yml", map("alt-limit", map("enabled", true)));
        f.put("dkBank/tiers.yml", map("tiers", map("diamond", map("upgrade-cost", 750000,
                "interest", map("online-rate", 0.35, "offline-rate", 0.25, "cap", 400000)))));
        return f;
    }

    @Test
    void loweringAPriceFixesTheFinding() throws IOException {
        Files2 f = setup();
        List<String> out = WhatIf.run(WhatIf.parse(new String[]{"price", "BASALT", "sell", "0.18"}),
                AuditSettings.defaults(f.root), f, ServerFacts.none(), null);
        assertTrue(out.stream().anyMatch(l -> l.contains("fixed") && l.contains("BASALT")), String.join("\n", out));
        assertTrue(out.getLast().contains("To make it real"));
    }

    @Test
    void bankChangeShowsTheNewRateAndProjection() throws IOException {
        Files2 f = setup();
        WhatIf.Live live = new WhatIf.Live(1_000_000, 20_000, Map.of("dkBank interest", 8_000.0));
        List<String> out = WhatIf.run(WhatIf.parse(new String[]{"bank", "diamond", "online", "0.2"}),
                AuditSettings.defaults(f.root), f, ServerFacts.none(), live);
        String all = String.join("\n", out);
        assertTrue(all.contains("$1,400 → $800"), all);
        assertTrue(all.contains("fixed") && all.contains("diamond pays"), all);
        assertTrue(all.contains("Money supply in 30 days"), all);
    }

    @Test
    void simulationsNeverWriteThePriceSnapshot() throws IOException {
        Files2 f = setup();
        AuditSettings s = AuditSettings.defaults(f.root);
        s.snapshotFile = f.root.resolve("snap.tsv");
        WhatIf.run(WhatIf.parse(new String[]{"reward", "bank_interest", "0.5"}), s, f, ServerFacts.none(), null);
        assertFalse(Files.exists(s.snapshotFile));
    }

    @Test
    void wrongArguments() {
        assertThrows(IllegalArgumentException.class, () -> WhatIf.parse(new String[]{"price", "BASALT", "up", "3"}));
        assertThrows(IllegalArgumentException.class, () -> WhatIf.parse(new String[]{"bank", "gold", "online", "lots"}));
        assertThrows(IllegalArgumentException.class, () -> WhatIf.parse(new String[]{"teleport", "a", "b"}));
    }
}
