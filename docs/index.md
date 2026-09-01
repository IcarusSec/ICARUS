# ICARUS Documentation

Welcome to the official documentation for **ICARUS**, an AI-native offensive security
testing extension for Burp Suite. ICARUS runs the whole API assessment pipeline —
automated scanning, technical validation, visual evidence capture, and client-ready
reporting — inside a single Burp tab.

<p align="center">
  <img src="assets/results-tab.png" alt="ICARUS Results tab" width="900">
</p>

New here? Read the **[ICARUS 1.0 launch notes](launch-1.0.md)** for a tour of what shipped.

## Documentation Structure

Our documentation is broken down into modular sections to help you navigate and find exactly what you need.

### Introduction & Usage
1. **[Getting Started](getting_started.md)** - Installation, compilation, and basic setup.
2. **[Testing Workflows](workflows.md)** - Step-by-step guide on how to integrate ICARUS into your security workflow.

### Core Features
1. **[AutoAuth Engine](features/autoauth.md)** - Advanced, host-scoped token management.
2. **[Evidence Manager](features/evidence_manager.md)** - Capturing, tagging, and organizing findings.
3. **[Dynamic Reporting Engine](features/reporting.md)** - Customizing, profiling, and exporting polished HTML/PDF reports.

### Security Modules (Engines)
1. **[JSON Parameter Validator](modules/param_validator.md)**
2. **[HTTP Verb Tester](modules/http_verb.md)**
3. **[JWT & Bearer Token Checker](modules/jwt_checker.md)**
4. **[Rate Limit Tester](modules/rate_limit.md)**
5. **[Passive Scanners (Header & Error Leak)](modules/passive_scanners.md)**

### Developer Architecture
1. **[Architecture Overview](architecture/overview.md)**
2. **[Core Concepts & Execution](architecture/core_concepts.md)**
