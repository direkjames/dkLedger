package dev.direk.dkledger.audit;

import dev.direk.dkledger.audit.model.RecipeModel;
import dev.direk.dkledger.audit.yaml.ConfigSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A fake plugins folder for tests: files exist on disk (so folder lookups work) but their content
 * comes from maps, so tests don't need a YAML library.
 */
final class TestPlugins implements ConfigSource {

    final Path root;
    private final Map<Path, Map<String, Object>> files = new HashMap<>();

    TestPlugins() throws IOException {
        root = Files.createTempDirectory("dkledger-test");
    }

    TestPlugins put(String relative, Map<String, Object> content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# test");
        files.put(file.toAbsolutePath().normalize(), content);
        return this;
    }

    @Override
    public Map<String, Object> load(Path file) throws IOException {
        Map<String, Object> content = files.get(file.toAbsolutePath().normalize());
        if (content == null) throw new IOException("not a test file: " + file);
        return content;
    }

    AuditResult audit(ServerFacts server) {
        return Auditor.run(settings(), this, server);
    }

    AuditSettings settings() {
        return AuditSettings.defaults(root);
    }

    /** A map from key, value, key, value... */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** An EconomyShopGUI shop with items given as material, buy, sell, material, buy, sell... */
    static Map<String, Object> shop(Object... items) {
        Map<String, Object> list = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i += 3) {
            list.put(String.valueOf(i / 3 + 1), map("material", items[i], "buy", items[i + 1], "sell", items[i + 2]));
        }
        return map("pages", map("page1", map("items", list)));
    }

    static RecipeModel recipe(String type, String result, int amount, Object... countThenChoices) {
        List<RecipeModel.Ingredient> ingredients = new java.util.ArrayList<>();
        for (int i = 0; i < countThenChoices.length; i += 2) {
            ingredients.add(new RecipeModel.Ingredient(Arrays.asList(((String) countThenChoices[i + 1]).split("\\|")),
                    (Integer) countThenChoices[i]));
        }
        return new RecipeModel("test:" + result.toLowerCase(), type, result, amount, ingredients);
    }

    static ServerFacts server(List<RecipeModel> recipes, String... commands) {
        return new ServerFacts(recipes, Set.of(commands));
    }

    static List<Finding> of(AuditResult r, String check) {
        return r.findings().stream().filter(f -> f.check().equals(check)).toList();
    }

    static boolean has(AuditResult r, String check, Severity severity, String titlePart) {
        return of(r, check).stream().anyMatch(f -> f.severity() == severity && f.title().contains(titlePart));
    }
}
