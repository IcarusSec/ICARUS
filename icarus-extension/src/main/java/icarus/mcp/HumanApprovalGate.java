package icarus.mcp;

import burp.api.montoya.MontoyaApi;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;

/**
 * Blocks the calling thread (the MCP request handler, never the EDT) on a real Swing dialog in
 * Burp — the actual enforcement for "exploit_finding requires explicit human approval", not a
 * tool description asking the calling AI nicely to check first. An MCP client cannot approve its
 * own exploit attempt: this shows the exact request that's about to be sent to a human at the
 * keyboard and waits for them to click Approve/Deny before {@link IcarusMcpServer} sends anything.
 */
public final class HumanApprovalGate {

    private HumanApprovalGate() {}

    /** @return true if the analyst clicked Approve; false on Deny, or if the dialog couldn't be shown. */
    public static boolean requestApproval(MontoyaApi api, String title, String details) {
        boolean[] approved = {false};
        Runnable show = () -> approved[0] = JOptionPane.showConfirmDialog(
                api.userInterface().swingUtils().suiteFrame(),
                details,
                "ICARUS — Approval Required: " + title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;

        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
            return approved[0];
        }
        try {
            SwingUtilities.invokeAndWait(show);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (InvocationTargetException e) {
            api.logging().logToError("ICARUS approval dialog failed: " + e.getCause());
            return false;
        }
        return approved[0];
    }
}
