package backport;

import java.lang.annotation.*;

/**
 * Marks a class whose annotated static methods get added to {@link #value()},
 * an existing framework class, e.g. {@code @Backport("android.os.Handler")}.
 *
 * The class itself is added to the framework unchanged; the patcher adds a
 * small bridge method to the target that calls into it.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Backport {
    String value();
}
