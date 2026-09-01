# Security Policy

ICARUS is a security research tool. Vulnerabilities in the extension itself
(e.g., path traversal in report output paths, unsafe deserialization of saved
configs, XSS in exported HTML, an unauthenticated MCP surface) should be
reported privately.

**Please open a [private security advisory](https://github.com/IcarusSec/ICARUS/security/advisories/new)
rather than a public issue.**

Include:

- Extension version (jar filename)
- Burp Suite version
- Steps to reproduce
- Impact assessment

We aim to respond within 7 days.

## Supported Versions

Security fixes are applied to the latest minor release only. Older versions
should be upgraded rather than patched in place.

| Version | Supported          |
| ------- | ------------------ |
| 1.5.x   | :white_check_mark: |
| 1.4.x   | :white_check_mark: |
| < 1.4   | :x:                |

## Out of Scope

The following are **not** vulnerabilities in ICARUS and will be closed without a
fix:

- **Findings produced *by* ICARUS** against a target application — those are the
  tool doing its job, not a flaw in the tool.
- **Self-XSS / self-inflicted issues inside the tester's own Burp instance**
  (e.g. rendering attacker-controlled evidence you pasted in yourself).
- **SSRF / request forgery "to the tester's own targets"** — ICARUS sends the
  HTTP requests you direct it to send; reaching hosts you pointed it at is
  expected behavior.
- Issues that require an already-compromised local machine or a malicious local
  user with the ability to run arbitrary code as the Burp user.
- Missing hardening that has no demonstrated exploit path.

> Note: Findings *produced by* ICARUS against target applications are by design,
> not vulnerabilities in the tool itself.
