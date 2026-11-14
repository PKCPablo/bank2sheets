---
status: completed
created: 2026-11-14
task: 2/2
required_gates:
  architect_review: false
  memory_update: true
---

# Copyright license and project metadata implementation plan

**Goal:** Add an explicit MIT license and complete the project metadata in `README.md` and `pom.xml` so the repository is clearly licensed and identifiable from Maven and GitHub.

**Architecture:** This is a documentation and build-metadata change only. The license lives in a root `LICENSE` file, the README gains a `## License` section linking to it, and the Maven POM gains the standard `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, and `<scm>` elements. No Java source, dependency, Maven coordinate, or runtime behavior changes.

**Tech Stack:** Markdown, Maven 3.9.9 / JDK 21 POM metadata, MIT license text.

**Scope classification:** `multi-task`

## Blocking decisions

Resolve these before Task 1. Proposed defaults are used in the task steps below.

| Decision | Proposed default | Notes |
|---|---|---|
| License | **MIT** | Recommended by issue #10 for this personal project. |
| Copyright holder | `PKCPablo` | GitHub handle; replace with the owner's preferred legal name if different. |
| Copyright year | `2026` | Current year. |
| Per-file SPDX headers | **No** | Optional in issue #10; deferred to avoid touching every source file. |
| GitHub description/topics capture | **No** | Optional in issue #10; deferred because it would require repository-admin changes outside the working tree. |

If any default is rejected, update the `LICENSE`, README section, and POM `<licenses>` / `<developers>` fields consistently before committing.

## Tasks

### Task 1: Add MIT LICENSE and README License section

**Objective:** Create the root license file with the chosen holder and year, and expose it from the README.

**Files:**
- Create: `LICENSE`
- Modify: `README.md` (append after `## Google Sheets layout`)
- Validate: `LICENSE`, `README.md`, `git diff --check`

**Step 1: Establish expected behaviour**

The repository currently has no `LICENSE`, `COPYING`, or `NOTICE` file. After this task, `LICENSE` must exist at the repository root and contain the standard MIT license text with `Copyright (c) 2026 PKCPablo` at the top, and `README.md` must end with a `## License` section that names MIT and links to `LICENSE`.

**Step 2: Add or update validation**

No unit test applies to a license file. Validate with file-presence and content checks plus the repository whitespace check:

```powershell
Test-Path LICENSE
Get-Content LICENSE -TotalCount 3
rg -n "^## License$" README.md
git diff --check
```

Expected: `True`; the first three lines are `MIT License`, a blank line, and `Copyright (c) 2026 PKCPablo`; one README heading match; `git diff --check` exits 0 with no output.

**Step 3: Write minimal implementation**

Create `LICENSE` with the canonical MIT text and this copyright line:

```text
MIT License

Copyright (c) 2026 PKCPablo

Permission is hereby granted, free of charge, to any person obtaining a copy
...
```

Append to `README.md`:

```markdown
## License

Released under the [MIT License](LICENSE).

Copyright (c) 2026 PKCPablo.
```

Do not add a license header to every source file; per-file SPDX headers are out of scope for this plan.

**Step 4: Run test to verify pass**

Run: `Test-Path LICENSE; Get-Content LICENSE -TotalCount 3; rg -n "^## License$" README.md; git diff --check`

Expected: license file present with the chosen holder/year, README section present, and no whitespace errors.

**Step 5: Commit**

`git add LICENSE README.md`

`git commit -m "docs: add MIT license"`

---

### Task 2: Add Maven POM project metadata and validate the build

**Objective:** Fill the missing Maven project identification and license/developer/SCM metadata without changing coordinates or dependencies.

**Files:**
- Modify: `pom.xml` (after `<packaging>jar</packaging>`, before `<properties>`)
- Validate: `pom.xml`, `.\scripts\mvn.ps1 test`

**Step 1: Establish expected behaviour**

`pom.xml` currently declares only `groupId`, `artifactId`, `version`, and `packaging`; it has no project name, description, URL, license, developer, or SCM metadata. After this task, Maven must expose that metadata while keeping the existing coordinates and dependency set unchanged. The full test suite must still pass with the 104-test baseline and JaCoCo 80% line-coverage gate.

**Step 2: Add or update validation**

Use the existing Maven build as the validation path; no new tests are required for POM metadata. Capture the baseline before editing if desired, then verify after editing:

```powershell
.\scripts\mvn.ps1 test
git diff --check
```

Expected: `BUILD SUCCESS`, 104 tests pass, JaCoCo line coverage is at least 80%, and `git diff --check` exits 0.

**Step 3: Write minimal implementation**

Insert the following metadata block into `pom.xml` after `<packaging>jar</packaging>` and before `<properties>`, using the confirmed holder in `<developers>`:

```xml
<name>bank2sheets</name>
<description>Personal Java 21 CLI that fetches BBVA transactions through Open Banking (Enable Banking) and idempotently appends them to Google Sheets.</description>
<url>https://github.com/PKCPablo/bank2sheets</url>

<licenses>
  <license>
    <name>MIT License</name>
    <url>https://opensource.org/licenses/MIT</url>
    <distribution>repo</distribution>
  </license>
</licenses>

<developers>
  <developer>
    <id>PKCPablo</id>
    <name>PKCPablo</name>
    <url>https://github.com/PKCPablo</url>
  </developer>
</developers>

<scm>
  <connection>scm:git:https://github.com/PKCPablo/bank2sheets.git</connection>
  <developerConnection>scm:git:https://github.com/PKCPablo/bank2sheets.git</developerConnection>
  <url>https://github.com/PKCPablo/bank2sheets</url>
</scm>
```

Keep the existing `groupId`, `artifactId`, `version`, packaging, properties, dependencies, and plugins unchanged. Do not add email addresses, private keys, credentials, or a Maven Central publishing configuration.

**Step 4: Run test to verify pass**

Run: `.\scripts\mvn.ps1 test`

Expected: build succeeds, 104 tests pass, JaCoCo 80% line-coverage gate passes, and the POM is parsed without warnings or errors.

Run: `git diff --check`

Expected: no whitespace errors.

**Step 5: Commit**

`git add pom.xml`

`git commit -m "build: add Maven project metadata"`

## Required gates

- **architect_review:** false. No architecture boundary, dependency, or runtime contract changes.
- **memory_update:** true. After implementation, record the license choice and holder in `docs/ai/decisions/ADR-0006-license.md` and update `docs/ai/README.md` only if the router needs a new durable-decision reference.
- **Human validation:** none. The Maven test suite is an agent-owned, bounded validation. Repository-admin items (GitHub description/topics) are deferred and out of scope.

## Acceptance criteria

- [ ] `LICENSE` exists at the repository root and matches the confirmed license, holder, and year.
- [ ] `README.md` has a `## License` section linking to `LICENSE`.
- [ ] `pom.xml` contains `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, and `<scm>`, and keeps the existing `groupId`/`artifactId`/`version`.
- [ ] No credentials, private keys, or personal contact details beyond the confirmed copyright holder are added.
- [ ] `git diff --check` is clean and `.\scripts\mvn.ps1 test` passes with 104 tests and the JaCoCo 80% gate.

## Out of scope

- Changing the project version or Maven coordinates.
- Publishing to Maven Central or adding distribution management.
- Re-licensing Gson, JUnit, or other third-party dependencies.
- Adding a Contributor License Agreement or trademark policy.
- Per-file SPDX/copyright headers.
- Updating the GitHub repository description or topics through the GitHub API.
