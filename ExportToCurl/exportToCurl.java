// ============================================================
// Export to cURL
// Burp Suite Custom Action
//
// Authors: Adan Ferreira | Victor Lima
//
// Description:
// Exports the current request to a cURL command.
//
// Version: 0.1.0
// ============================================================

// ============================================================
// ================= BASIC USAGE =================
// ------------------------------------------------------------
// Paste this script in:
// Repeater > Custom actions > New > Blank
//
// Objective:
//  - Generates a ready-to-use curl command from the request.
//  - Outputs the command to the Custom Action log.
// ============================================================

var req = requestResponse.request();

if (req == null) {
    logging.logToOutput("[!] No request available to export.");
    return;
}

String method = req.method();
String url = req.url();
String body = req.bodyToString();

java.lang.StringBuilder curl = new java.lang.StringBuilder();

// -i: Include HTTP response headers in the output
// -s: Silent mode
// -k: Allow insecure server connections when using SSL
curl.append("curl -i -s -k -X '").append(method).append("' \\\n");

for (var h : req.headers()) {
    String name = h.name();
    String value = h.value();
    
    // Escape single quotes for bash compatibility
    String escapedValue = value.replace("'", "'\\''");
    
    curl.append("    -H '").append(name).append(": ").append(escapedValue).append("' \\\n");
}

if (body != null && !body.isEmpty()) {
    // Escape single quotes in the body
    String escapedBody = body.replace("'", "'\\''");
    curl.append("    --data-binary '").append(escapedBody).append("' \\\n");
}

curl.append("    '").append(url).append("'");

logging.logToOutput("========== cURL Command ==========\n");
logging.logToOutput(curl.toString());
logging.logToOutput("\n==================================");
