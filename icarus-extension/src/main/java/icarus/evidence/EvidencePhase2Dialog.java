package icarus.evidence;

import icarus.core.Finding;
import icarus.core.EvidencePaths;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import icarus.evidence.EvidenceCapture.CapturedEvidence;

public class EvidencePhase2Dialog {
    private final EvidenceCapture owner;
    public EvidencePhase2Dialog(EvidenceCapture owner) {
        this.owner = owner;
    }

    public void showPhase2(JFrame parentEditor, Finding finding, BufferedImage snap, String finalTitle) {
        parentEditor.getContentPane().removeAll();
        parentEditor.setTitle("ICARUS Evidence — Annotation");
        parentEditor.setMinimumSize(new Dimension(640, 480));

        // Converted back to a top-level JFrame to allow OS-level maximization,
        // with the Burp icon mapped for native integration.
        java.awt.Frame parent = owner.api.userInterface().swingUtils().suiteFrame();
        JFrame frame = new JFrame("ICARUS Evidence — Annotation");
        if (parent != null) frame.setIconImage(parent.getIconImage());
        java.awt.GraphicsConfiguration gc = parent != null ? parent.getGraphicsConfiguration() : null;
        java.awt.Rectangle screenBounds = gc != null ? gc.getBounds() : new java.awt.Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        
        int maxWidth = Math.min(1200, screenBounds.width - 50);
        int maxHeight = Math.min(800, screenBounds.height - 100);
        frame.setSize(new Dimension(maxWidth, maxHeight));
        frame.setLocationRelativeTo(parent);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());

        // Canvas panel that paints the image and annotations at an offset (for panning)
        List<Shape> shapes = new ArrayList<>();
        List<Color> cols = new ArrayList<>();
        List<String> kinds = new ArrayList<>();

        Color[] curCol = { Color.RED };
        String[] mode = { "PAN" }; // PAN, BOX, ARROW, HIGHLIGHT, REDACT
        Point[] dragStart = { null };
        Shape[] preview = { null };
        int[] panOffset = { 0, 0 }; // x, y offset for panning
        double[] scale = { 1.0 };

        JPanel canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics gr) {
                super.paintComponent(gr);
                Graphics2D g2 = (Graphics2D) gr.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                g2.translate(panOffset[0], panOffset[1]);
                g2.scale(scale[0], scale[0]);

                // Draw image
                g2.drawImage(snap, 0, 0, null);

                // Draw committed shapes
                g2.setStroke(new BasicStroke(3f));
                for (int i = 0; i < shapes.size(); i++) {
                    drawAnnotation(g2, shapes.get(i), kinds.get(i), cols.get(i));
                }
                // Draw live preview
                if (preview[0] != null) {
                    drawAnnotation(g2, preview[0], mode[0], curCol[0]);
                }
                g2.dispose();
            }

            private void drawAnnotation(Graphics2D g2, Shape s, String kind, Color c) {
                EvidenceAnnotator.paintAnnotation(g2, s, kind, c);
            }
        };
        canvas.setBackground(new Color(30, 30, 30));
        canvas.setPreferredSize(new Dimension(snap.getWidth() + 200, snap.getHeight() + 200));

        MouseAdapter mouseHandler = new MouseAdapter() {
            private Point panAnchor;

            @Override
            public void mousePressed(MouseEvent e) {
                if ("PAN".equals(mode[0])) {
                    panAnchor = e.getPoint();
                    canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else {
                    dragStart[0] = new Point((int)((e.getX() - panOffset[0]) / scale[0]), (int)((e.getY() - panOffset[1]) / scale[0]));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if ("PAN".equals(mode[0]) && panAnchor != null) {
                    panOffset[0] += e.getX() - panAnchor.x;
                    panOffset[1] += e.getY() - panAnchor.y;
                    panAnchor = e.getPoint();
                    canvas.repaint();
                } else if (dragStart[0] != null) {
                    Point p = new Point((int)((e.getX() - panOffset[0]) / scale[0]), (int)((e.getY() - panOffset[1]) / scale[0]));
                    if ("ARROW".equals(mode[0])) {
                        preview[0] = EvidenceAnnotator.createArrow(dragStart[0], p);
                    } else {
                        int x = Math.min(dragStart[0].x, p.x);
                        int y = Math.min(dragStart[0].y, p.y);
                        int w = Math.abs(p.x - dragStart[0].x);
                        int h = Math.abs(p.y - dragStart[0].y);
                        preview[0] = new Rectangle2D.Double(x, y, w, h);
                    }
                    canvas.repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if ("PAN".equals(mode[0])) {
                    panAnchor = null;
                    canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else if (preview[0] != null) {
                    shapes.add(preview[0]);
                    cols.add(curCol[0]);
                    kinds.add(mode[0]);
                    preview[0] = null;
                    dragStart[0] = null;
                    canvas.repaint();
                }
            }
            
            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                double delta = e.getPreciseWheelRotation() < 0 ? 1.1 : 0.9;
                scale[0] = Math.max(0.5, Math.min(3.0, scale[0] * delta));
                // Center zoom on mouse cursor
                panOffset[0] = (int)(e.getX() - (e.getX() - panOffset[0]) * delta);
                panOffset[1] = (int)(e.getY() - (e.getY() - panOffset[1]) * delta);
                canvas.repaint();
            }
        };
        canvas.addMouseListener(mouseHandler);
        canvas.addMouseMotionListener(mouseHandler);
        canvas.addMouseWheelListener(mouseHandler);

        JScrollPane scroll = new JScrollPane(canvas);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);

        // Toolbar
        JPanel bar = new JPanel(new GridLayout(0, 1, 0, 6));
        bar.setBorder(new EmptyBorder(8, 8, 8, 8));

        JToggleButton panBtn = new JToggleButton("Pan (space)", true);
        JToggleButton boxBtn = new JToggleButton("Box (s)");
        JToggleButton arrowBtn = new JToggleButton("Arrow (a)");
        JToggleButton hiBtn = new JToggleButton("Highlight (h)");
        JToggleButton redactBtn = new JToggleButton("Redact (r)");

        ButtonGroup grp = new ButtonGroup();
        grp.add(panBtn); grp.add(boxBtn); grp.add(arrowBtn); grp.add(hiBtn); grp.add(redactBtn);

        // Non-focusable: Space is Swing's default "activate button" key, so a focused
        // toolbar button (the common state right after selecting a tool) would swallow
        // the hold-to-pan Space binding below via its own WHEN_FOCUSED keymap entry
        // instead of letting it reach the window-level shortcut. Mouse clicks are unaffected.
        for (var b : List.of(panBtn, boxBtn, arrowBtn, hiBtn, redactBtn)) {
            b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
            b.setFocusable(false);
        }

        ActionListener modeSel = a -> {
            if (a.getSource() == panBtn) {
                mode[0] = "PAN";
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            } else {
                if (a.getSource() == boxBtn) mode[0] = "BOX";
                else if (a.getSource() == arrowBtn) mode[0] = "ARROW";
                else if (a.getSource() == hiBtn) mode[0] = "HIGHLIGHT";
                else if (a.getSource() == redactBtn) mode[0] = "REDACT";
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
        };
        panBtn.addActionListener(modeSel); boxBtn.addActionListener(modeSel);
        arrowBtn.addActionListener(modeSel); hiBtn.addActionListener(modeSel);
        redactBtn.addActionListener(modeSel);

        JButton colourBtn = EvidenceUiHelpers.createModernButton("Colour", curCol[0]);
        colourBtn.setFocusable(false);
        colourBtn.addActionListener(a -> {
            Color chosen = JColorChooser.showDialog(frame, "Choose colour", curCol[0]);
            if (chosen != null) { curCol[0] = chosen; colourBtn.setBackground(curCol[0]); }
        });

        JButton undoBtn = EvidenceUiHelpers.createModernButton("Undo", new Color(70, 70, 70));
        undoBtn.setFocusable(false);
        undoBtn.addActionListener(a -> {
            if (!shapes.isEmpty()) {
                shapes.remove(shapes.size() - 1);
                cols.remove(cols.size() - 1);
                kinds.remove(kinds.size() - 1);
                canvas.repaint();
            }
        });

        JButton saveBtn = EvidenceUiHelpers.createModernButton("Save Evidence", EvidenceImageRenderer.ACCENT_COLOR);
        saveBtn.setFocusable(false);
        saveBtn.addActionListener(a -> {
            try {
                BufferedImage out = EvidenceAnnotator.renderFinalImage(snap, shapes, kinds, cols);

                String lastDir = EvidencePaths.defaultOutputDir(owner.api, owner.config);
                JFileChooser fc = new JFileChooser(new File(lastDir));
                fc.setSelectedFile(new File("evidence-" + finalTitle.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png"));
                if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    File f = fc.getSelectedFile();
                    ImageIO.write(out, "png", f);
                    owner.captured.add(new CapturedEvidence(finding, f.toPath(), out, ""));
                    owner.onApplied.accept(finding);
                    if (f.getParentFile() != null) {
                        owner.config.set("evidence.output_dir", f.getParentFile().getAbsolutePath());
                        owner.api.persistence().extensionData().setString("owner.config", owner.config.serialize());
                    }
                    JOptionPane.showMessageDialog(frame, "Saved: " + f.getAbsolutePath());
                    // frame.dispose(); // User requested not to close immediately
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        JButton copyBtn = EvidenceUiHelpers.createModernButton("Copy to Clipboard", EvidenceImageRenderer.ACCENT_COLOR.darker());
        copyBtn.setFocusable(false);
        copyBtn.addActionListener(a -> {
            try {
                BufferedImage out = EvidenceAnnotator.renderFinalImage(snap, shapes, kinds, cols);
                Transferable transferable = new Transferable() {
                    public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] { DataFlavor.imageFlavor }; }
                    public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
                    public Object getTransferData(DataFlavor flavor) { return out; }
                };
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable, null);
                // JOptionPane.showMessageDialog(frame, "Image copied to clipboard.");
                frame.dispose(); // User requested to close when copying
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        JButton sendToReportBtn = EvidenceUiHelpers.createModernButton("Send annotation to Report Generator", EvidenceImageRenderer.ACCENT_COLOR);
        sendToReportBtn.setFocusable(false);
        sendToReportBtn.addActionListener(a -> {
            BufferedImage out = EvidenceAnnotator.renderFinalImage(snap, shapes, kinds, cols);
            owner.saveAndRegisterEvidence(finding, out);
            JOptionPane.showMessageDialog(frame, "Sent to report generator!");
            // frame.dispose(); // User requested not to close immediately
        });

        bar.add(panBtn);
        bar.add(new JSeparator());
        bar.add(colourBtn);
        bar.add(boxBtn);
        bar.add(arrowBtn);
        bar.add(hiBtn);
        bar.add(redactBtn);
        bar.add(undoBtn);
        bar.add(new JSeparator());
        bar.add(sendToReportBtn);
        bar.add(saveBtn);
        bar.add(copyBtn);
        
        JScrollPane barScroll = new JScrollPane(bar);
        barScroll.setBorder(BorderFactory.createEmptyBorder());
        barScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        barScroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(barScroll, BorderLayout.EAST);

        // Flameshot-style shortcuts. Routed through doClick() on the existing toolbar
        // buttons so the ButtonGroup selection state and mode[0]/cursor side effects in
        // their ActionListener stay the single source of truth — no duplicated logic here.
        InputMap shortcutMap = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap shortcutActions = root.getActionMap();

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), "icarus.modeBox");
        shortcutActions.put("icarus.modeBox", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { boxBtn.doClick(); }
        });

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), "icarus.modeArrow");
        shortcutActions.put("icarus.modeArrow", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { arrowBtn.doClick(); }
        });

        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), "icarus.undo");
        shortcutActions.put("icarus.undo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { undoBtn.doClick(); }
        });

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "icarus.modeHighlight");
        shortcutActions.put("icarus.modeHighlight", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { hiBtn.doClick(); }
        });

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "icarus.modeRedact");
        shortcutActions.put("icarus.modeRedact", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { redactBtn.doClick(); }
        });

        // Spacebar hold-to-pan: switch to Pan on press, restore the previous tool on
        // release. The mode[0] guard makes repeated KEY_PRESSED events from OS key-repeat
        // (fired continuously while held) a no-op after the first one.
        String[] prevMode = { null };

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false), "icarus.panDown");
        shortcutActions.put("icarus.panDown", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (!"PAN".equals(mode[0])) {
                    prevMode[0] = mode[0];
                    panBtn.doClick();
                }
            }
        });

        shortcutMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true), "icarus.panUp");
        shortcutActions.put("icarus.panUp", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (prevMode[0] != null) {
                    if ("BOX".equals(prevMode[0])) boxBtn.doClick();
                    else if ("ARROW".equals(prevMode[0])) arrowBtn.doClick();
                    else if ("HIGHLIGHT".equals(prevMode[0])) hiBtn.doClick();
                    else if ("REDACT".equals(prevMode[0])) redactBtn.doClick();
                    prevMode[0] = null;
                }
            }
        });

        // Default cursor for pan mode
        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

        parentEditor.dispose(); // Close the Phase 1 dialog
        frame.setContentPane(root);
        frame.setVisible(true);
    }
}
