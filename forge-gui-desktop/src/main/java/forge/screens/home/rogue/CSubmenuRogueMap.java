package forge.screens.home.rogue;

import javax.swing.SwingUtilities;
import forge.util.Localizer;

import forge.Singletons;
import forge.gamemodes.rogue.*;
import forge.gamemodes.rogue.effect.*;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.gamemodes.rogue.path.*;
import forge.gui.framework.EDocID;
import forge.gui.framework.FScreen;
import forge.gui.framework.ICDoc;
import forge.localinstance.achievements.RogueCommanderAchievements;
import forge.screens.deckeditor.CDeckEditorUI;
import forge.screens.deckeditor.controllers.CEditorRogue;
import forge.screens.deckeditor.views.VCardCatalog;
import forge.screens.home.CHomeUI;
import forge.screens.home.VHomeUI;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;
import forge.toolbox.FOptionPane;
import forge.util.MyRandom;
import java.awt.event.ActionListener;
import java.util.*;
import javax.swing.*;
import org.tinylog.Logger;

/**
 * Controls the "rogue map" submenu in the home UI.
 *
 * <br><br><i>(C at beginning of class name denotes a control class.)</i>
 */
public enum CSubmenuRogueMap implements ICDoc {
  SINGLETON_INSTANCE;

  private final RogueFlow flow = new RogueFlow(new DesktopRogueDecisions());
  private final ActionListener actEnterNode = arg0 -> flow.enter();
  private final ActionListener actEditDeck = arg0 -> editDeck();
  private final ActionListener actRerollPlane = arg0 -> rerollSelectedPlanebound();
  private final ActionListener actAbandonRun = arg0 -> abandonRun();
  private final VSubmenuRogueMap view = VSubmenuRogueMap.SINGLETON_INSTANCE;

  @Override
  public void update() {
    if (!RogueUIHelper.progressReadable()) {
      // Without a run, Back does nothing and the sidebar is the only way out
      VHomeUI.SINGLETON_INSTANCE.setSidebarVisible(true);
      return;
    }
    // If no run exists in memory, try to load from saved runs
    if (flow.run() == null) {
      flow.setRun(RogueIO.loadMostRecentContinuableRun().orElse(null));
    }
    RogueRun currentRun = flow.run();

    // If still no run (no saved runs or all completed/failed), navigate to start screen
    if (currentRun == null) {
      CHomeUI.SINGLETON_INSTANCE.itemClick(EDocID.HOME_ROGUESTART);
      return;
    }

    currentRun.getRunTimer().start();
    updateView();
    SwingUtilities.invokeLater(() -> {
      view.getBtnEnterNode().requestFocusInWindow();
      showTutorials();
    });
  }

  private void showTutorials() {
    RogueRun currentRun = flow.run();
    RogueTutorialHelper.showIfNotSeen(RogueTutorial.MAP_NAVIGATION);
    if (currentRun != null && currentRun.getEndlessLevel() > 0) {
      RogueTutorialHelper.showIfNotSeen(RogueTutorial.ENDLESS_MODE);
    }

    if (currentRun == null || currentRun.getCurrentNode() == null) {
      return;
    }

    // Check if player just won their first battle and show post-battle tutorial if so
    List<RoguePathNode> completedNodes = currentRun.getPath().getNodes().stream()
        .filter(n -> n instanceof NodePlanebound && n.isCompleted())
        .toList();
    if (!completedNodes.isEmpty()) {
      RogueTutorialHelper.showIfNotSeen(RogueTutorial.POST_BATTLE);
    }

    // Check node types of current row and show battle tutorials if not seen yet
    int currentRow = currentRun.getCurrentNode().getRowIndex();
    for (RoguePathNode node : currentRun.getPath().getNodesInRow(currentRow)) {
      if (node instanceof NodePlanebound planebound
          && planebound.getPlaneboundType() == RoguePlaneboundType.ELITE) {
        RogueTutorialHelper.showIfNotSeen(RogueTutorial.ELITE_PLANEBOUND);
      } else if (node instanceof NodePlanebound) {
        RogueTutorialHelper.showIfNotSeen(RogueTutorial.PRE_BATTLE);
      }
    }
  }

  @Override
  public void register() {
    // The map attaches its listeners in initialize().
  }

  @Override
  public void initialize() {
    view.getBtnEnterNode().addActionListener(actEnterNode);
    view.getBtnEditDeck().addActionListener(actEditDeck);
    view.getBtnRerollPlane().addActionListener(actRerollPlane);
    view.getBtnBackToHome().addActionListener(e -> backToHome());
    view.getBtnAbandonRun().addActionListener(actAbandonRun);
    view.getBtnDevWinRun().addActionListener(e -> devWinRun());
    view.getBtnDevNextNode().addActionListener(e -> devNextNode());
    view.getPathVisualizer().setNodeClickHandler(this::handleNodeClick);
  }

  /**
   * Handle click on a node panel.
   */
  private void handleNodeClick(NodePanel panel) {
    RogueRun currentRun = flow.run();
    if (currentRun == null) {
      return;
    }

    // Get the index of the clicked node
    int nodeIndex = view.getPathVisualizer().getNodePanels().indexOf(panel);
    if (nodeIndex < 0) {
      return;
    }

    RoguePathNode clickedNode = currentRun.getPath().getNode(nodeIndex);
    if (clickedNode == null) {
      return;
    }

    RoguePathNode currentNode = currentRun.getCurrentNode();
    if (currentNode == null) {
      return;
    }

    // Only allow selecting nodes in the current row
    if (clickedNode.getRowIndex() != currentNode.getRowIndex()) {
      return;
    }

    PathUpdateContext pathCtx = new PathUpdateContext();
    RogueEffectComposite.INSTANCE.onPathUpdate(pathCtx, currentRun);

    // Only allow selecting reachable nodes in the current row
    List<Integer> visibleInCurrentRow = currentRun.getPath()
        .getVisibleNodesInCurrentRow(currentNode.getRowIndex(), pathCtx);
    if (!visibleInCurrentRow.contains(nodeIndex)) {
      return; // Not reachable, ignore click
    }

    // Check if this is a multi-node row - only allow selection changes if multiple nodes
    List<RoguePathNode> currentRowNodes = currentRun.getPath()
        .getNodesInRow(currentNode.getRowIndex());
    if (currentRowNodes.size() == 1) {
      return; // Single node row - no selection needed, already auto-selected
    }

    // Allow clicking any reachable node in multi-node row to select it
    // Set as current node and update view
    currentRun.setCurrentNodeIndex(nodeIndex);
    updateViewWithSelection();
  }

  private void updateView() {
    view.updateDisplay(flow.run());
    updateViewWithSelection();
  }

  /**
   * Update the view with selection state and button text.
   */
  private void updateViewWithSelection() {
    RogueRun currentRun = flow.run();
    if (currentRun == null) {
      return;
    }

    // Update path visualizer selection
    view.getPathVisualizer().setSelectedNode(currentRun.getCurrentNodeIndex());

    boolean matchInProgress = currentRun.getHostedMatch() != null;
    view.getBtnBackToHome().setEnabled(!matchInProgress);
    view.getBtnAbandonRun().setEnabled(!matchInProgress);

    // Update button state based on current node
    RoguePathNode currentNode = currentRun.getCurrentNode();

    if (currentNode == null) {
      view.getBtnEnterNode().setEnabled(false);
      view.getBtnEnterNode().setText(Localizer.getInstance().getMessage("lblRogueNoNodeAvailable"));
      view.getBtnRerollPlane().setVisible(false);
      view.updateActionButtonLayout();
      return;
    }

    PathUpdateContext pathCtx = new PathUpdateContext();
    RogueEffectComposite.INSTANCE.onPathUpdate(pathCtx, currentRun);

    // Disable button if match already in progress (prevents duplicate match tabs)
    view.getBtnEnterNode().setEnabled(!matchInProgress);
    view.getBtnEditDeck().setEnabled(!matchInProgress);
    view.getBtnEnterNode().setText(getEnterButtonText(currentNode, pathCtx));
    updatePlaneboundRerollButton(currentNode, matchInProgress, pathCtx);
    view.updateActionButtonLayout();
  }

  /**
   * Get the appropriate button text for entering a node.
   */
  private String getEnterButtonText(RoguePathNode node, PathUpdateContext pathCtx) {
    if (node instanceof NodePlanebound nodePlanebound) {
      String planeName = pathCtx.hidePlanes && !nodePlanebound.isRevealed()
          ? "???" : nodePlanebound.getRoguePlanebound().planeName();
      return Localizer.getInstance().getMessage("lblRogueEnterPlane", planeName);
    } else if (node instanceof NodeSanctum) {
      return Localizer.getInstance().getMessage("lblRogueEnterSanctum");
    } else if (node instanceof NodeBazaar) {
      return Localizer.getInstance().getMessage("lblRogueEnterBazaar");
    } else if (node instanceof NodeEvent) {
      return Localizer.getInstance().getMessage("lblRogueEnterEvent");
    } else if (node instanceof NodeChest) {
      return Localizer.getInstance().getMessage("lblRogueOpenChest");
    } else {
      return Localizer.getInstance().getMessage("lblRogueEnterNode");
    }
  }

  private void updatePlaneboundRerollButton(RoguePathNode currentNode, boolean matchInProgress,
                                        PathUpdateContext pathCtx) {
    JButton button = view.getBtnRerollPlane();
    if (!(currentNode instanceof NodePlanebound nodePlanebound)
        || matchInProgress
        || pathCtx.remainingPlaneboundRerolls <= 0) {
      button.setVisible(false);
      button.setToolTipText(null);
    } else {
      button.setVisible(true);
      button.setText(Localizer.getInstance().getMessage("lblRogueRerollPlaneCount", pathCtx.remainingPlaneboundRerolls));
      boolean hasCandidates = !getPlaneboundRerollCandidates(nodePlanebound).isEmpty();
      button.setEnabled(hasCandidates);
      button.setToolTipText(Localizer.getInstance().getMessage(
          hasCandidates ? "lblRogueRerollPlanebound" : "lblRogueNoAlternativePlanebound"));
    }
  }

  private void rerollSelectedPlanebound() {
    RogueRun currentRun = flow.run();
    if (currentRun == null || currentRun.getHostedMatch() != null) {
      return;
    }

    RoguePathNode currentNode = currentRun.getCurrentNode();
    if (!(currentNode instanceof NodePlanebound nodePlanebound)) {
      return;
    }

    PathUpdateContext pathCtx = new PathUpdateContext();
    RogueEffectComposite.INSTANCE.onPathUpdate(pathCtx, currentRun);
    if (pathCtx.remainingPlaneboundRerolls <= 0) {
      updateViewWithSelection();
      return;
    }

    List<RoguePlanebound> candidates = getPlaneboundRerollCandidates(nodePlanebound);
    if (candidates.isEmpty()) {
      updateViewWithSelection();
      return;
    }

    Collections.shuffle(candidates, MyRandom.getRandom());
    nodePlanebound.setRoguePlanebound(candidates.get(0));
    nodePlanebound.setRevealed(false);
    RogueEffectComposite.INSTANCE.onPathNodeReroll(pathCtx, currentRun);
    RogueIO.saveRun(currentRun);
    updateView();
  }

  private List<RoguePlanebound> getPlaneboundRerollCandidates(NodePlanebound selectedNode) {
    RogueRun currentRun = flow.run();
    if (currentRun == null || currentRun.getPath() == null || selectedNode == null) {
      return List.of();
    }

    RoguePathNode currentNode = currentRun.getCurrentNode();
    if (currentNode == null) {
      return List.of();
    }

    int currentRow = currentNode.getRowIndex();
    Set<RoguePlanebound> excludedPlanebounds = new HashSet<>();
    for (RoguePathNode node : currentRun.getPath().getNodes()) {
      if (!(node instanceof NodePlanebound planebound)) {
        continue;
      }

      boolean isCurrentRow = node.getRowIndex() == currentRow;
      boolean isFutureRow = node.getRowIndex() > currentRow;
      boolean isRevealedPreviousRow = node.getRowIndex() < currentRow && planebound.isRevealed();
      if (isCurrentRow || isFutureRow || isRevealedPreviousRow) {
        excludedPlanebounds.add(planebound.getRoguePlanebound());
      }
    }

    int planeboundRow = currentRun.getPath().countPlaneboundRowsUpTo(selectedNode.getRowIndex());
    List<RoguePlanebound> candidates = new ArrayList<>(RogueConfig.loadPlanebounds());
    candidates.removeIf(planebound -> planebound.type() != selectedNode.getPlaneboundType()
        || planebound.minRow() > planeboundRow
        || excludedPlanebounds.contains(planebound));
    return candidates;
  }

  public void continueEndlessly() {
    RogueRun currentRun = flow.run();
    if (currentRun == null || !currentRun.isRunComplete() || currentRun.isRunFailed()
        || currentRun.isRunWon()
        || !RogueMetaProgress.getInstance().isEndlessModeUnlocked()) {
      return;
    }

    RogueMetaProgress progress = RogueMetaProgress.getInstance();
    currentRun.setEndlessLevel(currentRun.getEndlessLevel() + 1);
    currentRun.setCurrentNodeIndex(0);
    RoguePathGenerator.generateRandomPath(currentRun);
    CSubmenuRogueStart.SINGLETON_INSTANCE.applyRunStartEffectsAndNpcBoon(currentRun, progress);
    RogueIO.saveRun(currentRun);
  }

  public RogueRun getCurrentRun() {
    return flow.run();
  }

  void setCurrentRun(RogueRun run) {
    flow.setRun(run);
  }

  public void clearCurrentRun() {
    flow.clearRun();
  }

  private void devNextNode() {
    RogueRun currentRun = flow.run();
    if (currentRun == null) return;
    RoguePathNode node = currentRun.getCurrentNode();
    if (node == null) return;

    node.setCompleted(true);
    var progress = RogueMetaProgress.getInstance();
    if (node instanceof NodePlanebound nodePlanebound) {
      RogueStats.fireOnMatchCompleted(currentRun, progress, true);
      currentRun.addGold(nodePlanebound.getGoldReward());
      progress.addEchoes(nodePlanebound.getEchoReward());
    } else {
      RogueStats.fireOnSideNodeCompleted(currentRun, progress);
    }
    // Last node — treat as win
    if (currentRun.getCurrentNodeIndex() >= currentRun.getPath().getNodeCount() - 1) {
      devWinRun();
      return;
    }

    currentRun.nextNode();
    RogueIO.saveRun(currentRun);
    update();
  }

  private void devWinRun() {
    RogueRun currentRun = flow.run();
    if (currentRun == null) return;
    String commanderName = currentRun.getCurrentCommanderName();
    currentRun.setRunWon(true);
    currentRun.getRunTimer().stop();
    RogueMetaProgress progress = RogueMetaProgress.getInstance();
    progress.addEchoes(20);
    progress.addRunHistoryEntry(RogueRunHistoryEntry.fromRun(currentRun, "VICTORY", "[DEV]"));
    RogueStats.fireOnRunCompleted(currentRun, progress, true);
    RogueCommanderAchievements.getInstance().recordRunWon(commanderName);
    progress.recordDescensionWin(currentRun);
    RogueCommanderAchievements.getInstance().evaluateRunAchievements(currentRun);
    progress.notifyDescensionL1IfFirstWin(currentRun);
    RogueIO.saveRun(currentRun);
    flow.clearRun();
    CHomeUI.SINGLETON_INSTANCE.itemClick(EDocID.HOME_ROGUESTART);
  }

  private void backToHome() {
    RogueRun currentRun = flow.run();
    if (currentRun == null || currentRun.getHostedMatch() != null) {
      return;
    }

    RogueRunTimer timer = currentRun.getRunTimer();
    timer.stop();
    try {
      RogueIO.saveRun(currentRun);
    } catch (RuntimeException e) {
      timer.start();
      Logger.error(e, "Could not save Rogue Commander run before returning home");
      FOptionPane.showErrorDialog(Localizer.getInstance().getMessage("lblSavefailed"));
      return;
    }
    CHomeUI.SINGLETON_INSTANCE.itemClick(EDocID.HOME_ROGUESTART);
  }

  private void abandonRun() {
    RogueRun currentRun = flow.run();
    if (currentRun == null || currentRun.getHostedMatch() != null) {
      return;
    }

    boolean confirmed = FOptionPane.showConfirmDialog(
        Localizer.getInstance().getMessage("lblRogueAbandonRunConfirm"),
        Localizer.getInstance().getMessage("lblRogueAbandonRun"),
        Localizer.getInstance().getMessage("lblRogueAbandonRun"),
        Localizer.getInstance().getMessage("lblCancel"),
        false);
    if (!confirmed) {
      return;
    }

    currentRun.getRunTimer().stop();
    RogueMetaProgress.getInstance().addRunHistoryEntry(
        RogueRunHistoryEntry.fromRun(currentRun, "ABANDONED", ""));
    RogueIO.deleteRun(currentRun);
    SoundSystem.instance.play(SoundEffectType.LoseDuel, SoundEffectType.LoseDuel.isSynced());
    flow.clearRun();
    CHomeUI.SINGLETON_INSTANCE.itemClick(EDocID.HOME_ROGUESTART);
  }

  public void editDeck() {
    RogueRun currentRun = flow.run();
    if (currentRun == null || currentRun.getHostedMatch() != null) {
      return;
    }

    CDeckEditorUI deckEditor = CDeckEditorUI.SINGLETON_INSTANCE;
    deckEditor.setEditorController(new CEditorRogue(currentRun, FScreen.DECK_EDITOR_ROGUE,
        deckEditor.getCDetailPicture()));
    if (!Singletons.getControl().setCurrentScreen(FScreen.DECK_EDITOR_ROGUE)) {
      return;
    }

    SwingUtilities.invokeLater(() -> {
      VCardCatalog catalog = VCardCatalog.SINGLETON_INSTANCE;
      if (catalog.getParentCell() != null) {
        catalog.getParentCell().setSelected(catalog);
      }
      RogueTutorialHelper.showIfNotSeen(RogueTutorial.DECK_EDITOR);
    });
  }
}
