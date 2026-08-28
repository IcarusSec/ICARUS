# ICARUS Technical Documentation

This document provides a concise overview of the ICARUS Burp Suite extension codebase, categorized by package.

## Package: `autoauth`

### `AutoAuthModule`
No description provided.

### `AutoAuthPreviewEditorProvider`
No description provided.

## Package: `core`

### `Category`
Categories of security tests performed by ICARUS modules.

### `Finding`
No description provided.

### `FindingRecord`
No description provided.

### `FindingRegistry`
No description provided.

### `IcarusModule`
Contract for every ICARUS security testing module. Modules are stateless — all mutable state lives in the {@link ModuleConfig} they receive. This keeps them testable and safe to reuse across concurrent scans.

### `JsonParser`
No description provided.

### `JsonPaths`
No description provided.

### `ModuleConfig`
No description provided.

### `RawNumber`
No description provided.

### `Severity`
Severity levels for findings, ordered from most to least critical.

### `VerboseErrorDetector`
No description provided.

## Package: `evidence`

### `CweRepository`
No description provided.

### `EvidenceCapture`
No description provided.

### `EvidenceColorScheme`
Color schemes for evidence screenshot rendering.

### `PdfReportGenerator`
No description provided.

### `ReportGenerator`
No description provided.

## Package: `icarus (core root)`

### `Icarus`
ICARUS — Burp Suite Extension entry point. Consolidates all ICARUS Bambda scripts into a single extension with auto-evidence capture and structured reporting.

### `Orchestrator`
No description provided.

### `has`
No description provided.

## Package: `modules`

### `HttpVerbModule`
No description provided.

### `JwtCheckerModule`
No description provided.

### `ParamValidatorModule`
No description provided.

### `PassiveErrorModule`
No description provided.

### `PostmanExportModule`
No description provided.

### `RateLimitModule`
Rate Limit module — detects, characterizes, and attempts to bypass rate limiting. Phase 1: Blast N identical requests (null payloads) to detect if rate limiting exists. Phase 2: If detected, characterize the threshold and block type. Phase 3: Attempt known bypass techniques and re-blast.

### `SensitiveHeaderModule`
No description provided.

## Package: `ui`

### `IcarusTab`
No description provided.

### `SettingsPanel`
No description provided.

### `ToastNotification`
No description provided.

### `to`
No description provided.
