package forge.web.adventure;

import com.badlogic.gdx.graphics.GL20;

import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A GL that draws nothing. The adventure model builds textures, atlases and shaders while it loads, and the headless backend has
 * no GL, so these calls must succeed without one. Pictures the server needs are read from the files, never from a texture.
 */
final class NoGl {
    private NoGl() {}

    static GL20 create() {
        final AtomicInteger names = new AtomicInteger();
        return (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] {GL20.class}, (proxy, method, args) -> {
            final String name = method.getName();
            if (name.startsWith("glGen") || name.startsWith("glCreate")) {
                if (method.getReturnType() == int.class) {
                    return names.incrementAndGet();
                }
                if (args != null && args.length > 1 && args[1] instanceof IntBuffer ids) {
                    while (ids.hasRemaining()) {
                        ids.put(names.incrementAndGet());
                    }
                }
                return null;
            }
            // Compile, link and framebuffer status checks read 1 as success
            if ((name.equals("glGetShaderiv") || name.equals("glGetProgramiv")) && args[2] instanceof IntBuffer out) {
                out.put(out.position(), 1);
                return null;
            }
            if (name.equals("glCheckFramebufferStatus")) {
                return GL20.GL_FRAMEBUFFER_COMPLETE;
            }
            if (name.equals("glGetIntegerv") && args[1] instanceof IntBuffer out) {
                out.put(out.position(), 4096);
                return null;
            }
            final Class<?> type = method.getReturnType();
            if (type == String.class) {
                return "";
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == float.class) {
                return 0f;
            }
            if (type == long.class) {
                return 0L;
            }
            return null;
        });
    }
}
