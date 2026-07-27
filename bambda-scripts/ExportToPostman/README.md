# Export to Postman (`ExportToPostman`)

A Burp Suite Custom Action that exports the current request in the Repeater to a Postman Collection JSON format.

## Overview

Sometimes you want to quickly port your crafted request from Burp Repeater directly into Postman for team sharing, API documentation, or testing workflows. This Custom Action creates a complete, valid Postman Collection JSON for the active request directly in the Custom Actions log.

## Features

- **Postman Collection v2.1.0:** Generates fully compatible Postman Collection JSON objects.
- **Accurate Method & URL:** Extracts the exact HTTP method and parses the URL into Postman's `host`, `path`, and `query` block structures.
- **Header Preservation:** Preserves all HTTP headers accurately.
- **Body Preservation:** Preserves raw body payload securely with proper JSON escaping.

## Usage

1. Open Burp Suite and navigate to **Repeater**.
2. Go to the **Custom actions** settings (or right-click a request and find the Custom actions menu).
3. Create a **New** action and select **Blank**.
4. Copy the contents of `exporttopostman.java` and paste it into the script editor.
5. Run the action against your target request.
6. Check the Custom Action output log, copy the JSON block, and save it as a `.json` file to import into Postman!
