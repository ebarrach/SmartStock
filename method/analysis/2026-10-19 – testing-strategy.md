# SS-006 — Test Strategy, Quality Assurance, and Issue Tracking

> Historical audit as of 2026-10-19. For current triggers, reproduced tests,
> acceptance matrix and anomaly register, read `2026-10-29 – QUALITY_MATRIX.md`.
> A configured CI step is not evidence of a successful execution.

## 1. CI/CD Workflows and Existing Tests Audit (SS-006.1)

### Overview
The repository contains three GitHub Actions workflows located in `../../.github/workflows`: `ci.yml`, `docs.yml`, and `qodana_code_quality.yml`. Currently, all three are restricted to manual triggers via `on: workflow_dispatch`. No automated event triggers (such as `push` or `pull_request`) are enabled, leaving the `main` branch unprotected against regressions and unverified builds.

### Analyzed Workflows Detail

1. **`ci.yml` (CI - Qualité & Tests):**
   - **Backend Agent (`../../code/smartstock-agent`):**
     - Environment: Ubuntu, JDK 21 (Eclipse Temurin), sbt.
     - Executed steps: Compilation (`sbt compile`), code style verification (`sbt scalafmtCheckAll`), and unit tests (`sbt test`).
   - **Mobile Application (`../../code/mobile-app`):**
     - Environment: Ubuntu, Node.js 22, React Native.
     - Executed steps: Clean dependency installation (`npm ci`), code formatting check (`npm run format:check`), static linting (`npm run lint`), TypeScript type checking (`npx tsc --noEmit`), and Jest unit testing (`npm test -- --runInBand`).

2. **`docs.yml` (Docs - Publier PDFs):**
   - Environment: Ubuntu.
   - Executed steps: Lists the contents of the `../../doc` directory and attempts to archive `UserGuide.pdf` and `ProgrammerGuide.pdf` using `actions/upload-artifact@v4`.
   - Limitation: Does not automatically compile or generate PDFs from source; it relies entirely on pre-existing files.

3. **`qodana_code_quality.yml` (Qodana):**
   - Tool: JetBrains Qodana static analysis (`v2026.2`).
   - Limitation: Configured with `pr-mode: false` and manual dispatch only. It does not run automatically on Pull Requests to enforce code quality gates.

---

## 2. Acceptance Criteria and Test Matrix (SS-006.2)

| Component | Verification Type | Tool / Command | Acceptance Criteria | Current CI State |
| :--- | :--- | :--- | :--- | :--- |
| **Backend (Scala)** | Compilation | `sbt compile` | 0 compilation errors using JDK 21 | Manual trigger |
| **Backend (Scala)** | Formatting | `sbt scalafmtCheckAll` | Full adherence to scalafmt styling rules | Manual trigger |
| **Backend (Scala)** | Unit Tests | `sbt test` | 100% of unit tests pass with zero failures | Manual trigger |
| **Mobile (React Native)** | Type Safety | `npx tsc --noEmit` | 0 TypeScript type errors | Manual trigger |
| **Mobile (React Native)** | Linting & Format | `npm run lint` & `npm run format:check` | 0 ESLint and Prettier warnings or errors | Manual trigger |
| **Mobile (React Native)** | Unit Tests | `npm test -- --runInBand` | All Jest tests complete successfully | Manual trigger |
| **Documentation** | Asset Packaging | `actions/upload-artifact@v4` | PDF guides are present and archived | Manual trigger |
| **Global Quality** | Static Code Analysis | Qodana Scan | No critical or blocking vulnerabilities | Manual trigger |

---

## 3. Code Review Rules and Issue Tracking (SS-006.3)

### Code Review Guidelines (Pull Requests)
1. **Branch Protection:** Direct commits to the `main` branch are strictly forbidden.
2. **Mandatory CI Passing:** Every Pull Request must trigger and pass all checks in `ci.yml` (Scala and React Native) prior to merging.
3. **Peer Review Approval:** At least one formal approval from a peer reviewer (e.g., Esteban Barracho or Anatelle) is required to merge code.

### Issue Tracking Workflow (GitHub Issues)
Every defect or bug identified must be submitted via a GitHub Issue using the standard template:
- **Title:** `[BUG] <Component> - <Brief summary>`
- **Expected Behavior:** Detailed explanation of what the application should do.
- **Observed Behavior:** Description of the actual incorrect behavior (include error logs/screenshots).
- **Steps to Reproduce:**
  1. Go to '...'
  2. Click on '....'
  3. Observe error '....'
- **Severity Level:** Blocker (prevents build/startup), Major (core functionality broken), or Minor (cosmetic/UI issue).
  
