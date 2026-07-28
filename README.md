<p align="center">
  <img src="./.images/banner.png" alt="ICARUS Banner">
</p>
<h1 align="center">ICARUS</h1>

<p align="center">
<img src="https://img.shields.io/badge/BurpSuite-Extension-orange" alt="Burp Suite">
<img src="https://img.shields.io/badge/Security-Testing-blue" alt="Security">
<img src="https://img.shields.io/badge/Language-Java-red" alt="Java">
</p>

<div align="center">
  <a href="#features">Features</a>
  <span>&nbsp;&nbsp;•&nbsp;&nbsp;</span>
  <a href="#modules">Modules</a>
  <span>&nbsp;&nbsp;•&nbsp;&nbsp;</span>
  <a href="#installation--usage">Installation & Usage</a>
  <span>&nbsp;&nbsp;•&nbsp;&nbsp;</span>
  <a href="#disclaimer">Disclaimer</a>
  <br />
</div>

## What is ICARUS?

**ICARUS** is a comprehensive security testing extension for Burp Suite, designed to automate and streamline API security testing tasks directly from your Burp workflow. It provides a unified interface, automated scanning capabilities, and advanced evidence capture for rapid, effective vulnerability assessment and reporting.

## Features

- **Unified Interface:** A centralized control panel (`ICARUS` tab) for configuring all modules, tracking tasks, and managing findings.
- **Smart Evidence Capture:** Advanced workflow for generating clean, actionable reports. Includes full payload logging, a built-in image editor (with annotation tools like box, arrow, highlight, and redact), and HTML report generation.
- **Automated Scanning:** Run comprehensive checks against selected requests with a single click from the Burp context menu.

## Modules

ICARUS integrates multiple powerful security testing tools into a single extension:

### 1. JSON Input Validation (`ParamValidator`)
Focuses on testing JSON request parameter validation. It answers a simple question: **Did the API accept input that should have been rejected?**
- Structural validation tests (null values, removed fields, empty objects/arrays).
- Type confusion tests (e.g., string → number, boolean → string).
- Boundary value tests (e.g., empty string, very long strings, zero, negative numbers).
- Injection payload tests (SQLi, XSS, NoSQL, Path Traversal, etc.).

### 2. HTTP Verb Tester (`HttpVerbModule`)
Performs basic HTTP verb validation for API security testing. Generates variations using alternate HTTP methods (`GET`, `HEAD`, `POST`, `OPTIONS`, `TRACE`, etc.) to identify misconfigurations or accepted alternative methods.
- Automatic handling of body content based on the method.
- Support for `OPTIONS` / `Allow` header validation.
- TRACE reflection detection.

### 3. JWT / Bearer Token Checker (`JwtCheckerModule`)
A comprehensive tool for detecting and testing JSON Web Tokens (JWTs) and Bearer tokens for common security vulnerabilities.
- Detects multiple JWTs across standard headers and cookies.
- Algorithm Analysis (e.g., weak configurations like `alg=none`, embedded claims like `jwk`, `jku`, `kid`).
- Payload Tampering (e.g., tampering with `admin`, `role`, `scope`).
- Signature Removal testing.
- Time-based Attacks (missing `exp`/`iat` claims).

### 4. Rate Limit Tester (`RateLimitModule`)
Sends repeated requests to detect, characterize, and attempt to bypass rate limiting.
- Burst detection to determine whether throttling exists.
- Configurable thresholds for request counts and timing.
- Captures requests per second (RPS) and timestamps in evidence generation.

### 5. Sensitive Header Scanner (`SensitiveHeaderModule`)
Automatically inspects responses for sensitive headers or caching misconfigurations that could lead to data leakage.

### 6. Export to Postman (`PostmanExportModule`)
Exports the current request directly to a Postman Collection JSON format.
- Accurate extraction of HTTP method, headers, and URL structure.
- Secure body payload preservation with proper JSON escaping.

## Installation & Usage

1. **Build the extension:** Run `./build.sh` inside the `icarus-extension/` directory. This will download the Montoya API, compile the Java files, and generate a JAR file in `icarus-extension/build_manual/libs/`.
2. **Load into Burp Suite:**
   - Go to the **Extensions** tab.
   - Click **Add**.
   - Select **Java** as the extension type.
   - Choose the generated `icarus-<version>.jar` file.
3. **Usage:**
   - Navigate to the **ICARUS** tab in Burp Suite to configure module settings and view findings.
   - Right-click any request in the Repeater or Proxy history, go to **Extensions** -> **ICARUS**, and select a specific module or **Run All Modules**.

## Disclaimer

These tools are intended for:
- Security research
- Defensive security testing
- Authorized penetration testing
- Secure software development

> **Use only against systems you are authorized to test.**

## License

ICARUS is open-sourced under the MIT license. See the [LICENSE](LICENSE) file for more information.
