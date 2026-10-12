package dev.direk.dkledger.platform;

import dev.direk.dkledger.audit.yaml.ConfigSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads other plugins' YAML files with the SnakeYAML that ships with Paper. Uses plain SnakeYAML instead of
 * Bukkit's YamlConfiguration, so serialized items ("==: org.bukkit.inventory.ItemStack") stay plain maps
 * and are never turned into real items off the main thread.
 */
public final class SnakeYamlSource implements ConfigSource {

    @Override
    public Map<String, Object> load(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(16 * 1024 * 1024);
        options.setMaxAliasesForCollections(200);
        Yaml yaml = new Yaml(new SafeConstructor(options)); // one per call: SnakeYAML isn't thread-safe
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = yaml.load(reader);
            Map<String, Object> out = new LinkedHashMap<>();
            if (root instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        } catch (YAMLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
