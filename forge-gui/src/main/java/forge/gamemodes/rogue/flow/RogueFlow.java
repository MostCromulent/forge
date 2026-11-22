package forge.gamemodes.rogue.flow;

import forge.gamemodes.rogue.RogueIO;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueStats;
import forge.gamemodes.rogue.RogueWinLoseController;
import forge.gamemodes.rogue.effect.DefeatContext;
import forge.gamemodes.rogue.effect.RogueEffectComposite;
import forge.gamemodes.rogue.flow.RogueDecisions.ResultKind;
import forge.gamemodes.rogue.path.NodeBazaar;
import forge.gamemodes.rogue.path.NodeChest;
import forge.gamemodes.rogue.path.NodeEvent;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gamemodes.rogue.path.NodeSanctum;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.util.Localizer;
import java.util.List;

/** A client's current Rogue Commander run, and the steps of playing it. */
public final class RogueFlow {
    private final RogueDecisions decisions;
    private final NodeBazaarHelper nodeBazaarHelper = new NodeBazaarHelper(this);
    private final NodeChestHelper nodeChestHelper = new NodeChestHelper(this);
    private final NodePlaneboundHelper nodePlaneboundHelper = new NodePlaneboundHelper(this);
    private final NodeSanctumHelper nodeSanctumHelper = new NodeSanctumHelper(this);
    private final NodeEffectResultHelper nodeEffectResultHelper =
        new NodeEffectResultHelper(this, nodeBazaarHelper, nodeChestHelper, nodeSanctumHelper, nodePlaneboundHelper);
    private final NodeEventHelper nodeEventHelper = new NodeEventHelper(this, nodeEffectResultHelper);

    private RogueRun currentRun;

    public RogueFlow(RogueDecisions decisions) {
        this.decisions = decisions;
        nodeChestHelper.setEffectResultHelper(nodeEffectResultHelper);
    }

    RogueDecisions decisions() {
        return decisions;
    }

    public RogueRun run() {
        return currentRun;
    }

    public void setRun(RogueRun run) {
        this.currentRun = run;
    }

    public void clearRun() {
        currentRun = null;
    }

    public void enter() {
        if (currentRun == null) {
            return;
        }

        RoguePathNode node = currentRun.getCurrentNode();

        if (node == null) {
            return;
        }

        if (node instanceof NodePlanebound nodePlanebound) {
            nodePlaneboundHelper.handlePlaneboundNode(nodePlanebound, currentRun);
        } else if (node instanceof NodeSanctum nodeSanctum) {
            nodeSanctumHelper.handleSanctumNode(nodeSanctum, currentRun);
        } else if (node instanceof NodeBazaar nodeBazaar) {
            nodeBazaarHelper.handleBazaarNode(nodeBazaar, currentRun);
        } else if (node instanceof NodeEvent nodeEvent) {
            nodeEventHelper.handleEventNode(nodeEvent, currentRun);
        } else if (node instanceof NodeChest nodeChest) {
            nodeChestHelper.handleChestNode(nodeChest, currentRun);
        }
    }

    public void completeSideNode(RoguePathNode node) {
        node.setCompleted(true);
        currentRun.nextNode();
        RogueStats.fireOnSideNodeCompleted(currentRun, RogueMetaProgress.getInstance());
        RogueIO.saveRun(currentRun);
        decisions.mapChanged(currentRun);
    }

    public boolean checkSideNodeDefeat(String defeatedBy) {
        if (currentRun == null || currentRun.getCurrentLife() > 0) {
            return false;
        }

        DefeatContext defeatCtx = new DefeatContext();
        RogueEffectComposite.INSTANCE.onDefeat(defeatCtx, currentRun);
        if (defeatCtx.revived) {
            currentRun.setCurrentLife(defeatCtx.reviveLife);
            decisions.showResult(ResultKind.REVIVE,
                Localizer.getInstance().getMessage("lblRogueContinuumEngine"),
                Localizer.getInstance().getMessage("lblRogueContinuumRevive", defeatCtx.reviveLife),
                List.of());
            return false;
        }

        if (RogueWinLoseController.finalizeRunDefeat(currentRun, defeatedBy)) {
            decisions.showMessage(Localizer.getInstance().getMessage("lblRogueSparkReward"),
                Localizer.getInstance().getMessage("lblRogueSparkWon"));
        }
        showSideNodeDefeat(defeatedBy);
        currentRun = null;
        decisions.runEnded();
        return true;
    }

    private void showSideNodeDefeat(String defeatedBy) {
        String message = defeatedBy == null || defeatedBy.isEmpty()
            ? Localizer.getInstance().getMessage("lblRogueDefeated")
            : Localizer.getInstance().getMessage("lblRogueDefeatedBy", defeatedBy);
        decisions.showResult(ResultKind.DEFEAT, Localizer.getInstance().getMessage("lblRogueDefeat"),
            message, List.of());
    }
}
