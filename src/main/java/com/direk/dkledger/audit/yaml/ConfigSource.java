package com.direk.dkledger.audit.yaml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Reads a YAML file into plain maps and lists. The plugin uses SnakeYAML; tests can use anything. */
@FunctionalInterface
public interface ConfigSource {

    /**
     * @return the file's top-level map (empty for an empty file)
     * @throws IOException if the file can't be read or isn't valid YAML
     */
    Map<String, Object> load(Path file) throws IOException;
}
