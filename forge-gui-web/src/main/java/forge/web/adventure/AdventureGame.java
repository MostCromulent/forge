package forge.web.adventure;

import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.world.WorldSave;
import forge.adventure.world.WorldSaveHeader;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.game.GameView;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.web.campaign.Campaign;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.AdventureCreate;
import forge.web.protocol.CampaignProtocol.AdventureQuickSave;
import forge.web.protocol.CampaignProtocol.AdventureSave;
import forge.web.protocol.CampaignProtocol.AdventureSaveTo;
import forge.web.protocol.CampaignProtocol.AdventureSaves;
import forge.web.protocol.CampaignProtocol.CampaignBar;
import forge.web.protocol.CampaignProtocol.CampaignPrefs;
import forge.web.protocol.CampaignProtocol.CampaignResult;
import forge.web.protocol.CampaignProtocol.CampaignStats;
import forge.web.protocol.CampaignProtocol.Reward;
import forge.web.protocol.CampaignProtocol.TradePick;
import forge.web.protocol.CampaignProtocol.Trading;
import forge.web.protocol.Commands;
import forge.web.protocol.Commands.Handler;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.ToBrowser.CataloguePage;
import forge.web.protocol.ToBrowser.Notice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * Adventure as a campaign mode. The saves are the mobile client's, in the same folder, named by file. Every call into the adventure
 * model runs on Forge's GUI thread, because the model loads its pictures through Assets.
 */
public final class AdventureGame implements Campaign, IHasForgeLog {
    private final WebAdventureHost screens = new WebAdventureHost();

    public AdventureGame() {
        AdventureRuntime.start();
        AdventureHost.set(screens);
    }

    @Override
    public AdventureSaves saves() {
        final List<AdventureSave> rows = new ArrayList<>();
        final File[] files = new File(WorldSave.getSaveDir()).listFiles((dir, name) -> WorldSave.isSafeFile(name));
        if (files != null) {
            for (final File file : files) {
                final String name = headerName(file);
                if (name != null) {
                    rows.add(new AdventureSave(file.getName(), WorldSave.filenameToSlot(file.getName()), name, Campaign.savedOn(file)));
                }
            }
        }
        rows.sort(Comparator.comparingInt(AdventureSave::slot));
        return new AdventureSaves(rows, current());
    }

    /** The name the save was given, or null when the file cannot be read. */
    private static String headerName(final File file) {
        try (FileInputStream in = new FileInputStream(file);
             InflaterInputStream inflater = new InflaterInputStream(in);
             ObjectInputStream objects = new ObjectInputStream(inflater)) {
            final WorldSaveHeader header = (WorldSaveHeader) objects.readObject();
            header.dispose();
            return header.name;
        } catch (final IOException | ClassNotFoundException | ClassCastException e) {
            return null;
        }
    }

    @Override
    public String current() {
        final String last = Config.instance().getSettingData().lastActiveSave;
        return last != null && WorldSave.isSafeFile(last) && new File(WorldSave.getSaveDir(), last).isFile() ? last : null;
    }

    @Override
    public String open(final String save) {
        if (save == null || !WorldSave.isSafeFile(save) || !Campaign.isSaveNamed(WorldSave.getSaveDir(), new File(WorldSave.getSaveDir(), save), save)) {
            return null;
        }
        return AdventureRuntime.onUi(() -> WorldSave.load(WorldSave.filenameToSlot(save))) ? save : null;
    }

    /** A save's name is its slot's, so only the text shown in it could change. The page does not offer this. */
    @Override
    public String rename(final String name, final String to) {
        return Localizer.getInstance().getMessage("lblWebAdventureNoRename");
    }

    @Override
    public void delete(final String name) {
        final File file = new File(WorldSave.getSaveDir(), name);
        if (WorldSave.isSafeFile(name) && Campaign.isSaveNamed(WorldSave.getSaveDir(), file, name) && !file.delete()) {
            netLog.warn("Could not delete the adventure save {}", file);
        }
    }

    @Override
    public List<Record> page() {
        return List.of(AdventureRuntime.onUi(this::bar));
    }

    private CampaignBar bar() {
        final AdventurePlayer player = AdventurePlayer.current();
        return new CampaignBar(player.getName(), Config.instance().getPlane(), List.of(), Map.of());
    }

    @Override
    public Reward reward() {
        return null;
    }

    @Override
    public void claim(final Host host) {
    }

    @Override
    public CampaignResult gameOver(final GameView hostGame) {
        return null;
    }

    @Override
    public CampaignResult result() {
        return null;
    }

    @Override
    public void left() {
    }

    @Override
    public CataloguePage cards(final CatalogueQuery q) {
        return new CataloguePage(q.request(), List.of(), 0, 0, 0, false, q.source());
    }

    @Override
    public Trading trading() {
        return new Trading(List.of(), "", null, null, null);
    }

    @Override
    public String trade(final String source, final List<TradePick> picks) {
        return null;
    }

    @Override
    public CampaignStats stats(final String scope) {
        return new CampaignStats("", List.of(), List.of(), List.of(), null, null);
    }

    @Override
    public CampaignPrefs prefs(final String problem) {
        return new CampaignPrefs("", List.of(), "", problem, false);
    }

    @Override
    public String setPref(final String key, final String value) {
        return null;
    }

    @Override
    public void resetPrefs() {
    }

    // Copied from NewGameScene.start
    private static void create(final AdventureCreate c) {
        final DifficultyData[] difficulties = Config.instance().getConfigData().difficulties;
        final DifficultyData difficulty = difficulties[Math.max(0, Math.min(c.difficulty(), difficulties.length - 1))];
        final CardEdition edition = c.edition() == null ? null : FModel.getMagicDb().getEditions().get(c.edition());
        WorldSave.generateNewWorld(c.name(), !c.female(), c.race(), c.avatar(), ColorSet.fromNames(c.colors().toCharArray()), difficulty,
                AdventureModes.valueOf(c.mode()), c.customDeck(), edition, c.seed());
        GamePlayerUtil.getGuiPlayer().setName(c.name());
        if (AdventurePlayer.current().getQuests().stream().noneMatch(q -> q.getID() == 28)) {
            AdventurePlayer.current().addQuest("28", true);
        }
        WorldSave.getCurrentSave().autoSave();
    }

    private static boolean saveTo(final int slot) {
        return AdventureRuntime.onUi(() -> WorldSave.getCurrentSave().save(AdventurePlayer.current().getName() + AdventureHost.get().saveFileSuffix(), slot));
    }

    private static void reportSave(final BrowserChannel channel, final Host host, final boolean saved) {
        final Localizer text = Localizer.getInstance();
        channel.send(saved ? new Notice(null, text.getMessage("lblWebAdventureSaved"), false) : host.error(text.getMessage("lblWebAdventureSaveFailed")));
    }

    private record Call(AdventureGame game, String save, Host host) {
    }

    private static <T extends Record> Handler<Call, T> onShelf(final Handler<Call, T> handler) {
        return (c, ch, command) -> {
            if (c.save() == null) {
                handler.run(c, ch, command);
            }
        };
    }

    private static <T extends Record> Handler<Call, T> inSave(final Handler<Call, T> handler) {
        return (c, ch, command) -> {
            if (c.save() != null) {
                handler.run(c, ch, command);
            }
        };
    }

    public static final Commands<Call> COMMANDS = new Commands<Call>()
            .on(AdventureCreate.class, onShelf((c, ch, create) -> {
                try {
                    AdventureRuntime.onUi(() -> {
                        create(create);
                        return null;
                    });
                } catch (final IllegalArgumentException e) {
                    ch.send(c.host().error(e.getMessage()));
                    return;
                }
                c.host().opened(WorldSave.filename(WorldSave.AUTO_SAVE_SLOT));
            }))
            .on(AdventureSaveTo.class, inSave((c, ch, s) -> reportSave(ch, c.host(), s.slot() >= 0 && saveTo(s.slot()))))
            .on(AdventureQuickSave.class, inSave((c, ch, s) -> reportSave(ch, c.host(), saveTo(WorldSave.QUICK_SAVE_SLOT))));

    @Override
    public void handle(final BrowserChannel channel, final Record command, final String save, final Host host) {
        COMMANDS.run(new Call(this, save, host), channel, command);
    }
}
