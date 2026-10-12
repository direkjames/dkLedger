package com.direk.dkledger.ledger;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out which plugin is moving money by walking the call stack: the first class that belongs to a plugin
 * other than the ones that only pass money along (dkLedger, Vault, dkCore). A plugin added later is found the
 * same way, so nothing has to be hooked by hand.
 */
public final class SourceResolver {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /** Class loader -> plugin name ("" = not a plugin, e.g. the server). */
    private final Map<ClassLoader, String> names = new ConcurrentHashMap<>();
    private final Set<String> passThrough;

    /** @param passThrough plugins that only pass money along, lower case (dkLedger, Vault, dkCore) */
    public SourceResolver(Set<String> passThrough) {
        this.passThrough = passThrough;
    }

    /** The plugin calling right now, skipping pass-through plugins and {@code skipAlso}, if any. */
    public Optional<String> caller(String... skipAlso) {
        Set<String> skip = skipAlso.length == 0 ? Set.of() : Set.of(lower(skipAlso));
        return WALKER.walk(frames -> frames
                .map(f -> plugin(f.getDeclaringClass()))
                .filter(n -> !n.isEmpty())
                .filter(n -> !passThrough.contains(n.toLowerCase(Locale.ROOT)) && !skip.contains(n.toLowerCase(Locale.ROOT)))
                .findFirst());
    }

    /** The plugin a class belongs to, or "" for server and library classes. */
    public String plugin(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        if (loader == null) return "";
        return names.computeIfAbsent(loader, l -> {
            if (l instanceof ConfiguredPluginClassLoader configured) {
                try {
                    return configured.getConfiguration().getName();
                } catch (RuntimeException ignored) {
                    // fall through
                }
            }
            try {
                return JavaPlugin.getProvidingPlugin(type).getName();
            } catch (IllegalArgumentException | IllegalStateException e) {
                return "";
            }
        });
    }

    private static String[] lower(String[] names) {
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) out[i] = names[i].toLowerCase(Locale.ROOT);
        return out;
    }
}
