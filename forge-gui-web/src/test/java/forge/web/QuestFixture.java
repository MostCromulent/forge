package forge.web;

import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gamemodes.quest.io.QuestDataIO;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/** Quests copied in for a test, in the profile of whoever runs it, and removed afterwards. */
final class QuestFixture {
    private static final List<String> made = new ArrayList<>();
    private static String currentBefore;

    private QuestFixture() {
    }

    /** The fixture save, gzipped as Quest writes it, under the name stored inside it. */
    private static byte[] packed(final String name, final boolean classic) throws IOException {
        final String xml;
        try (InputStream in = QuestFixture.class.getResourceAsStream("/quest/Fixture_quest.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream zip = new GZIPOutputStream(bytes)) {
            // A save writes to the file its stored name gives, so a copy keeping the old name would write beside it
            final String named = xml.replace("<name>Fixture quest</name>", "<name>" + name + "</name>");
            // The mode has no setter, so a Classic quest is the same save read as one
            zip.write((classic ? named.replace("<mode>Fantasy</mode>", "<mode>Classic</mode>") : named).getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /** Copies the saved quest into the profile under a new name and reads it: Fantasy, Main world, 250 credits, a Plant and a Wolf at level 1, "Forest deck" current. */
    static QuestData install() throws IOException {
        return install(false);
    }

    /** The same quest in Classic mode, which has no pets, no bazaar and no challenges. */
    static QuestData installClassic() throws IOException {
        return install(true);
    }

    private static QuestData install(final boolean classic) throws IOException {
        if (currentBefore == null) {
            currentBefore = FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST);
        }
        final String name = "Quest test " + UUID.randomUUID().toString().substring(0, 8);
        final Path file = Path.of(ForgeConstants.QUEST_SAVE_DIR, name + ".dat");
        Files.createDirectories(file.getParent());
        Files.write(file, packed(name, classic));
        made.add(name);
        return QuestDataIO.loadData(file.toFile());
    }

    /** A name a test is about to have a quest made or renamed to, whose files are removed afterwards. */
    static String expected() {
        if (currentBefore == null) {
            currentBefore = FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST);
        }
        final String name = "Quest test " + UUID.randomUUID().toString().substring(0, 8);
        made.add(name);
        return name;
    }

    static void cleanUp() throws IOException {
        FModel.getQuest().setCurrentEvent(null);
        FModel.getQuest().load(null);
        for (final String name : made) {
            Files.deleteIfExists(Path.of(ForgeConstants.QUEST_SAVE_DIR, name + ".dat"));
            Files.deleteIfExists(Path.of(ForgeConstants.QUEST_SAVE_DIR, name + ".dat.bak"));
        }
        made.clear();
        if (currentBefore != null) {
            final QuestPreferences prefs = FModel.getQuestPreferences();
            prefs.setPref(QPref.CURRENT_QUEST, currentBefore);
            prefs.save();
            currentBefore = null;
        }
    }
}
