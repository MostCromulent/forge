package forge.web.adventure;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.DialogData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.Current;
import forge.adventure.util.Reward;
import forge.adventure.world.WorldSave;
import forge.card.ColorSet;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.web.protocol.CampaignProtocol.AdventureChoice;
import forge.web.protocol.CampaignProtocol.AdventureDialog;
import forge.web.protocol.CampaignProtocol.AdventureDialogClosed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Quest dialogs, shown in the browser one at a time. AdventureQuestController queues them, as it does for the mobile client. The
 * conditions and actions are copied from MapDialog, whose scene2d dialog the server cannot build. Every call runs on Forge's GUI thread.
 */
final class Dialogs implements IHasForgeLog {
    private final Consumer<Record> out;
    private final Deque<DialogData> queue = new ArrayDeque<>();
    /** The page shown now, or null. */
    private DialogData page;
    private AdventureDialog shown;
    private int nextId = 1;
    /** Takes rewards a dialog grants. */
    private Consumer<Array<Reward>> rewards = r -> r.forEach(AdventurePlayer.current()::addReward);

    Dialogs(final Consumer<Record> out) {
        this.out = out;
    }

    void onRewards(final Consumer<Array<Reward>> rewards) {
        this.rewards = rewards;
    }

    boolean isOpen() {
        return shown != null;
    }

    /** The page shown now, sent again to a browser that reconnects, or null. */
    AdventureDialog current() {
        return shown;
    }

    /** Takes the quest dialogs AdventureQuestController queued and shows the first unless one is open. */
    void collect() {
        AdventureQuestController.instance().showQuestDialogs(null);
        for (DialogData d = AdventureQuestController.instance().pollDialog(); d != null; d = AdventureQuestController.instance().pollDialog()) {
            queue.add(d);
        }
        if (shown == null) {
            next();
        }
    }

    /** Shows a dialog now, ahead of any waiting. */
    void open(final DialogData dialog) {
        queue.addFirst(dialog);
        if (shown == null) {
            next();
        }
    }

    // Ported from AdventureQuestController.displayNextDialog and MapDialog.activate
    private void next() {
        while (!queue.isEmpty()) {
            final DialogData data = queue.poll();
            if (data.options == null || data.options.length == 0) {
                setEffects(data.action);
                continue;
            }
            if (isConditionOk(data.condition) && load(data)) {
                return;
            }
        }
    }

    /** The player picked an option of the page with this id. A choice for an older page, or of an option not offered, does nothing. */
    void choose(final int dialogId, final int index) {
        if (shown == null || shown.id() != dialogId || index < 0 || index >= page.options.length) {
            return;
        }
        final DialogData option = page.options[index];
        if (option.isDisabled || !isConditionOk(option.condition)) {
            return;
        }
        if (!load(option)) {
            next();
        }
    }

    // Ported from MapDialog.loadDialog. Returns whether a page with choices is now shown
    private boolean load(final DialogData dialog) {
        setEffects(dialog.action);
        if (dialog.options.length == 0 && dialog.text.isEmpty() && dialog.action.length > 0) {
            close();
            return false;
        }
        final Localizer text = Localizer.getInstance();
        final List<AdventureChoice> choices = new ArrayList<>();
        for (int i = 0; i < dialog.options.length; i++) {
            final DialogData option = dialog.options[i];
            if (isConditionOk(option.condition)) {
                final String label = option.locname != null && !option.locname.isEmpty() ? text.getMessageorUseDefault(option.locname, option.name) : option.name;
                choices.add(new AdventureChoice(i, label, option.isDisabled));
            }
        }
        if (choices.isEmpty()) {
            close();
            return false;
        }
        final String body = dialog.loctext != null && !dialog.loctext.isEmpty() ? text.getMessageorUseDefault(dialog.loctext, dialog.text) : dialog.text;
        page = dialog;
        shown = new AdventureDialog(nextId++, body, choices);
        out.accept(shown);
        return true;
    }

    private void close() {
        if (shown != null) {
            shown = null;
            page = null;
            out.accept(new AdventureDialogClosed());
        }
    }

    /** The open point of interest's changes, which hold its map flags and reputation, or null on the world map. */
    private static PointOfInterestChanges mapChanges() {
        final PointOfInterest map = AdventureHost.get().mapPoint();
        return map == null ? null : WorldSave.getCurrentSave().getPointOfInterestChanges(map.getID());
    }

    // Copied from MapDialog.setEffects. Actions on map objects need a town map, which the browser cannot show yet
    private void setEffects(final DialogData.ActionData[] data) {
        if (data == null) {
            return;
        }
        final AdventurePlayer player = Current.player();
        for (final DialogData.ActionData e : data) {
            if (e == null) {
                continue;
            }
            if (e.removeItem != null && !e.removeItem.isEmpty()) {
                player.removeItem(e.removeItem);
            }
            if (e.addItem != null && !e.addItem.isEmpty()) {
                player.addItem(e.addItem);
            }
            if (e.addLife != 0) {
                player.heal(e.addLife);
            }
            if (e.addGold != 0) {
                if (e.addGold > 0) {
                    player.giveGold(e.addGold);
                } else {
                    player.takeGold(-e.addGold);
                }
            }
            if (e.addShards != 0) {
                if (e.addShards > 0) {
                    player.addShards(e.addShards);
                } else {
                    player.takeShards(-e.addShards);
                }
            }
            if (e.addMapReputation != 0) {
                if (e.POIReference != null && !e.POIReference.isEmpty() && !e.POIReference.contains("$")) {
                    WorldSave.getCurrentSave().getPointOfInterestChanges(e.POIReference).addMapReputation(e.addMapReputation);
                } else if (mapChanges() != null) {
                    mapChanges().addMapReputation(e.addMapReputation);
                }
            }
            if (e.deleteMapObject != 0 || e.activateMapObject != 0 || e.battleWithActorID != 0 || e.setEffect != null) {
                netLog.warn("A dialog action needs a town map, which the browser cannot show yet, so it was skipped");
            }
            if (e.giveBlessing != null) {
                player.addBlessing(e.giveBlessing);
            }
            if (e.setColorIdentity != null && !e.setColorIdentity.isEmpty()) {
                player.setColorIdentity(e.setColorIdentity);
            }
            if (e.setCharacterFlag != null && !e.setCharacterFlag.key.isEmpty()) {
                player.setCharacterFlag(e.setCharacterFlag.key, e.setCharacterFlag.val);
            }
            if (e.advanceCharacterFlag != null && !e.advanceCharacterFlag.isEmpty()) {
                player.advanceCharacterFlag(e.advanceCharacterFlag);
            }
            if (e.setQuestFlag != null && !e.setQuestFlag.key.isEmpty()) {
                player.setQuestFlag(e.setQuestFlag.key, e.setQuestFlag.val);
            }
            if (e.advanceQuestFlag != null && !e.advanceQuestFlag.isEmpty()) {
                player.advanceQuestFlag(e.advanceQuestFlag);
            }
            final PointOfInterestChanges map = mapChanges();
            // Ported from MapStage.setQuestFlag and advanceQuestFlag, which keep map flags on the point of interest's changes
            if (e.setMapFlag != null && !e.setMapFlag.key.isEmpty() && map != null) {
                map.getMapFlags().put(e.setMapFlag.key, (byte) e.setMapFlag.val);
                AdventureQuestController.instance().updateQuestsMapFlag(e.setMapFlag.key, e.setMapFlag.val);
            }
            if (e.advanceMapFlag != null && !e.advanceMapFlag.isEmpty() && map != null) {
                map.getMapFlags().merge(e.advanceMapFlag, (byte) 1, (a, b) -> (byte) (a + b));
                AdventureQuestController.instance().updateQuestsMapFlag(e.advanceMapFlag, map.getMapFlags().get(e.advanceMapFlag));
            }
            if (e.grantRewards != null && e.grantRewards.length > 0) {
                final Array<Reward> granted = new Array<>();
                for (final RewardData reward : e.grantRewards) {
                    granted.addAll(reward.generate(false, true));
                }
                rewards.accept(granted);
            }
            if (e.grantRewardsChoice != null && e.grantRewardsChoice.length > 0) {
                netLog.warn("A dialog offered a choice of rewards, which the browser cannot show yet, so it was skipped");
            }
            if (e.issueQuest != null && !e.issueQuest.isEmpty()) {
                player.addQuest(e.issueQuest, false);
            }
        }
    }

    // Copied from MapDialog.isConditionOk. Without a town map, an actor never exists and a map flag is never set
    private static boolean isConditionOk(final DialogData.ConditionData[] data) {
        if (data == null) {
            return true;
        }
        final AdventurePlayer player = Current.player();
        final PointOfInterestChanges map = mapChanges();
        for (final DialogData.ConditionData condition : data) {
            if (condition.item != null && !condition.item.isEmpty()) {
                if (!player.hasItem(condition.item)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.colorIdentity != null && !condition.colorIdentity.isEmpty()) {
                if (player.getColorIdentity().hasAllColors(ColorSet.fromNames(condition.colorIdentity.toCharArray()).getColor())) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasGold != 0) {
                if (player.getGold() < condition.hasGold) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasShards != 0) {
                if (player.getShards() < condition.hasShards) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasLife != 0) {
                if (player.getLife() < condition.hasLife + 1) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasMapReputation != Integer.MIN_VALUE) {
                if ((map == null ? 0 : map.getMapReputation()) < condition.hasMapReputation + 1) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.hasBlessing != null && !condition.hasBlessing.isEmpty()) {
                if (!player.hasBlessing(condition.hasBlessing)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.actorID != 0 && !condition.not) {
                return false;
            }
            if (condition.getCharacterFlag != null) {
                final String key = condition.getCharacterFlag.key;
                if (!player.checkCharacterFlag(key)) return false;
                if (!checkFlagCondition(player.getCharacterFlag(key), condition.getCharacterFlag.op, condition.getCharacterFlag.val)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.checkCharacterFlag != null && !condition.checkCharacterFlag.isEmpty()) {
                if (!player.checkCharacterFlag(condition.checkCharacterFlag)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.getQuestFlag != null) {
                final String key = condition.getQuestFlag.key;
                if (!player.checkQuestFlag(key)) return false;
                if (!checkFlagCondition(player.getQuestFlag(key), condition.getQuestFlag.op, condition.getQuestFlag.val)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.checkQuestFlag != null && !condition.checkQuestFlag.isEmpty()) {
                if (!player.checkQuestFlag(condition.checkQuestFlag)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.getMapFlag != null) {
                final String key = condition.getMapFlag.key;
                if (map == null || map.getMapFlags().get(key) == null) return false;
                if (!checkFlagCondition(map.getMapFlags().getOrDefault(key, (byte) 0), condition.getMapFlag.op, condition.getMapFlag.val)) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
            if (condition.checkMapFlag != null && !condition.checkMapFlag.isEmpty()) {
                if (map == null || map.getMapFlags().get(condition.checkMapFlag) == null) {
                    if (!condition.not) return false;
                } else if (condition.not) return false;
            }
        }
        return true;
    }

    // Copied from MapDialog.checkFlagCondition
    private static boolean checkFlagCondition(final int flag, final String condition, final int value) {
        if (condition == null) {
            return false;
        }
        return switch (condition.toUpperCase()) {
            case "EQUALS", "EQUAL", "=" -> flag == value;
            case "LESSTHAN", "<" -> flag < value;
            case "MORETHAN", ">" -> flag > value;
            case "LE_THAN", "<=" -> flag <= value;
            case "ME_THAN", ">=" -> flag >= value;
            default -> false;
        };
    }
}
