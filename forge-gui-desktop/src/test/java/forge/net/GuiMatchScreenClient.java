package forge.net;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;

import forge.gui.interfaces.IGuiGame;
import forge.util.IHasForgeLog;

/**
 * A remote player whose GUI is the real desktop match screen.
 *
 * <p>The batch's usual client answers prompts and draws nothing, so no amount of it exercises
 * the paths a snapshot-fed client differs on most: laying out a match, building a field per
 * player, painting a zone. Those are where the state a client is sent is finally read, and a
 * defect there is invisible to every headless game.
 *
 * <p>The match screen cannot simply be used as-is, because it is built for someone to look at:
 * the methods that ask a question open a dialog and wait, and nobody is there to answer. So the
 * questions are answered here and everything else - all the display, all the state - goes to
 * the real screen. {@link forge.screens.match.CMatchUI} is final, which rules out overriding,
 * hence a proxy.
 */
final class GuiMatchScreenClient implements IHasForgeLog {

    /**
     * The methods that return a value without asking anyone anything.
     *
     * <p>Everything else that returns a value opens a dialog, so the split cannot be made on
     * the return type alone. Naming the harmless ones is safe in the direction that matters: a
     * question mistakenly forwarded hangs the game, while an accessor mistakenly answered here
     * only loses a detail.
     */
    private static final Set<String> ANSWERED_BY_THE_SCREEN = Set.of(
            "getGameView", "getGamestate", "isSelecting", "isGamePaused", "getGameSpeed",
            "getDayTime", "isUiSetToSkipPhase", "isNetGame", "openZones", "getGameController",
            "getOriginalGameController", "equals", "hashCode", "toString");

    private GuiMatchScreenClient() { }

    /**
     * A GUI that draws through the desktop match screen and answers its own dialogs.
     *
     * @param screen the real match screen, from the desktop GUI factory
     * @param answers a headless GUI used purely for its default answers
     */
    static IGuiGame wrap(final IGuiGame screen, final IGuiGame answers,
            final java.util.function.BiConsumer<Method, Object[]> observer) {
        return (IGuiGame) Proxy.newProxyInstance(
                GuiMatchScreenClient.class.getClassLoader(),
                new Class<?>[] { IGuiGame.class },
                (proxy, method, args) -> {
                    final boolean toScreen = method.getReturnType() == void.class
                            || ANSWERED_BY_THE_SCREEN.contains(method.getName());
                    final Object result;
                    try {
                        result = method.invoke(toScreen ? screen : answers, args);
                    } catch (final InvocationTargetException e) {
                        throw e.getCause();
                    }
                    // After the screen has drawn it, because a real player answers what is on
                    // screen, and answering first would race the thing under test.
                    observer.accept(method, args);
                    return result;
                });
    }
}
