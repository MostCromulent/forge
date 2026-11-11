package forge.web.adventure;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import forge.gui.FThreads;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Starts libgdx without a window, so the mobile adventure model can read its files and pictures in the server. */
public final class AdventureRuntime {
    private static boolean started;

    private AdventureRuntime() {}

    public static synchronized void start() {
        if (started) {
            return;
        }
        final HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 10;
        new HeadlessApplication(new ApplicationAdapter() { }, config);
        if (Gdx.gl == null) {
            Gdx.gl = Gdx.gl20 = NoGl.create();
        }
        started = true;
    }

    /** Runs job on Forge's GUI thread and returns its result. The adventure model loads its pictures through Assets, which allows only that thread. */
    public static <T> T onUi(final Supplier<T> job) {
        final AtomicReference<T> result = new AtomicReference<>();
        FThreads.invokeInEdtAndWait(() -> result.set(job.get()));
        return result.get();
    }
}
