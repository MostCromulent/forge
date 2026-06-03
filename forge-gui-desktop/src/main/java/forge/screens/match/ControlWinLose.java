package forge.screens.match;

import javax.swing.JButton;

import forge.Singletons;
import forge.game.GameView;
import forge.game.VoteChoice;
import forge.game.VoteKind;
import forge.gamemodes.match.VoteTally;
import forge.gui.FThreads;
import forge.gui.SOverlayUtils;
import forge.gui.framework.FScreen;
import forge.interfaces.IGameController;

/** 
 * Default controller for a ViewWinLose object. This class can
 * be extended for various game modes to populate the custom
 * panel in the win/lose screen.
 * 
 */
public class ControlWinLose {
    private final ViewWinLose view;
    protected final GameView lastGame;
    protected final CMatchUI matchUI;

    /** @param v &emsp; ViewWinLose
     * @param match */
    public ControlWinLose(final ViewWinLose v, final GameView game0, final CMatchUI matchUI) {
        this.view = v;
        this.lastGame = game0;
        this.matchUI = matchUI;
        matchUI.setActiveWinLose(this);
        addListeners();
    }

    /** */
    public void addListeners() {
        view.getBtnContinue().addActionListener(e -> actionOnContinue());

        view.getBtnRestart().addActionListener(e -> actionOnRestart());

        view.getBtnQuit().addActionListener(e -> {
            actionOnQuit();
            ((JButton) e.getSource()).setEnabled(false);
        });
    }

    /** Action performed when "continue" button is pressed in default win/lose UI. */
    public void actionOnContinue() {
        castNextGameVote(VoteChoice.CONTINUE);
    }

    /** Action performed when "restart" button is pressed in default win/lose UI. */
    public void actionOnRestart() {
        castNextGameVote(VoteChoice.NEW);
    }

    /** Action performed when "quit" button is pressed in default win/lose UI. */
    public void actionOnQuit() {
        saveOptions();
        SOverlayUtils.hideOverlay();
        for (final IGameController controller : matchUI.getOriginalGameControllers()) {
            controller.castVote(VoteKind.NEXT_GAME, VoteChoice.QUIT);
        }
        Singletons.getControl().setCurrentScreen(FScreen.HOME_SCREEN);
    }

    /**
     * Submit a continue/new-match vote and keep the screen up until every player has voted, so the
     * live tally is visible. The overlay is torn down once the vote settles (see {@link #updateNextGameTally}).
     */
    private void castNextGameVote(final VoteChoice choice) {
        saveOptions();
        view.setNextGameButtonsEnabled(false);
        for (final IGameController controller : matchUI.getOriginalGameControllers()) {
            controller.castVote(VoteKind.NEXT_GAME, choice);
        }
    }

    /** Render the live next-game tally; once the vote settles, dismiss the screen so the match can proceed. */
    public void updateNextGameTally(final VoteTally update) {
        FThreads.invokeInEdtNowOrLater(() -> {
            if (update.outcome() != null) {
                SOverlayUtils.hideOverlay();
            } else {
                view.renderVoteTally(update.tallyLines());
            }
        });
    }

    /**
     * Either continues or restarts a current game. May be overridden for use
     * with other game modes.
     */
    public void saveOptions() {
        matchUI.writeMatchPreferences();
    }

    /**
     * <p>
     * populateCustomPanel.
     * </p>
     * May be overridden as required by controllers for various game modes
     * to show custom information in center panel. Default configuration is empty.
     *
     * @return boolean, panel has contents or not.
     */
    public boolean populateCustomPanel() {
        return false;
    }

    /** @return ViewWinLose object this controller is in charge of */
    public ViewWinLose getView() {
        return this.view;
    }
}
