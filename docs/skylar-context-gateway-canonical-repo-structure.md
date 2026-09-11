# Skylar Context Gateway — Canonical Repository Structure

**Repo:** `inscope-labs/abx-server-1`
**Principle:** every file or component that is part of Skylar — Android app code
*or* anything that ultimately runs on the OCI VM — has exactly one home in this
repository. Nothing Skylar-related is ever hand-authored or hand-edited on the
OCI VM itself; the VM is a **deploy target**, never a source of truth. A
deploy script copies/renders what lives here onto the VM; it never reads
config back off the VM into the repo.

This intentionally does **not** absorb Starlight, SFM (`abx-sfm-1`), xtools,
or the mailbox test app into this repo — those remain separate repos because
Android's UID isolation requires them to be physically separate installed
apps, which is a platform constraint, not a documentation one. What *does*
move into this repo is the protocol code they all depend on (the shared
envelope library), authored once here and published out for them to consume.

```
abx-server-1/
├── README.md
├── LICENSE
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── version.properties                   # manual: versionMajor / versionMinor
├── version-build.properties             # CI-owned only: versionCode / versionDebug
├── AGENTS.md                            # drift-check + protected-path rules
│
├── .github/
│   └── workflows/
│       ├── android-ci.yml               # assembleDebug / testDebugUnitTest / lint — JVM only, no emulator
│       ├── release-apk.yml
│       ├── envelope-lib-publish.yml     # publishes libs/skylar-envelope to the package registry
│       └── infra-deploy.yml             # manually-triggered push of infra/ to the OCI VM
│
├── app/                                 # Skylar Core — the Android app itself
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── kotlin/com/inscopelabs/abx/skylar/
│       │   │   ├── core/                # verify → authorize → route → audit pipeline
│       │   │   ├── mesh/                # embedded libtailscale lifecycle
│       │   │   ├── ipc/                 # AIDL clients to Starlight/SFM, xtools bridge
│       │   │   ├── policy/              # loads the signed artefacts built in /policy
│       │   │   └── audit/
│       │   ├── res/
│       │   └── assets/
│       │       └── policy/              # signed policy bundled at build time — copied
│       │                                 # in from /policy/dist, never authored here directly
│       ├── test/                        # JVM unit tests
│       └── androidTest/                 # instrumented — run manually on physical device only
│
├── libs/
│   └── skylar-envelope/                 # shared signed-envelope + policy-container library
│       ├── build.gradle.kts
│       └── src/
│           ├── main/kotlin/com/inscopelabs/abx/skylar/envelope/
│           └── test/kotlin/
│       # Built and versioned here; published as an internal AAR/JAR so Starlight,
│       # SFM, xtools, and the mailbox test app (each its own repo) consume it as
│       # a dependency rather than re-implementing it. The *source* stays
│       # single-homed even though the *consumers* are separate apps by necessity.
│
├── policy/                              # source of truth for signed policy artefacts
│   ├── authorization-matrix.yaml        # caller_id → {capability: scope} — unsigned source
│   ├── routing-table.yaml               # capability → target — unsigned source
│   ├── signing/
│   │   └── sign-policy.sh               # signs the above with the policy signing key
│   └── dist/                            # signed, versioned output — consumed by app/assets
│                                          # and, where relevant, by infra/issuer
│
├── infra/                               # everything that runs on the OCI VM — authored,
│   │                                     # reviewed, and versioned here; deployed out, not
│   │                                     # edited in place on the VM
│   ├── issuer/                          # Issuer service — transport + request-signing
│   │   │                                # credential issuance (Phases 5–6)
│   │   ├── src/
│   │   ├── Dockerfile
│   │   ├── config/
│   │   │   ├── issuer.config.yaml
│   │   │   └── client-entries/          # pre-registered ephemeral-caller client entries
│   │   └── secrets.example.env          # placeholders only — real secrets never committed
│   │
│   ├── relay-forwarder/                 # OCI VM's blind-forward role
│   │   ├── cloudflared/
│   │   │   ├── config.yml
│   │   │   └── tunnel.example.json
│   │   ├── cloudflare-access/
│   │   │   └── access-policy.yaml
│   │   └── tailscale/
│   │       ├── acl-policy.hujson        # restricts the VM to Skylar's forwarding port only
│   │       └── enrollment-notes.md
│   │
│   ├── compose/
│   │   └── docker-compose.oci-vm.yml    # ties Issuer + cloudflared + relay together on the VM
│   │
│   └── deploy/
│       ├── deploy-to-oci-vm.sh          # rsync/ssh push of infra/ onto the VM
│       ├── inventory.yaml               # VM host/connection details (non-secret)
│       └── rollback.sh
│
├── testing/
│   └── mailbox-endpoint/                # scriptable test-oracle Capability Target (Phase 9)
│       ├── build.gradle.kts             # own module → own APK → own UID at install time
│       └── src/main/kotlin/...
│
├── tools/
│   ├── keygen/                          # test-key generation — dev only, never prod keys
│   └── envelope-cli/                    # manual sign/send envelope tool for debugging
│
├── agent-reports/
│   └── <UTC-ISO-timestamp>-<slug>.md    # mandatory per existing AI Studio agent convention
│
└── docs/
    ├── skylar-context-gateway-architecture.md
    ├── skylar-context-gateway-infrastructure.md
    ├── skylar-context-gateway-phased-development-plan.md
    └── adr/                             # architecture decision records (e.g. issuer colocation)
```

## Why this shape

**One repo, two deploy targets.** `app/` builds an APK; `infra/` builds
container/service config for the OCI VM. Both are compiled from the same
commit, so the Android app and the VM-side Issuer/relay can never silently
drift out of sync with each other or with the policy they both depend on.

**The VM never writes back.** `infra/deploy/deploy-to-oci-vm.sh` is one-way:
repo → VM. If someone hand-edits `cloudflared` config or a Tailscale ACL
directly on the VM to fix something urgently, that change is definitionally
temporary — the next deploy overwrites it — which is the intended discipline
for keeping one source of truth rather than the VM slowly accumulating
untracked drift. Any hotfix has to land in `infra/` and re-deploy.

**Secrets stay out entirely.** `infra/issuer/secrets.example.env` and
`infra/relay-forwarder/.../tunnel.example.json` are placeholders committed to
the repo; the real values live only in the secret manager referenced in
Phase 0's deliverables and are injected at deploy time, never committed. This
also means the Issuer/Relay Forwarder key-isolation posture decided in
Phase 5 (same host, separate secret storage) is enforced by the deploy
script writing to two distinct secret locations, not by convention alone.

**`policy/` is the one place authorization and routing decisions get
written.** The unsigned YAML source, the signing step, and the signed output
all live together; both the app (via bundled assets) and the Issuer (if it
needs its own read copy for scope-checking at issuance time) consume the
same `policy/dist/` output rather than each maintaining their own copy.

**`libs/skylar-envelope` is the one deliberate export.** It's the only piece
of this repo that another repo (Starlight, SFM, xtools, mailbox-endpoint)
depends on, and it leaves this repo only as a built, versioned artifact via
`envelope-lib-publish.yml` — never as copy-pasted source. This keeps the
"single source of truth" property intact even though the protocol has
several independent consumers.

**Mailbox app lives here, not in its own repo.** Unlike Starlight/SFM/xtools
(pre-existing products with their own lifecycles), the mailbox endpoint app
exists solely to validate Skylar (Phase 9) and has no purpose outside this
programme — keeping it under `testing/` avoids spinning up a fifth repo for
a component that only Skylar's own test suite will ever use.

## What this changes about the existing docs

- Infrastructure doc (`skylar-context-gateway-infrastructure.md`) §3.2's
  "deploy/configure on the OCI VM" language now has a concrete home:
  `infra/relay-forwarder/` and `infra/issuer/`, pushed by
  `infra/deploy/deploy-to-oci-vm.sh`.
- Phase 5 and Phase 6 of the build plan should reference committing their
  Issuer/relay work under `infra/`, not treating the VM's live state as the
  deliverable.
- Phase 0's "secure secret storage conventions" deliverable maps directly
  onto the `*.example.*` placeholder pattern above.

Want me to fold these repo-path references into the Phase 0 and Phase 5/6
work items of the build plan so agents building from that document know
exactly where to commit each piece?
