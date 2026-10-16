package backport;

import java.lang.annotation.*;

/**
 * Adds a public static final field named {@link #value()} to the
 * {@link Backport} target. The annotated static method takes no parameters
 * and returns the field's value; it runs once, at the end of the target's
 * static initialiser.
 *
 * Only needed for object constants; int/long/String constants are copied
 * into apps at compile time and never looked up on the phone.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface InjectField {
    String value();
}
