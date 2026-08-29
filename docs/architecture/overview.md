# Architecture Overview

This section is dedicated to developers and contributors looking to understand, debug, or extend the ICARUS extension.

ICARUS is built primarily on Java 21 utilizing the modern **Burp Suite Montoya API**.

## High-Level System Design

The architecture of ICARUS is strictly divided into logical domains to maintain a clean separation of concerns.

1. **The Orchestrator (`icarus.Orchestrator`)**
   Acts as the central nervous system. It receives requests from the UI (e.g., "Run all modules against Request X") and delegates them to the `ScanRunner`.
   
2. **The ScanRunner (`icarus.ScanRunner`)**
   Responsible for the actual asynchronous execution of modules. It manages thread pools, ensures modules receive their `ModuleConfig`, and handles the lifecycle of an active scan.

3. **Stateless Modules (`icarus.core.IcarusModule`)**
   The testing engines themselves. They are entirely stateless. All runtime data is injected via `ModuleConfig`. This allows ICARUS to run highly parallel scans without thread contention or race conditions.

4. **The UI Layer (`icarus.ui`)**
   Built using standard Swing components injected into the Montoya API framework. The UI is completely decoupled from the security logic. It listens to the `Orchestrator` and `FindingRegistry` for state changes and updates the view accordingly.

5. **Evidence & Reporting (`icarus.evidence`)**
   An independent subsystem responsible for screenshotting the Montoya Request/Response viewers and rendering OpenPDF documents.
