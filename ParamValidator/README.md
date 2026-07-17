# Burp Suite - JSON Input Validation Custom Action

A Burp Suite Custom Action for automatically testing **JSON request parameter validation**.

Instead of looking for a specific vulnerability (SQLi, XSS, etc.), this project focuses on a much simpler question:

> **Did the API accept input that should have been rejected?**

If a payload intentionally crafted to violate the expected contract is accepted (`HTTP 2xx`), the request is flagged as a potential input validation issue and can be sent directly to Burp Repeater for manual investigation.

---

## Why?

Many API security issues originate from weak server-side validation.

Examples include:

- accepting HTML/JavaScript payloads
- accepting SQL-like strings
- accepting NoSQL operators
- accepting path traversal payloads
- accepting invalid types
- accepting unexpected structures
- accepting oversized values

Although accepting these values does **not** necessarily mean the API is vulnerable to exploitation, it usually indicates missing or insufficient input validation.

This project automates those checks.

---

## Features

- JSON parser (no external dependencies)
- Recursive traversal of nested objects and arrays
- Automatic mutation generation
- Structural validation tests
- Type confusion tests
- Boundary value tests
- Injection payload tests
- Batch request execution
- Automatic Repeater integration
- Optional Organizer integration
- Fully configurable from a single configuration section

---

## Current Test Categories

### Structural

- null values
- removed fields
- empty objects
- empty arrays

---

### Type Confusion

- string → number
- string → boolean
- number → string
- number → numeric string
- boolean → string
- boolean → number

---

### Boundary

- empty string
- very long strings
- zero
- negative numbers
- integer overflow
- integer → float
- boolean inversion

---

### Injection Payloads

- SQL Injection
- Time-based SQLi
- XSS
- NoSQL Injection
- Path Traversal
- Format String
- Unicode / RTL Override

---

## Finding Criteria

The current detection strategy is intentionally conservative.

A finding is generated when:

```

mutated request
+
HTTP 2xx
=
potential input validation issue

```

Responses such as:

- 400
- 401
- 403
- 404
- 409
- 415
- 422

are treated as expected validation behavior.

Server errors (5xx) are logged separately.

---

## Configuration

Everything intended to be customized by the analyst is located at the beginning of the script.

Examples:

- enable/disable entire test categories
- enable/disable individual mutations
- customize payloads
- configure maximum mutations
- configure maximum Repeater tabs
- customize finding HTTP range
- enable/disable baseline validation
- configure logging verbosity

---

## Philosophy

This project intentionally **does not attempt to prove exploitation**.

For example:

```

<script>alert(1)</script>

```

returning **HTTP 200** does **not** prove XSS.

Instead, it indicates that the API accepted input that arguably should have been rejected.

The objective is to reduce manual work by surfacing requests worth reviewing.

---

## Roadmap

- OpenAPI-aware mutations
- Parameter-specific validation rules
- Smarter payload selection
- Response reflection detection
- Response diffing
- GraphQL support
- XML support
- Multipart support
- Better array mutation strategies
- Custom payload profiles
- Export findings as JSON

---

## Disclaimer

This project is intended for:

- security research
- defensive security testing
- authorized penetration testing
- secure software development

Use only against systems you are authorized to test.

---

## License

MIT
