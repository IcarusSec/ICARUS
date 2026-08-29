# Getting Started with ICARUS

This guide will walk you through compiling the ICARUS codebase and installing it within Burp Suite.

## Prerequisites
- **Java 21** or higher.
- **Burp Suite Professional or Community Edition**.
- Build tools (Gradle wrapper is included).

## 1. Compile the Extension

ICARUS uses Gradle for dependency management and building. A script is provided to streamline the process.

```bash
cd icarus-extension/
./build.sh
```

This script will automatically:
1. Download the required Montoya API dependencies.
2. Compile the Java source code.
3. Package the output into a standalone JAR file.

*The generated `.jar` will be saved to:* `icarus-extension/build_manual/libs/icarus-<version>.jar`

## 2. Load into Burp Suite

1. Open Burp Suite.
2. Navigate to the **Extensions** tab.
3. Click the **Add** button in the "Installed" section.
4. Set the extension type to **Java**.
5. Select the `icarus-<version>.jar` file generated in the previous step.
6. Verify that ICARUS loads without errors. The **ICARUS** tab will appear in the main navigation bar.

## 3. Initial Configuration

Before running scans, verify your configuration:
- Navigate to the **ICARUS** tab.
- Click on **Settings** to adjust global scan thresholds, reporting metadata, and UI preferences.
- Review the specific module parameters (e.g., Request Burst Size for Rate Limiting).
