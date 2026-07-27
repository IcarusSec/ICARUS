    private JTextArea createStyledTextArea(String text) {
        JTextArea ta = new JTextArea(text);
        ta.setFont(MONO_FONT);
        ta.setBackground(BG_COLOR);
        ta.setForeground(TEXT_COLOR);
        ta.setCaretColor(Color.WHITE);
        ta.setMargin(new Insets(10, 15, 10, 15)); // Modern padding
        ta.setTabSize(4);

        // Add Undo/Redo capability
        javax.swing.undo.UndoManager undoManager = new javax.swing.undo.UndoManager();
        ta.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        InputMap im = ta.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = ta.getActionMap();

        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), "Undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, ctrl), "Redo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl | InputEvent.SHIFT_DOWN_MASK), "Redo");

        am.put("Undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) undoManager.undo();
            }
        });
        am.put("Redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) undoManager.redo();
            }
        });

        return ta;
    }
