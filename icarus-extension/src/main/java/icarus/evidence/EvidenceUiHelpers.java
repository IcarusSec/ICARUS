package icarus.evidence;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

public final class EvidenceUiHelpers {

    public static JScrollPane createSmoothScrollPane(Component c) {
        JScrollPane scroll = new JScrollPane(c);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    public static JButton createModernButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.darker(), 1),
            new EmptyBorder(8, 16, 8, 16)
        ));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public static JTextArea createStyledTextArea(String text) {
        JTextArea ta = new JTextArea(text);
        ta.setFont(EvidenceImageRenderer.MONO_FONT);
        ta.setBackground(EvidenceImageRenderer.BG_COLOR);
        ta.setForeground(EvidenceImageRenderer.TEXT_COLOR);
        ta.setCaretColor(Color.WHITE);
        ta.setMargin(new Insets(10, 15, 10, 15));
        ta.setTabSize(4);

        javax.swing.undo.UndoManager undoManager = new javax.swing.undo.UndoManager();
        ta.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        InputMap im = ta.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = ta.getActionMap();

        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), "Undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, ctrl), "Redo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl | InputEvent.SHIFT_DOWN_MASK), "Redo");

        am.put("Undo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) undoManager.undo();
            }
        });
        am.put("Redo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) undoManager.redo();
            }
        });

        return ta;
    }

    public static void attachSmartContextMenu(JTextArea ta) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem itmTruncate = new JMenuItem("Truncate Selection");
        itmTruncate.addActionListener(e -> replaceSelection(ta, "... [Truncated for Evidence] ..."));

        JMenuItem itmRedact = new JMenuItem("Redact Selection");
        itmRedact.addActionListener(e -> replaceSelection(ta, "[REDACTED]"));

        JMenuItem itmRemoveLine = new JMenuItem("Remove Current Line");
        itmRemoveLine.addActionListener(e -> removeCurrentLine(ta));

        menu.add(itmTruncate);
        menu.add(itmRedact);
        menu.addSeparator();
        menu.add(itmRemoveLine);

        ta.setComponentPopupMenu(menu);
    }

    public static void addCweChip(JPanel pnlChips, List<String> selectedCwe, String cweId) {
        if (selectedCwe.contains(cweId)) return;
        selectedCwe.add(cweId);

        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setBackground(new Color(60, 60, 60));
        chip.setBorder(BorderFactory.createLineBorder(EvidenceImageRenderer.SEPARATOR_COLOR));

        JLabel lbl = new JLabel(cweId);
        lbl.setForeground(EvidenceImageRenderer.TEXT_COLOR);

        JButton remove = new JButton("×");
        remove.setMargin(new Insets(0, 4, 0, 4));
        remove.setFocusable(false);
        remove.addActionListener(e -> {
            selectedCwe.remove(cweId);
            pnlChips.remove(chip);
            pnlChips.revalidate();
            pnlChips.repaint();
        });

        chip.add(lbl);
        chip.add(remove);
        pnlChips.add(chip);
        pnlChips.revalidate();
        pnlChips.repaint();
    }

    public static void replaceSelection(JTextArea ta, String replacement) {
        if (ta.getSelectedText() != null) {
            ta.replaceSelection(replacement);
        }
    }

    public static void removeCurrentLine(JTextArea ta) {
        try {
            int caret = ta.getCaretPosition();
            int line = ta.getLineOfOffset(caret);
            int start = ta.getLineStartOffset(line);
            int end = ta.getLineEndOffset(line);
            ta.getDocument().remove(start, end - start);
        } catch (BadLocationException ex) {
            // ignore
        }
    }

    public static String cleanNoise(String text) {
        String[] noisyHeaders = {
            "Accept-Language:", "Accept-Encoding:", "Connection:", "Upgrade-Insecure-Requests:",
            "Sec-Fetch-", "Sec-Ch-Ua"
        };
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            boolean isNoise = false;
            for (String noise : noisyHeaders) {
                if (line.toLowerCase().startsWith(noise.toLowerCase())) {
                    isNoise = true;
                    break;
                }
            }
            if (!isNoise) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
