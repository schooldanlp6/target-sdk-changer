package backport;

import java.lang.annotation.*;

/**
 * Adds a public constructor to the {@link Backport} target.
 *
 * The new constructor first calls the existing constructor whose parameter
 * descriptor is {@link #delegate()} (its parameters must be the first
 * parameters of the new one), then calls this static method with the new
 * object and all arguments:
 *
 * <pre>
 * &#64;InjectConstructor(delegate = "(III)V")
 * static void init(VolumeProvider self, int control, int max, int current, String id)
 * </pre>
 * becomes {@code new VolumeProvider(int, int, int, String)}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface InjectConstructor {
    String delegate();
}
