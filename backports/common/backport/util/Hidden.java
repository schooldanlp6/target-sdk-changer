package backport.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Access to non-public framework members that backports need.
 *
 * Backports run inside the framework, so this always works on the phone;
 * it's only reflection because the compile-time stubs hide these members.
 * Every lookup returns null instead of throwing, so a backport can fall
 * back to something simpler on ROMs where the member doesn't exist.
 */
public final class Hidden {
    private Hidden() {}

    public static Constructor<?> constructor(Class<?> cls, Class<?>... params) {
        try {
            Constructor<?> c = cls.getDeclaredConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static Method method(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
