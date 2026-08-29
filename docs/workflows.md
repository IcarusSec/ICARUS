# Core Testing Workflows

ICARUS integrates natively with Burp Suite's existing UI, making it incredibly intuitive to incorporate into your day-to-day testing workflows.

## Context Menu Integration

ICARUS capabilities are accessible from virtually anywhere in Burp Suite (Repeater, Proxy history, Target map).

1. **Right-Click** any HTTP request/response.
2. Navigate to the **Extensions > ICARUS** sub-menu.
3. You will see context-aware options:
   - **Run All Modules**: Dispatches the request to all active scanning engines concurrently.
   - **Run [Module Name]**: E.g., `Run JWT Checker`. Launches a specific test in isolation.
   - **Send to Reporter Creation**: Captures the request/response pair for the Evidence Manager.
   - **Set as Auth Token Source**: Begins an AutoAuth workflow (see AutoAuth docs).

## Managing Active Scans

When a scan is launched:
1. Navigate to the **ICARUS Tab**.
2. Switch to the **Active Tasks** view.
3. You will see a real-time table of executing modules, their progress, and success/failure states.
4. Because modules are highly concurrent and stateless, you can launch dozens of scans simultaneously without UI freezing.

## Reviewing Findings

If a module discovers a vulnerability:
1. A **Toast Notification** (bottom right of the UI) will briefly appear to alert you.
2. The finding will be logged in the **Results** tab within ICARUS.
3. Double-clicking a finding will display the request/response payload that triggered it, alongside a detailed description and remediation advice.
