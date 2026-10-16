package backport;

import java.lang.annotation.*;

/**
 * Adds a public static method to the {@link Backport} target, with the same
 * name and parameters: {@code @InjectStatic static Handler createAsync(Looper l)}
 * becomes {@code Handler.createAsync(Looper)}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface InjectStatic {
}
