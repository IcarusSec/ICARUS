<p align="center">
  <img src="./.images/banner.png" alt="ICARUS Banner">
</p>
<h1 align="center">ICARUS 1.0</h1>

<p align="center">
  <a href="https://github.com/IcarusSec/ICARUS/actions/workflows/build.yml"><img src="https://github.com/IcarusSec/ICARUS/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/IcarusSec/ICARUS/releases/latest"><img src="https://img.shields.io/github/v/release/IcarusSec/ICARUS?display_name=tag" alt="Latest release"></a>
  <a href="https://github.com/IcarusSec/ICARUS/blob/main/LICENSE"><img src="https://img.shields.io/github/license/IcarusSec/ICARUS" alt="License"></a>
</p>

<p align="center">
  <a href="https://portswigger.net/burp/extender"><img src="https://img.shields.io/badge/BurpSuite-Extension-orange?style=for-the-badge&logo=burpsuite" alt="Burp Suite"></a>
  <a href="https://java.com/"><img src="https://img.shields.io/badge/Language-Java_19-red?style=for-the-badge&logo=openjdk" alt="Java"></a>
  <a href="https://github.com/IcarusSec/ICARUS/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="License"></a>
  <img src="https://img.shields.io/badge/Security-Testing-brightgreen?style=for-the-badge" alt="Security">
</p>

---

## 🎯 Overview

**ICARUS** is an AI-native offensive security testing extension for Burp Suite. It runs the
whole API assessment pipeline — automated scanning, technical validation, visual evidence
capture, and client-ready reporting — inside a single Burp tab.

<p align="center">
  <img src="./docs/assets/results-tab.png" alt="ICARUS Results tab" width="900">
</p>

Right-click any request → **Extensions → ICARUS** to scan. Findings land in the **Results**
grid, get validated (by you or by an AI agent over MCP), turn into annotated evidence, and
export to a themed HTML or PDF report — without leaving Burp.

---

## ✨ Core Features

- **ParamValidator & WAF Evasion:** Deep testing of JSON bodies, URL parameters, and
  form-urlencoded data. Behavioral WAF detection triggers an evasion payload jump (CRS
  4-tuned list, GLOB/NOTNULL SQLi bypasses against `libinjection`), with baseline diffing
  against non-2xx responses for boolean-based SQLi.
- **Embedded MCP Server:** ICARUS hosts its own Model Context Protocol server over
  Streamable HTTP. AI agents connect directly to read traffic, run `validate_finding` /
  `exploit_finding` attacks, and render annotated evidence with zero manual screenshots.
- **Master-Detail Evidence Manager:** A dedicated tab with a two-phase annotation workflow
  (smart text cleanup → visual canvas: boxes, arrows, highlights, redactions). Rename,
  drag-to-reorder, paste from clipboard. Retest Mode stamps `FIXED` / `NOT FIXED` banners.
- **Dynamic Report Engine:** `ReportProfiles` architecture — drag-and-drop sections, custom
  master-detail Markdown, CVSS 4 classification, themed HTML and offline OpenPDF output
  (Catppuccin, Dracula, Nord, Gruvbox, Burp Proxy Night).
- **AutoAuth:** Replaces Burp Macros. Highlight-and-click to map tokens; ICARUS refreshes
  and injects them in the background so sessions never expire.
- **Unified Command Interface:** One panel to configure every module, track active tasks,
  and manage findings in real time.

---

## 📸 Screenshots

### Evidence Manager

Select a finding on the left, curate its evidence cards on the right — captions, severity,
ordering, and report inclusion.

<p align="center">
  <img src="./docs/assets/evidence-manager.png" alt="Evidence Manager" width="900">
</p>

### Auto-rendered evidence

AI agents (or the annotation canvas) produce whitespace-trimmed, ICARUS-branded request /
response cards. No manual screenshots.

<p align="center">
  <img src="./docs/assets/evidence-jwt-privesc.png" alt="Annotated JWT privilege-escalation evidence" width="800">
</p>

### Reports

<p align="center">
  <img src="./docs/assets/report-pdf-cover.png" alt="ICARUS PDF report cover" width="380">
  <img src="./docs/assets/report-pdf-finding.png" alt="ICARUS PDF finding detail" width="380">
</p>

---

## 🧩 Extension Modules

<details>
<summary><b>1. JSON & URL ParamValidator (with WAF Evasion)</b></summary>
<br>
Rigorous testing of JSON request parameters, URL query strings, and form-urlencoded data to
determine whether the backend processes malformed or malicious input.
<br><br>

<p align="center">
  <img src="./.images/paramval.gif" alt="Param Validator Demo" width="900">
</p>
<br>

- **WAF Evasion Engine:** Behaviorally detects WAF blocks and executes evasion payload jumps using CRS 4-tuned payloads to bypass filters like `libinjection`.
- **Smart Baseline Diffing:** Detects boolean-based SQLi and subtle state transitions by diffing against non-2xx baselines.
- **Structural & Boundary Validation:** Identifies missing null enforcement, type confusion, and boundary limits.
</details>

<details>
<summary><b>2. HTTP Verb Tester</b></summary>
<br>
Exhaustive HTTP verb validation — mutates requests across alternate methods (`GET`, `HEAD`,
`POST`, `OPTIONS`, `TRACE`, …) to uncover endpoint misconfigurations.
<br><br>

<p align="center">
  <img src="./.images/httverb.gif" alt="HTTP Verb Tester Demo" width="900">
</p>
</details>

<details>
<summary><b>3. Rate Limit Tester</b></summary>
<br>
High-velocity request engine to detect, characterize, and attempt bypasses on API rate
limiting. Heavily concurrent blast engine with smart 429/backoff throttling.
</details>

<details>
<summary><b>4. JWT / Bearer Token Checker</b></summary>
<br>
Detects, parses, and exploits JWTs and Bearer tokens for critical flaws — `alg=none`,
signature stripping, missing/expired `exp`, `aud` tampering, and role-claim privilege
escalation.
</details>

<details>
<summary><b>5. AutoAuth Module</b></summary>
<br>
Replaces Burp Macros with a highlight-and-click workflow for managing auth tokens, keeping
sessions alive across multiple sources silently in the background.
</details>

<details>
<summary><b>6. Passive Error Detector</b></summary>
<br>
Runs in the background, flagging HTTP 500+ responses and verbose error / stack-trace leaks
(SQL errors, framework tracebacks) as they cross the proxy.
</details>

<details>
<summary><b>7. Export to Postman</b></summary>
<br>
Exports active HTTP requests directly into a standard Postman Collection JSON.
</details>

---

## ⚡ Quick Start (Pre-built JAR)

Grab the latest fat JAR from the
[**Releases page**](https://github.com/IcarusSec/ICARUS/releases/latest) — download
`icarus-<version>.jar`, then in Burp Suite go to **Extensions → Add → extension type: Java**
and select the file.

---

## 🚀 Build from Source

Requires a JDK (build targets `--release 19`). The build script downloads the Montoya API,
OpenPDF, the MCP SDK, and commonmark-java, then packages everything into one fat JAR.

### Linux / macOS
```bash
cd icarus-extension/
./build.sh
```

### Windows (PowerShell)
```powershell
cd icarus-extension\
powershell -ExecutionPolicy Bypass -File build.ps1
```

Output: `icarus-extension/build_manual/libs/icarus-<version>.jar`

Then load it into Burp: **Extensions** tab → **Add** → type **Java** → select the JAR.

---

## 📖 Documentation

- **[Getting Started](docs/getting_started.md)**
- **[Testing Workflows](docs/workflows.md)**
- **[Dynamic Reporting Engine](docs/features/reporting.md)**
- **[Evidence Manager](docs/features/evidence_manager.md)**
- **[AutoAuth](docs/features/autoauth.md)**

Per-module guides and architecture notes live in [`docs/`](docs/).

---

## 🛠 Usage Guidelines

- **Configuration:** Use the **ICARUS** tabs (Results, Evidence, Reporting, Knowledge Base,
  Settings, Audit Log) in Burp to configure profiles and manage findings.
- **Execution:** Right-click any request in **Repeater**, **Proxy history**, or **Target**,
  then **Extensions → ICARUS** — pick a module or **Run All Modules** for a full assessment.

---

## ⚠️ Disclaimer

> [!WARNING]
> These tools are explicitly intended for:
> - Security research and vulnerability analysis
> - Defensive security engineering
> - Authorized penetration testing engagements
> - Secure software development lifecycles
>
> **You must only use this software against systems, networks, and applications that you are explicitly authorized to test.**

---

## ⚖️ License

ICARUS is open-sourced software licensed under the [MIT License](LICENSE).
