package dev.yanianz.bravenspawner.econ;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Small, fail-closed reflection helpers for optional plugin APIs.
 *
 * <p>Classes are loaded through the target plugin's own class loader so the
 * lookup does not depend on Paper's cross-plugin class visibility rules. Every
 * failure is reported as an exception the caller turns into "unavailable".
 */
public final class Reflection {

    private Reflection() {}

    /** The enabled plugin with the first matching name, or null. */
    public static Plugin enabledPlugin(String... names) {
        try {
            for (String name : names) {
                Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
                if (plugin != null && plugin.isEnabled()) return plugin;
            }
        } catch (RuntimeException ignored) {
            // A partially initialised host behaves as "not installed".
        }
        return null;
    }

    public static Class<?> load(Plugin owner, String className) throws ClassNotFoundException {
        ClassLoader loader = owner == null ? Reflection.class.getClassLoader() : owner.getClass().getClassLoader();
        return Class.forName(className, true, loader);
    }

    /** A public method declared by {@code type} or one of its public supertypes. */
    public static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        return type.getMethod(name, parameters);
    }

    /** Like {@link #method} but null when absent. */
    public static Method optional(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /** Invokes and unwraps the target's own exception. */
    public static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause() == null ? wrapped : wrapped.getCause();
            throw new IllegalStateException(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        } catch (IllegalAccessException denied) {
            throw new IllegalStateException("access denied: " + method, denied);
        }
    }

    public static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    public static boolean asBoolean(Object value) {
        return value instanceof Boolean flag && flag;
    }
}
