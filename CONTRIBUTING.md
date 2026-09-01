# Contributing to ICARUS

Thanks for your interest in improving ICARUS. This guide covers the dev
environment, build, coding conventions, and how to get a change merged.

## Prerequisites

- **JDK 19 or newer.** The extension compiles with `--release 19`. Burp Suite
  ships a bundled JDK that works; a standalone Temurin 19/21 install works too.
- **Burp Suite** (Community or Professional) to load and test the built jar.
- **git** and a POSIX shell (`bash`) or PowerShell.

No Gradle/Maven install is required — the build is a self-contained script that
downloads its own dependencies from Maven Central.

## Building

Run the build from inside `icarus-extension/`.

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

Both produce `icarus-extension/build_manual/libs/icarus-<version>.jar`. Load it
via Burp → **Extensions** → **Add** → extension type **Java**.

The version is the single source of truth in
`icarus-extension/src/main/java/icarus/Icarus.java` (`VERSION`), scraped by the
build scripts — bump it there.

## Coding conventions

- **Language:** Java. Match the style of the surrounding code.
- **Formatting:** governed by `.editorconfig` — 4-space indent, LF endings,
  UTF-8, trailing whitespace trimmed, final newline.
- **Swing / threading:** all UI updates and blocking dialogs (`JFileChooser`,
  `JOptionPane`, …) must run on the Event Dispatch Thread. Use
  `SwingUtilities.invokeAndWait`/`invokeLater`, and guard `invokeAndWait`
  against being called from the EDT.
- **Batch robustness:** if one item in a batch fails, catch, log, and continue —
  don't abort the whole operation.
- **File system:** normalize paths before comparing (`Path.normalize()`), and
  prompt before overwriting an existing file.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add NOTNULL SQLi evasion payload
fix(pv): guard WAF-throttle invokeAndWait against an EDT caller
perf(pv): reuse the parsed JSON tree across mutations
docs: document the MCP tool surface
chore: bump bundled montoya-api to 2026.7
```

Common types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`,
`chore`, `style`. Keep the subject imperative and under ~72 characters. Commit
after each logical change rather than one large squash.

## Branches & pull requests

- Branch off `main`. Name branches `feat/<short-topic>`, `fix/<short-topic>`,
  or `docs/<short-topic>`.
- Keep a PR focused on one concern. Rebase on `main` before requesting review.
- Fill in the pull request template. Describe what changed, why, and how you
  tested it (which Burp version, which module, sample request/response).
- CI must be green: the **Build** and **Security Scan** workflows and CodeQL
  run on every PR.
- `main` is protected — changes land through a reviewed PR, not a direct push.

## Reporting security issues

Do **not** open a public issue for a vulnerability in the extension itself.
See [SECURITY.md](SECURITY.md).

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By
participating you agree to uphold it.
