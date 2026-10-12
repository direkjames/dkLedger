package com.direk.dkledger.platform;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * For hooks into plugins whose API dkLedger doesn't compile against (EconomyShopGUI, AOneBlock): their events
 * are found by class name and read with their getters. If a plugin renames something, that hook just stays
 * quiet instead of breaking dkLedger.
 */
public final class Reflect {

    private Reflect() {
    }

    /** Calls the first of these no-argument methods that exists. Null if none does or it fails. */
    public static Object call(Object target, String... methods) {
        if (target == null) return null;
        for (String name : methods) {
            try {
                Method m = target.getClass().getMethod(name);
                m.trySetAccessible(); // the class itself may not be public
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // try the next name
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }
        return null;
    }

    /** Calls a one-argument method. Null if it doesn't exist or fails. */
    public static Object call(Object target, String method, Class<?> type, Object arg) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method, type);
            m.trySetAccessible();
            return m.invoke(target, arg);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    public static String text(Object o) {
        if (o == null) return "";
        if (o instanceof Enum<?> e) return e.name();
        return String.valueOf(o);
    }

    public static double number(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    /** Loads a class with a plugin's (or addon's) class loader, or null if it isn't there. */
    public static Class<? extends Event> eventClass(ClassLoader loader, String className) {
        try {
            Class<?> c = Class.forName(className, true, loader);
            return Event.class.isAssignableFrom(c) ? c.asSubclass(Event.class) : null;
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * Listens to an event class found by name, at MONITOR, ignoring cancelled events. The handler's errors are
     * caught so one odd event can't spam the console.
     */
    public static <L extends Listener> void listen(Plugin owner, L listener, Class<? extends Event> type, Consumer<Event> handler) {
        Bukkit.getPluginManager().registerEvent(type, listener, EventPriority.MONITOR, (l, event) -> {
            if (!type.isInstance(event)) return; // subclasses share the handler list
            try {
                handler.accept(event);
            } catch (RuntimeException | LinkageError e) {
                owner.getLogger().fine("Skipped a " + type.getSimpleName() + ": " + e);
            }
        }, owner, true);
    }
}
