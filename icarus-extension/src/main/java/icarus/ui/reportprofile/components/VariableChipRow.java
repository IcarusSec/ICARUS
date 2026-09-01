package icarus.ui.reportprofile.components;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

public final class VariableChipRow extends JPanel {
    private static final String[] TOKENS = {
        "{{team}}", "{{component}}", "{{requester}}", "{{environment}}", 
        "{{author}}", "{{date}}", "{{finding_count}}", "{{finding_types}}"
    };

    public VariableChipRow(Consumer<String> onTokenClick) {
        setLayout(new icarus.ui.reportprofile.layout.WrapLayout(FlowLayout.LEFT, 6, 6));
        setOpaque(false);
        
        for (String token : TOKENS) {
            add(new VariableChip(token, onTokenClick));
        }
    }
}
