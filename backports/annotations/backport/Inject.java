package backport;

import java.lang.annotation.*;

/**
 * Adds a public instance method to the {@link Backport} target.
 *
 * Write it as a static method whose first parameter is the target object:
 * {@code @Inject static void setFoo(TextView self, int foo)} becomes
 * {@code TextView.setFoo(int)}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Inject {
}
