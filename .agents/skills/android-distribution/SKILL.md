---
name: android-distribution
description: Build, sign, verify, or package Bold Explorer's Google and FOSS Android variants, including F-Droid requirements. Do not use for ordinary code changes or tests.
---

# Android distribution

Use this skill for variant-specific builds, signing, release readiness, or
F-Droid work. The app has two orthogonal axes: distribution flavor and build
type, producing `googleDebug`, `googleBeta`, `googleRelease`, `fossDebug`,
`fossBeta`, and `fossRelease`.

## Flavors

`google` (the default) ships Play Services location and has application ID
`com.boldexplorer`. It uses fused and GNSS providers, switchable from the Debug
screen. `foss` has ID `com.boldexplorer.foss`, uses GNSS only, and must have no
Play Services dependency compiled into its APK. The source-set routers give
both flavors the same public API; do not leak a flavor distinction elsewhere.

```bash
make assemble                 # google debug
make install                  # build and install google debug
make assemble-beta            # google beta, release-signed
make assemble-release         # google production release
make assemble-foss            # FOSS debug
make install-foss             # build and install FOSS debug
make assemble-foss-beta       # FOSS beta
make assemble-foss-release    # FOSS production release
make check-foss               # compile check used by CI
./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath | rg -i play-services
```

The final check must print nothing. Compare it with
`googleReleaseRuntimeClasspath` when diagnosing a flavor leak.

## Build types and signing

`debug` exposes debug features and uses the debug key. `beta` also exposes
debug features and coordinate logging, but is release-signed for tester
distribution. `release` hides debug features and coordinates in logs, and is
release-signed.

Beta and release signing reads uncommitted `app/keystore.properties`. Keep it
and the keystore out of version control; loss of the keystore prevents updates
to installed builds. F-Droid re-signs from source, so it does not use this
keystore.

## F-Droid invariant

F-Droid builds `:app:assembleFossRelease`. `foss` must exclude the proprietary
runtime dependency at compile time, not merely avoid using it at runtime.
`googleImplementation(libs.play.services.location)` and the flavor-specific
location router are intentional. Hilt and KSP are open-source build tooling and
do not require flavor scoping.

For release readiness, run `make test`, build the intended variant, then test
GPS, compass, background tracking, headphones, TTS, and TalkBack on device.
