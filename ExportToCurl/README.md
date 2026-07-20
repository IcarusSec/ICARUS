# Export to cURL (`ExportToCurl`)

A Burp Suite Custom Action that exports the current request in the Repeater to a `curl` command.

## Overview

Sometimes during testing, it is useful to quickly grab a `curl` equivalent of the request you've crafted in Burp Repeater so you can run it from the command line, share it, or use it in scripts. This Custom Action reads the current request and outputs a ready-to-use `curl` command directly in the action's log.

## Features

- **Accurate Method & URL:** Extracts the exact HTTP method and absolute URL.
- **Header Preservation:** Preserves all headers and escapes them properly for a bash shell environment.
- **Body Preservation:** Uses `--data-binary` to accurately send the body payload as it is, which is crucial for JSON, XML, or other specific formats.
- **Bash Compatible:** Automatically escapes single quotes in headers and body to avoid breaking the shell command.

## Usage

1. Open Burp Suite and navigate to **Repeater**.
2. Go to the **Custom actions** settings (or right-click a request and find the Custom actions menu).
3. Create a **New** action and select **Blank**.
4. Copy the contents of `exporttocurl.java` and paste it into the script editor.
5. Run the action against your target request.
6. Check the Custom Action output log to copy the generated `curl` command!
