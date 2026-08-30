package com.icarus.ui.reportprofile.sections;

import com.icarus.ui.reportprofile.layout.Breakpoint;
import com.icarus.ui.reportprofile.layout.ResponsiveSection;
import icarus.report.model.SectionNode;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class SectionListPanel implements ResponsiveSection {
    private final JScrollPane component;
    public final DefaultListModel<SectionNode> model;
    public final JList<SectionNode> list;

    public SectionListPanel() {
        model = new DefaultListModel<>();
        list = new JList<>(model);
        list.setCellRenderer(new SectionListCellRenderer());
        
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index < 0) return;
                
                Rectangle cellBounds = list.getCellBounds(index, index);
                int xInCell = e.getX() - cellBounds.x;
                
                if (xInCell >= SectionListCellRenderer.TOGGLE_X0 && xInCell <= SectionListCellRenderer.TOGGLE_X1) {
                    SectionNode row = model.getElementAt(index);
                    if (!row.required()) {
                        model.setElementAt(new SectionNode(row.id(), !row.enabled(), row.order(), row.required(), row.rendererKey(), row.params()), index);
                        list.repaint();
                    }
                } else {
                    list.setSelectedIndex(index);
                }
            }
        });

        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new SectionTransferHandler());

        component = new JScrollPane(list);
        component.setPreferredSize(new Dimension(280, 280));
        component.setMinimumSize(new Dimension(200, 200));
    }

    @Override
    public Component component() {
        return component;
    }

    @Override
    public void onBreakpointChanged(Breakpoint bp) {
        // Handled by FlowPanel wrapper
    }

    private class SectionTransferHandler extends TransferHandler {
        private final DataFlavor flavor;
        private int sourceIndex = -1;

        public SectionTransferHandler() {
            flavor = new DataFlavor(SectionNode.class, "SectionNode");
        }

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            sourceIndex = list.getSelectedIndex();
            SectionNode row = model.getElementAt(sourceIndex);
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{flavor};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor f) {
                    return f.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                    if (isDataFlavorSupported(f)) {
                        return row;
                    }
                    throw new UnsupportedFlavorException(f);
                }
            };
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            if (action == MOVE && sourceIndex != -1) {
                // handled in importData
            }
            sourceIndex = -1;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(flavor) && support.isDrop();
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            
            try {
                SectionNode data = (SectionNode) support.getTransferable().getTransferData(flavor);
                JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
                int dropIndex = dl.getIndex();
                
                if (sourceIndex != -1) {
                    if (dropIndex > sourceIndex) {
                        dropIndex--;
                    }
                    model.remove(sourceIndex);
                }
                
                model.add(dropIndex, data);
                list.setSelectedIndex(dropIndex);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
