package forge.web;

import forge.gui.UiCommand;
import forge.gui.interfaces.IButton;
import forge.gui.interfaces.IWinLoseView;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.util.Localizer;
import forge.web.ToBrowser.PackCard;
import forge.web.ToBrowser.RewardStep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Quest's result view, recording what QuestWinLoseController shows as reward steps and the button text it sets as the result's buttons. */
final class WebQuestView implements IWinLoseView<WebQuestView.Button> {
    /** A button nobody sees: what the controller sets on it is read back to build the result's buttons. */
    static final class Button implements IButton {
        private final String defaultText;
        private String text;
        private boolean visible;
        private boolean enabled;

        Button(final String defaultText) {
            this.defaultText = defaultText;
            reset();
        }

        void reset() {
            text = defaultText;
            visible = true;
            enabled = true;
        }

        @Override public String getText() { return text; }
        @Override public void setText(final String text0) { text = text0; }
        @Override public boolean isVisible() { return visible; }
        @Override public void setVisible(final boolean b0) { visible = b0; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void setEnabled(final boolean b0) { enabled = b0; }
        @Override public boolean isSelected() { return false; }
        @Override public void setSelected(final boolean b0) { }
        @Override public boolean requestFocusInWindow() { return false; }
        @Override public void setCommand(final UiCommand command0) { }
        @Override public void setImage(final FSkinProp color) { }
        @Override public void setTextColor(final int r, final int g, final int b) { }
        @Override public String getToolTipText() { return null; }
        @Override public void setToolTipText(final String s0) { }
    }

    private final Button continueButton = new Button(Localizer.getInstance().getMessage("btnContinue"));
    private final Button restartButton = new Button(Localizer.getInstance().getMessage("btnRestart"));
    private final Button quitButton = new Button(Localizer.getInstance().getMessage("lblQuit"));
    /** Every step the match's games showed, in order; a match's rewards are shown together once it is left. */
    final List<RewardStep> steps = new ArrayList<>();
    private Runnable script;

    /** Each game's result sets the buttons afresh, as desktop's window is made anew for each game. */
    void newGame() {
        continueButton.reset();
        restartButton.reset();
        quitButton.reset();
        script = null;
    }

    /** The rest of the controller's work, once; it is run after the result has been sent so its questions come after the result screen. */
    Runnable takeScript() {
        final Runnable r = script;
        script = null;
        return r;
    }

    @Override public Button getBtnContinue() { return continueButton; }
    @Override public Button getBtnRestart() { return restartButton; }
    @Override public Button getBtnQuit() { return quitButton; }

    @Override
    public void hide() {
    }

    @Override
    public void showRewards(final Runnable runnable) {
        script = runnable;
    }

    @Override
    public void showCards(final String title, final List<PaperCard> cards) {
        final List<PackCard> shown = cards.stream().map(c -> new PackCard(c.getName(), c.getImageKey(false), c.getRarity().name(), 0)).toList();
        // Cards lost to ante are shown with the rest, marked so the browser can set them apart
        final boolean lost = title.equals(Localizer.getInstance().getMessage("lblLootedLostAnteCard"));
        steps.add(new RewardStep("CARDS", 0, lost ? "lost" : null, shown, 0, 0, false, null, null, null, title, null, null));
    }

    @Override
    public void showMessage(final String message, final String title, final FSkinProp icon) {
        // The controller writes for desktop's label: line breaks, and underlining around an alternate win's name
        final List<String> lines = Arrays.stream(message.replaceAll("</?u>", "").split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        steps.add(new RewardStep("MESSAGE", 0, null, null, 0, 0, false, null, null, icon == null ? null : icon.name(), title,
                lines.size() == 1 ? lines.get(0) : null, lines.size() == 1 ? null : lines));
    }
}
