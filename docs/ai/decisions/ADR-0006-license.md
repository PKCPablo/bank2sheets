# ADR-0006: License — MIT for bank2sheets

- Status: accepted
- Date: 2026-11-14

## Context

The repository had no `LICENSE`, `COPYING`, or `NOTICE` file, and `pom.xml` declared no project name, description, URL, license, developer, or SCM metadata. GitHub issue #10 asked for an explicit license and complete project metadata so the repository is clearly licensed and identifiable from Maven and GitHub. The owner confirmed the defaults in the implementation plan `plans/2026-11-14_125626-copyright-license-project-metadata.md` on 2026-11-14.

## Decision

- **License:** MIT.
- **Copyright holder:** `PKCPablo` (GitHub handle), year **2026**.
- **Location:** root `LICENSE` with the canonical MIT text, linked from a `## License` section in `README.md`.
- **Build metadata:** `pom.xml` carries `<name>`, `<description>`, `<url>`, `<licenses>` (MIT), `<developers>` (PKCPablo), and `<scm>`; coordinates, version, dependencies, and plugins are unchanged.
- **No per-file SPDX/copyright headers** in source files.
- **No GitHub repository description/topics change** via the API (deferred, repository-admin action outside the working tree).
- No email address, credentials, or other personal contact details are committed.

## Consequences

- The code is distributable under MIT with an explicit, legally identifiable holder and year.
- Maven tooling and GitHub can surface the project name, description, license, and SCM link from the POM.
- Adding per-file license headers remains a possible future change but is not required by MIT and stays out of scope.
- Re-licensing or a copyright-holder change must update `LICENSE`, the README license section, and the POM `<licenses>`/`<developers>` consistently.
