package forge.web;

import com.google.gson.JsonObject;

/** Where a message to the browser goes. Implementations must be safe to call from any thread. */
public interface BrowserChannel {
    void send(JsonObject message);
}
