package forge.web.protocol;

import forge.web.protocol.ToBrowser.CatalogueRow;
import forge.web.protocol.MessageJson.Command;
import forge.web.protocol.MessageJson.Message;
import forge.web.protocol.MessageJson.Nullable;

import java.util.List;
import java.util.Map;

/** The messages every campaign mode shares, in both directions. Each mode's own are in a class of their own, so a build can leave a mode out. */
public final class CampaignProtocol {
    private CampaignProtocol() {
    }

    /** Campaign commands that are only their name. */
    public enum CampaignPlain { campaignLeave, rewardClaim, trading, campaignPrefs, campaignPrefsReset }

    @Command
    public record CampaignBare(CampaignPlain t) {
    }

    /** Opens a campaign mode's saved games. With resume, it opens the save played last directly. */
    @Command("campaignOpen")
    public record CampaignOpen(String mode, boolean resume) {
    }

    @Command("campaignLoad")
    public record CampaignLoad(String name) {
    }

    @Command("campaignRename")
    public record CampaignRename(String name, String to) {
    }

    @Command("campaignDelete")
    public record CampaignDelete(String name) {
    }

    /** Asks for a campaign's statistics. scope narrows them to one scope the mode offers. Null asks for all of them. */
    @Command("campaignStats")
    public record CampaignStatsQuery(@Nullable String scope) {
    }

    /** Sets one of a mode's preferences. key is the key its row was sent with. */
    @Command("campaignPref")
    public record CampaignPref(String key, String value) {
    }

    /** A card picked from one of a campaign's lists. key is its image key, which names a printing. */
    public record TradePick(String key, int count) {
    }

    /** Trades the picks out of a list. source is the list's name, the same name a catalogue query uses. */
    @Command("trade")
    public record Trade(String source, List<TradePick> picks) {
    }

    /** One of a campaign's currencies: its skin icon's name, the amount the player has, and a text name for a reader who cannot see the icon. */
    public record Balance(String icon, int amount, String label) {
    }

    /** What the bar above every campaign page shows: the save's name, a line under it, and its balances. fresh names the new items on each tab, by tab. */
    @Message("campaignBar")
    public record CampaignBar(String name, String line, List<Balance> balances, Map<String, List<String>> fresh) {
    }

    /**
     * A button of the result screen. action is nextGame, leave, restart, quit, forfeit or the mode's own. quit leaves a match that is not over.
     * forfeit is a quit the player must confirm first. One of the mode's own leaves the match with that action as the choice.
     * disabled is why the button cannot be pressed.
     */
    public record ResultButton(String label, String action, boolean primary, @Nullable String disabled) {
    }

    /** A game of a campaign's match ended. line is the text under the result screen's heading, and detail a second line under it, if the mode has any. */
    @Message("campaignResult")
    public record CampaignResult(boolean won, boolean matchOver, @Nullable String line, @Nullable String detail, List<ResultButton> buttons) {
    }

    /** A statistics figure: its amount, and the total it is out of when it is a share. */
    public record Figure(String label, int amount, @Nullable Integer of) {
    }

    public record StatTable(List<String> headers, List<List<String>> rows) {
    }

    /** scopes lists what the figures can be narrowed to. scope is the one they are for, or null for all. allScopes is the label for all. */
    @Message("campaignStats")
    public record CampaignStats(String title, List<Figure> figures, List<StatTable> tables, List<String> scopes, @Nullable String scope,
            @Nullable String allScopes) {
    }

    /** A preference. column, when given, says which of several values in one row this is, as with a difficulty's own value. */
    public record PrefRow(String key, String label, String group, String value, @Nullable String column) {
    }

    /** A mode's preferences. Each is saved when it changes. note says whose they are. problem says why the last change was refused. */
    @Message("campaignPrefs")
    public record CampaignPrefs(String title, List<PrefRow> rows, String note, @Nullable String problem, boolean canReset) {
    }

    /** One of a campaign's lists of cards: the source used to ask for its cards, and how many cards it holds. */
    public record TradeList(String source, int count) {
    }

    /** The lists a campaign trades between, a line about what a trade pays, and any other ways the lists can be narrowed. */
    @Message("trading")
    public record Trading(List<TradeList> lists, String note, @Nullable List<String> groups, @Nullable List<Product> products,
            @Nullable List<CatalogueRow> extras) {
    }

    /** Something for sale that is not a card, such as a booster, a box or a deck. Its key is "p:" plus its name. A trade's pick uses that key. */
    public record Product(String key, String kind, String name, String image, int price) {
    }

    /** A card from a booster. shards is what a duplicate was turned into, or 0 for a new card. */
    public record PackCard(String name, String image, String rarity, int shards) {
    }

    /** One step a reward shows. kind is the mode's name for it, and icon is the balance it pays into. MESSAGE and CARDS steps carry their own text. outcome "lost" marks cards lost to ante.
     *  packImage is a BOOSTER step's booster picture, for a pack from one set. */
    public record RewardStep(String kind, int amount, @Nullable String outcome, @Nullable List<PackCard> cards,
            int number, int total, boolean chaos, @Nullable String pack, @Nullable String art, @Nullable String icon,
            @Nullable String title, @Nullable String text, @Nullable List<String> lines, @Nullable String packImage) {
    }

    /** What a match gave. It is already saved, and the browser shows it step by step. */
    @Message("reward")
    public record Reward(List<RewardStep> steps) {
    }
}
