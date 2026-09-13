# Standing Instructions for every agentic Build Agent — Skylar

## 1. Mandatory Process Report on Every Task

This environment provides no way to copy, save, or download your
responses. You MUST record a report for every task you complete, saved
as an actual file in the repository (not just a chat response),
committed and pushed:

Path: agent-reports/<UTC-ISO-timestamp>-<short-task-slug>.md

Always use this single path — never app/agent-reports/ or any other
location. If a report already exists at this path for a prior task,
that is expected; each task still gets its own new timestamped file,
never an overwrite.

The report must include:
- What was asked.
- What you actually changed (files touched, with a diff or summary).
- Any commands you ran and their results.
- Any assumptions you made.
- Any errors, partial failures, or things you were unable to verify.

This folder must NOT be gitignored; it must be pushed to GitHub so it
can be read outside this environment.

## 1.1 Issue Tracking Directories (issues/pending, issues/resolved)

Any rule elsewhere in this document that requires "flagging" a gap does
so by creating a discrete issue file, not only a line in the agent
report. The report line and the issue file are both required — the
report explains what happened in this task; the issue file is the
durable, directly-checkable record that survives independently of any
single report.

**Location:** `issues/pending/` and `issues/resolved/` at repository
root. Neither may be gitignored; both must be pushed to GitHub.

**Filename:** `<file-path-with-slashes-as-underscores>__<ISSUE-TYPE>.md`
e.g. a logging gap in `app/src/main/java/.../ui/MainActivity.kt` becomes
`issues/pending/app_src_main_java_com_inscopelabs_abx_clipinbox_ui_MainActivity.kt__LOGGING-GAP.md`.
One file per (source file, issue type) pair — a file can have multiple
open issues of different types, each its own issue file.

**Issue file contents:**
- Full file path (human-readable, not just encoded in the filename).
- Issue type (e.g. `LOGGING-GAP`, `FILE-SIZE`).
- One-line reason.
- Date flagged and source agent-report filename.

**Before creating a new issue:** check whether one already exists in
`issues/pending/` for this exact (file, type) pair. If it does, do not
create a duplicate — leave the existing one in place.

**Resolving an issue:** `git mv` the file from `issues/pending/` to
`issues/resolved/`, and append a short "RESOLVED" section (date,
resolving agent-report filename, brief note on the fix). Never delete
an issue file outright — the resolved record is the audit trail.

## 2. Version Increment Rule (version.properties)

`version.properties` is exclusively SkylarStudio agent-controlled. CI workflows, or SkylarDev must NEVER write to `version.properties`.

`version.properties` uses the following keys:
- `versionCode` (integer)
- `versionName` (string, e.g. `0.1.0`)
- `debugCode` (zero-padded integer string, e.g. `0003`)

For every task, the AI agent must assess a probability score (0-100) representing the likelihood that the task needs a new debug build.
- If the score is **greater than 75**, increment `versionCode` by 1 and `debugCode` by 1 (preserving its zero-padded width, e.g. `0003` -> `0004`).
- `versionName` stays manual-only and is not auto-incremented by the agent.
- `versionCode` from `version.properties` is the single counter shared by both debug and release builds (passed to Gradle via `-PversionCode`), while `versionName` differs per build type as implemented in the two workflows.
- The mandatory agent process report MUST explicitly state the assessed probability score and the resulting version increment action taken.

## 2.1 Release Tracking (release-code.txt)

`release-code.txt` is owned exclusively by the `build-apk-release.yml` CI workflow.
- It tracks `releaseMajor`, `releaseMinor`, and `releasePatch`.
- `release-code.txt`'s `releasePatch` is incremented and persisted by `build-apk-release.yml` itself after each successful release build — the SkylarStudio agent should never manually edit `releasePatch`.
- It is NOT subject to the agent-only restriction defined in Section 2.
- The `build-apk-release.yml` workflow reads these parameters to compute the release version output for builds.

## 3. Mandatory Logging Standard

Every new Activity, Fragment, feature, or discrete piece of functionality
must implement adequate logging of its own process flow — entry points,
key decision branches, and completion/failure outcomes — sufficient for
someone to reconstruct what happened after the fact from the log file
alone, without needing to reproduce the issue live. Use the existing
Logger facade (com.inscopelabs.abx.skylar.diagnostics.Logger — d/i/w/e)
exactly as it's already used throughout the codebase. Logger is safe to
call from any file regardless of build variant — it resolves to a real
implementation in debug builds and a true no-op in release builds
automatically, so new code never needs to guard calls to it or worry about
whether it's "allowed" to log; just call it the same way existing code
already does.

If a task requires reading, reviewing, or writing to an EXISTING file that
does not already implement adequate logging per the standard above, flag
it: create an issue file per Section 1.1 with type `LOGGING-GAP`, and
reference it in the task's mandatory agent report (per Section 1). This
applies whether or not the file was otherwise in scope for the task's
actual changes — flagging a logging gap does not require fixing it in
the same task unless the task's own scope already covers that file's
logic.

## 3.1 Logging Gap Remediation on Touch

Before starting any task, the agent MUST check `issues/pending/` for any
`LOGGING-GAP` issue naming a file this task is about to read, edit, or
create logic in.

- If a match exists, fixing that file's logging per the Section 3
  standard is added to this task's scope automatically — not deferred,
  not treated as a separate task, even if the prompt itself didn't
  mention it.
- The agent report for this task must state explicitly:
  - "PRIOR LOGGING GAPS FOUND: <issue file> — resolved / not resolved,
    why" for each match.
  - "PRIOR LOGGING GAPS FOUND: none" if no match exists.
- Resolving the gap means moving the issue file to `issues/resolved/`
  per Section 1.1, and the file now meets the Section 3 standard (entry
  points, key decision branches, completion/failure outcomes via the
  `Logger` facade).
- If a matched file cannot be fully remediated within the task's scope
  (e.g. the gap spans logic genuinely unrelated to the task), the agent
  must state why and leave the issue file in `issues/pending/` rather
  than silently ignoring it.

## 4. Single-Responsibility File Discipline

Every `.kt` file must fulfill exactly one of two roles:

- **Orchestrator** — coordinates and delegates (Activities, Fragments,
  ViewModels, Services, UseCases). Contains sequencing and wiring only;
  business logic, parsing, classification, and transformation rules must
  live in a separate Module file and be called, not implemented inline.
- **Module** — implements one cohesive unit of logic (a single class or
  tightly related small set of functions) and does not itself orchestrate
  calls across unrelated domains.

A file that both orchestrates AND implements substantial business logic
inline is a violation regardless of size.

## 4.1 Size-Triggered Compliance Threshold (applies on sight, not just on touch)

One threshold, split by file role, since role determines how much length
implies tangled responsibility versus how much is just intrinsic to the
kind of code:

- **UI files** — files whose primary content is `@Composable` screen or
  component functions (typically under `ui/components/`, `ui/screens/`,
  or equivalent): threshold is **1000 lines**. Declarative UI code
  (modifiers, nested layout scopes, padding, previews) is inherently
  more verbose than logic code without that length implying the file is
  doing too much.
- **Logic files** — everything else: Orchestrators and Modules per
  Section 4 (ViewModels, Activities, Fragments, Services, UseCases,
  repositories, provisioners, managers, etc.): threshold is
  **500 lines**. Length here usually does mean tangled responsibility.

**Files over their threshold** must NOT be included in any task's
scope — not read into, not edited, not extended — except a task
explicitly designated a **restructuring task**.

If a non-restructuring task's scope requires touching a file already
over its threshold, the agent must stop, not proceed, and create an
issue file of type `FILE-SIZE` per Section 1.1 if one doesn't already
exist, stating this explicitly under a "BLOCKED — FILE OVER THRESHOLD:
<file> (<UI/logic>, <line count>/<threshold>)" line in the report,
rather than making the edit anyway.

Before starting any task, check `issues/pending/` for any `FILE-SIZE`
issue naming a file in this task's touch-set. A match adds remediation
to this task's scope automatically, per the same pattern as Section 3.1.

## 4.2 Restructuring Tasks Are Repository-Wide Compliance Audits

Because restructuring means moving or removing code rather than deleting
it outright, the relocated logic carries its compliance state with it —
splitting a file does not by itself fix anything the code was already
failing at. A restructuring task must therefore:

- not add new features or change external behavior;
- split the file along Orchestrator/Module role lines until all
  resulting files are comfortably under the applicable Section 4.1
  threshold for their role (500 lines for logic files, 1000 for UI
  files) where reasonably achievable — the goal is genuine
  single-responsibility separation, not landing just under the number;
- check EVERY file touched by the split — source file, each new
  destination file, and any existing file that imports/calls the code
  being moved — against the full standing AGENTS.md rule set (logging,
  security/redaction boundaries, package/domain isolation, migration
  safety, etc.), not only the rule that triggered the restructuring;
- preserve or add tests sufficient to confirm behavior didn't change as
  a side effect of the split;
- resolve the triggering `FILE-SIZE` issue (move to `issues/resolved/`
  per Section 1.1) and any other issue files that get fixed as a side
  effect of the split;
- report each touched file individually under a "RESTRUCTURING AUDIT:
  <file> — <compliant / gaps found + which rule + issue file created>"
  line, so the audit trail is per-file, not a single pass/fail for the
  whole task.

## 5. Scan Before You Build

Before creating any new module, class, or file implementing a
security-relevant primitive — cryptographic signing/verification,
policy/authorization evaluation, key management, nonce/replay
tracking, or an IPC/AIDL target surface — search the existing codebase
for something that already implements the same concept. This applies
whether the existing version lives in the same directory, a different
module, or a different package.

- If an existing implementation is found, extend or relocate it. Do
  not build a second, parallel one — even if the existing one lives in
  what seems like the "wrong" location for what you're doing.
- If moving the existing implementation to a more correct location is
  itself justified (e.g. promoting an app-local class into a shared
  library module), do that move and update every consumer in the SAME
  task. Never leave the old version in place "for now" alongside the
  new one — two working implementations of the same security concern
  is a drift risk regardless of which one is better written.
- The mandatory agent report (Section 1) must state what was searched
  for and what, if anything, was found, before describing what was
  built. "Searched for existing envelope/signature implementations:
  found `app/core/EnvelopeVerifier.kt`, migrated its consumers to
  `libs/skylar-envelope` instead of duplicating" is the expected shape
  — "built a new X" with no search noted is not sufficient for
  anything security-relevant.

## 6. No Fabricated Stand-ins for Genuinely Separate Systems

Some integration points are defined by the architecture as belonging
to a physically separate, independently-installed application —
Starlight, SFM (`abx-sfm-1`), and xtools are explicitly named as
such in the canonical repo structure doc, specifically because
Android's UID isolation is a platform security boundary, not a
documentation convention.

- Never implement a same-process, same-APK class as a stand-in for one
  of these systems, even temporarily, even for testing, without
  making that fact impossible to miss: the class name itself must say
  "Mock" or equivalent, it must live in a clearly separate package
  (e.g. `.../target/mock/`), and its KDoc must state in its first
  paragraph that it is not the real system and does not exercise the
  real system's process/UID boundary.
- A same-process mock can validate pipeline wiring and interface
  contract shape. It can NEVER validate anything the architecture
  specifically requires cross-process/cross-UID isolation for
  (permission enforcement, non-Skylar-caller rejection, force-stop
  isolation between independent apps). Do not claim a validation
  criterion is satisfied on the strength of a same-process mock test
  if the criterion's own wording depends on process or UID separation
  — check the wording, not just whether a test passed.
- If completing a phase genuinely requires changes in a different
  repository this agent cannot reach, say so explicitly in the agent
  report and stop short of claiming the phase is complete. A partial,
  honestly-labeled result is compliant; a fully-passing test suite
  against a fabricated stand-in, presented as satisfying the real
  criterion, is not.

## 7. Test-Only Code Must Be Structurally Isolated From Production

Any method, constructor, or code path whose purpose is "for internal
testing" or equivalent must live in a test source set
(`src/test/`, `src/androidTest/`) — never in `src/main/`.

- If a genuine constraint forces such code to exist in `src/main/`
  (e.g. it must be reachable across module boundaries a test source
  set can't cross), it must be impossible for production code to
  invoke unintentionally: gate it behind an explicit check that throws
  unless a debug/test build flag is set, or make its danger
  unmistakable in both its name and its KDoc (e.g.
  `unsafeBypassVerificationForTestingOnly`, not `initialize`).
- A convenience overload that silently accepts pre-verified data and
  skips a security check — signature verification, authorization,
  anything this project's architecture treats as mandatory — is not an
  acceptable trade for test convenience if it sits in production
  source with an ordinary-looking name. Prefer changing the test to
  construct real inputs (a real signed artifact, a real keypair) over
  adding a bypass to the class under test, even when that makes the
  test more code. See `SkylarCoreTest`/`SkylarCorePhase3Test` for the
  established pattern: generate an EC keypair, register it, sign a
  real payload.
