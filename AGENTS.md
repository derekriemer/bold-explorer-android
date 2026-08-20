# AGENTS.md

This is the repository entry point for coding agents. Read [STYLE.md](STYLE.md)
before editing code. Load the focused local skill below when its subject applies;
they keep specialised operational guidance out of every task's context.

## Commands

```bash
bash setup.sh             # one-time WSL/Linux setup (JDK 17, Android SDK, ADB)
make test-shared          # fast :shared JVM test suite
make test                 # all unit tests
make test-db              # database repository tests
make assemble             # google debug APK (requires ANDROID_HOME)
make install              # build and install google debug APK
make logcat               # app and crash logs
make adb-connect          # Tailscale ADB (requires PHONE_IP and PHONE_PORT)
```

Run focused tests with `./gradlew :shared:jvmTest --tests "<class>"` or
`./gradlew :app:testDebugUnitTest --tests "<class>"`. Prefer `make test-shared`
for pure Kotlin work. Before hand-off, run the narrowest relevant test; run
`make test` for changes that cross modules or affect broad behaviour.

## Project shape

- `:shared` is pure Kotlin Multiplatform: algorithms, state machines, domain
  models, and repository interfaces. Keep it free of Android and iOS APIs.
- `:app` is Android-specific: Compose UI, Hilt, SQLDelight driver, sensors,
  location, audio, TTS, and DataStore implementations.
- Repository interfaces belong in `:shared`; their implementations and Hilt
  bindings belong in `:app`.
- The sibling `../js-bold-explorer/` is a read-only porting reference. Never
  modify it.

The app serves blind users: audio and semantics are product behaviour, not
polish. State transitions must be announced, live announcements use a polite
live region, controls need 48dp touch targets, and UI work requires a TalkBack
pass.

## Focused skills

- [`.agents/skills/android-distribution/SKILL.md`](.agents/skills/android-distribution/SKILL.md)
  — build variants, signing, F-Droid, and distribution verification.
- [`.agents/skills/bold-explorer-architecture/SKILL.md`](.agents/skills/bold-explorer-architecture/SKILL.md)
  — output/audio routing, persistence, SQLDelight, and architectural boundaries.
- [`.agents/skills/navigation-field-data/SKILL.md`](.agents/skills/navigation-field-data/SKILL.md)
  — analyse audio logs or create/review replayable field-walk scenarios.

Load the architecture skill before changing an output path, audio scheduling,
repository contract, SQLDelight schema, or settings migration. Load the field
data skill only for logs and recorded-walk tests; its privacy requirements are
mandatory for fixtures.

## Collaboration and version control

- Use `jj`, not Git, for normal version-control work. Start a new working
  commit for substantial work unless already on a fresh commit; use an
  imperative 50–72-character commit subject. Inspect `jj diff` before commit.
- Split unrelated work into separate commits with `jj split`; do not include
  pre-existing changes without the owner's direction.
- Parallelise by module/package. Sequence shared interface or event changes
  before their Android consumers; after `.sq` changes, generate the SQLDelight
  interface before compiling Kotlin that uses it.
- Update this entry point, `STYLE.md`, or the relevant skill when its guidance
  changes. `CLAUDE.md` only contains Claude-specific additions.
- Route plans that need to persist before hand-off: use an ADR for an
  architectural decision, `docs/plans/` for a multi-phase project or major
  refactor, and the relevant GitHub issue for a small bugfix or other
  short-lived work. Do not retain scratch plans in the repository.
- Use the next available ADR number when recording an architectural decision.

## Repository conventions

Use `rg` for text search and `fd` for file discovery. Do not use recursive
`grep`. Keep dependency versions in `gradle/libs.versions.toml`, referenced
through `libs.*`; do not add inline versions to Gradle build files.
