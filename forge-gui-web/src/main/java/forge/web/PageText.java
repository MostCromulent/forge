package forge.web;

import com.google.gson.JsonObject;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.tinylog.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The page's text, in the language Forge is set to: the patterns of the keys the page uses, from Forge's language
 * files, with English standing in for any a translation lacks. The page formats them itself (i18n.ts), by the same
 * rules as {@link forge.util.Localizer}, so the text of the page and the text the game sends it are in one language.
 */
final class PageText {
    /** The keys the page uses, listed by {@link ProtocolTypes} when it reads them from the TypeScript. */
    static final String KEYS_RESOURCE = "/forge/web/page-text.keys";

    private static byte[] json;

    private PageText() {
    }

    /** The page's text as the page asks for it: {lang, text: {key: pattern}}. Worked out once, on first use. */
    static synchronized byte[] json() {
        if (json == null) {
            final String lang = FModel.getPreferences().getPref(FPref.UI_LANGUAGE);
            final Properties english = load("en-US");
            final Properties chosen = "en-US".equals(lang) ? english : load(lang);
            final JsonObject text = new JsonObject();
            for (final String key : keys(english)) {
                final String pattern = chosen.getProperty(key, english.getProperty(key));
                if (pattern != null) {
                    text.addProperty(key, pattern);
                }
            }
            final JsonObject out = new JsonObject();
            out.addProperty("lang", lang);
            out.add("text", text);
            json = JsonCodec.GSON.toJson(out).getBytes(StandardCharsets.UTF_8);
        }
        return json;
    }

    /** The keys the page uses; every English key, if the list was never written, as when run without Maven. */
    private static List<String> keys(final Properties english) {
        try (InputStream in = PageText.class.getResourceAsStream(KEYS_RESOURCE)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(k -> !k.isBlank()).toList();
            }
        } catch (final IOException e) {
            Logger.warn(e, "Could not read the page's keys");
        }
        return new ArrayList<>(english.stringPropertyNames());
    }

    static Properties load(final String lang) {
        final Properties p = new Properties();
        final Path file = Path.of(ForgeConstants.LANG_DIR, lang + ".properties");
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (final IOException e) {
            Logger.warn(e, "Could not read the language file " + file);
        }
        return p;
    }
}
