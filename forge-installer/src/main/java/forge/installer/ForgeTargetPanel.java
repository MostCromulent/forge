package forge.installer;

import com.izforge.izpack.api.data.Panel;
import com.izforge.izpack.api.resource.Resources;
import com.izforge.izpack.gui.IzPanelLayout;
import com.izforge.izpack.gui.log.Log;
import com.izforge.izpack.installer.data.GUIInstallData;
import com.izforge.izpack.installer.gui.InstallerFrame;
import com.izforge.izpack.panels.target.TargetPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.io.File;

public class ForgeTargetPanel extends TargetPanel {

    private static final long serialVersionUID = 1L;

    private static final String EXPLANATION =
            "Installing on top of a previous copy of Forge can leave files from the previous "
            + "version in place. This can result in conflicts, loading errors, and crashes.\n\n"
            + "Your saved decks, preferences, quest progress, and downloaded card pictures are "
            + "stored in a separate location and will NOT be deleted.";

    private static final String INTERNAL_DATA_WARNING =
            "Your forge.profile.properties points user data inside this folder. "
            + "Uncheck the box and back up your data manually before installing, "
            + "or change the install path.";

    private JPanel warningBlock;
    private JLabel headlineLabel;
    private JCheckBox wipeCheckbox;
    private JLabel internalDataWarningLabel;

    public ForgeTargetPanel(final Panel panel, final InstallerFrame parent,
                            final GUIInstallData installData, final Resources resources, final Log log) {
        super(panel, parent, installData, resources, log);
    }

    @Override
    public void createLayoutBottom() {
        super.createLayoutBottom();
        buildWarningBlock();
        add(warningBlock, IzPanelLayout.getDefaultConstraint(FULL_LINE_CONTROL_CONSTRAINT));
        attachPathListener();
    }

    @Override
    public void panelActivate() {
        super.panelActivate();
        refreshDetection();
    }

    @Override
    public boolean isValidated() {
        final boolean ok = super.isValidated();
        if (ok) {
            writeWipeVariable();
        }
        return ok;
    }

    private void buildWarningBlock() {
        warningBlock = new JPanel();
        warningBlock.setLayout(new BoxLayout(warningBlock, BoxLayout.Y_AXIS));
        warningBlock.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
                BorderFactory.createEmptyBorder(8, 4, 4, 4)));
        warningBlock.setAlignmentX(Component.LEFT_ALIGNMENT);

        headlineLabel = new JLabel("A previous Forge installation was detected here.");
        headlineLabel.setFont(headlineLabel.getFont().deriveFont(Font.BOLD));
        headlineLabel.setForeground(new Color(0xB8, 0x60, 0x00));
        headlineLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        warningBlock.add(headlineLabel);
        warningBlock.add(Box.createVerticalStrut(6));

        wipeCheckbox = new JCheckBox("Delete previous Forge files before installing (recommended)");
        wipeCheckbox.setSelected(true);
        wipeCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        wipeCheckbox.addActionListener(e -> writeWipeVariable());
        warningBlock.add(wipeCheckbox);
        warningBlock.add(Box.createVerticalStrut(6));

        final JTextArea explanation = new JTextArea(EXPLANATION);
        explanation.setEditable(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setOpaque(false);
        explanation.setBorder(null);
        explanation.setFont(UIManager.getFont("Label.font"));
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        warningBlock.add(explanation);
        warningBlock.add(Box.createVerticalStrut(6));

        internalDataWarningLabel = new JLabel("<html><body style='width: 480px'>"
                + INTERNAL_DATA_WARNING.replace("\n", "<br>")
                + "</body></html>");
        internalDataWarningLabel.setForeground(new Color(0xC0, 0x00, 0x00));
        internalDataWarningLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        internalDataWarningLabel.setVisible(false);
        warningBlock.add(internalDataWarningLabel);

        warningBlock.setVisible(false);
    }

    private void attachPathListener() {
        if (pathSelectionPanel == null || pathSelectionPanel.getPathInputField() == null) {
            return;
        }
        pathSelectionPanel.getPathInputField().getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(final DocumentEvent e)  { refreshDetection(); }
            @Override public void removeUpdate(final DocumentEvent e)  { refreshDetection(); }
            @Override public void changedUpdate(final DocumentEvent e) { refreshDetection(); }
        });
    }

    private void refreshDetection() {
        final String path = getPath();
        if (path == null || path.isEmpty()) {
            setWarningVisible(false, false);
            writeWipeVariable();
            return;
        }
        final File dir = new File(path);
        final boolean priorInstall = ForgePriorInstallDetector.detect(dir);
        final boolean internalData = priorInstall && ForgePriorInstallDetector.hasInternalUserData(dir);
        setWarningVisible(priorInstall, internalData);
        if (internalData && wipeCheckbox != null) {
            wipeCheckbox.setSelected(false);
        }
        writeWipeVariable();
    }

    private void setWarningVisible(final boolean show, final boolean internalDataWarning) {
        if (warningBlock != null) {
            warningBlock.setVisible(show);
        }
        if (internalDataWarningLabel != null) {
            internalDataWarningLabel.setVisible(show && internalDataWarning);
        }
        revalidate();
        repaint();
    }

    private void writeWipeVariable() {
        final boolean wipe = warningBlock != null && warningBlock.isVisible()
                && wipeCheckbox != null && wipeCheckbox.isSelected();
        installData.setVariable(ForgeCleanInstallListener.WIPE_VARIABLE, Boolean.toString(wipe));
    }
}
