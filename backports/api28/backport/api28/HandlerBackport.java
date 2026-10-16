package backport.api28;

import android.os.Handler;
import android.os.Looper;
import backport.Backport;
import backport.InjectStatic;
import backport.util.Hidden;
import java.lang.reflect.Constructor;

/** android.os.Handler, API 28. */
@Backport("android.os.Handler")
public final class HandlerBackport {
    private HandlerBackport() {}

    /** Handler(Looper, Callback, boolean async) has been @hide since API 17. */
    private static final Constructor<?> ASYNC_CTOR =
            Hidden.constructor(Handler.class, Looper.class, Handler.Callback.class, boolean.class);

    @InjectStatic
    public static Handler createAsync(Looper looper) {
        return createAsync(looper, null);
    }

    @InjectStatic
    public static Handler createAsync(Looper looper, Handler.Callback callback) {
        if (looper == null) throw new NullPointerException("looper must not be null");
        if (ASYNC_CTOR != null) {
            try {
                return (Handler) ASYNC_CTOR.newInstance(looper, callback, true);
            } catch (ReflectiveOperationException ignored) {
                // fall through: a synchronous handler still delivers every message
            }
        }
        return new Handler(looper, callback);
    }
}
