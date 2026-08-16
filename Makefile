# Bold Explorer KMP — common CLI tasks
# Works on Linux/WSL. On Windows use gradlew.bat directly or open a WSL shell.

GW := ./gradlew

# ── Phase 1 ────────────────────────────────────────────────────────────────
.PHONY: test-shared
test-shared:
	$(GW) :shared:jvmTest

.PHONY: test-shared-watch
test-shared-watch:
	$(GW) :shared:jvmTest --continuous

# ── Database tests ─────────────────────────────────────────────────────────
.PHONY: test-db
test-db:
	$(GW) :app:testGoogleDebugUnitTest --tests "com.boldexplorer.db.*"

# ── Full suite ─────────────────────────────────────────────────────────────
.PHONY: test
test:
	$(GW) :shared:jvmTest :app:testGoogleDebugUnitTest

# ── iOS XCFramework (requires macOS + Xcode) ───────────────────────────────
.PHONY: xcframework
xcframework:
	$(GW) :shared:assembleSharedXCFramework

# ── Android build (requires ANDROID_HOME) ──────────────────────────────────
# Two orthogonal dimensions:
#   distribution flavor: google (default; Fused+GNSS, switchable via Debug screen)
#                         / foss (GNSS-only, no Google Play Services compiled in — F-Droid)
#   build type:           debug (dev sideload) / beta (release-signed, debug features on)
#                         / release (production, no debug tab, no coordinates in logs)
# Unprefixed targets stay on the `google` flavor for backward compatibility — it's what's
# actually been built and field-tested against day to day. `-foss` targets are the F-Droid side.
.PHONY: assemble
assemble:
	$(GW) :app:assembleGoogleDebug

.PHONY: assemble-beta
assemble-beta:
	$(GW) :app:assembleGoogleBeta

.PHONY: assemble-release
assemble-release:
	$(GW) :app:assembleGoogleRelease

.PHONY: assemble-foss
assemble-foss:
	$(GW) :app:assembleFossDebug

.PHONY: assemble-foss-beta
assemble-foss-beta:
	$(GW) :app:assembleFossBeta

.PHONY: assemble-foss-release
assemble-foss-release:
	$(GW) :app:assembleFossRelease

.PHONY: install
install:
	$(GW) :app:installGoogleDebug

.PHONY: install-foss
install-foss:
	$(GW) :app:installFossDebug

.PHONY: check-foss
check-foss:
	$(GW) :app:compileFossDebugKotlin

# ── Convenience ────────────────────────────────────────────────────────────
.PHONY: clean
clean:
	$(GW) clean

.PHONY: deps
deps:
	$(GW) dependencies --configuration commonMainImplementation

# ── ADB helpers (wireless / Tailscale) ─────────────────────────────────────
# Set PHONE_IP and PHONE_PORT in your shell (or .bashrc) to use these:
#   export PHONE_IP=100.x.y.z   # Tailscale IP of your Android device
#   export PHONE_PORT=12345      # port shown under Wireless debugging on device

.PHONY: adb-connect
adb-connect:
	adb connect $(PHONE_IP):$(PHONE_PORT)
	adb devices

.PHONY: adb-pair
adb-pair:
	@echo "Enter the IP:pairing-port shown on device, then the 6-digit code:"
	adb pair $(PHONE_IP):$(PAIR_PORT)

.PHONY: logcat
logcat:
	adb logcat -s BoldExplorer:* AndroidRuntime:E

# ── Database backup / restore ──────────────────────────────────────────────
# Needs a debuggable build on the device (make install) — run-as cannot reach a
# beta or release APK's data. Backups are written read-only; see tools/db.sh for
# why the -wal sidecar and force-stop matter.
#
# APP picks the flavor (google, the default, or foss) — they are separate app IDs
# with separate databases and can be installed side by side.

.PHONY: db-backup
db-backup:
	tools/db.sh backup $(if $(APP),--app $(APP))

.PHONY: db-restore
db-restore:
	@test -n "$(FROM)" || { echo "usage: make db-restore FROM=<backup-dir> [APP=google|foss]"; exit 2; }
	tools/db.sh restore "$(FROM)" --force $(if $(APP),--app $(APP))

# ── Formatting ─────────────────────────────────────────────────────────────
.PHONY: fmt
fmt:
	@command -v ktlint >/dev/null 2>&1 || { echo "ktlint not found — install with: curl -sSLO https://github.com/pinterest/ktlint/releases/latest/download/ktlint && chmod +x ktlint && sudo mv ktlint /usr/local/bin/"; exit 1; }
	ktlint --format "**/*.kt"

# ── Static analysis ────────────────────────────────────────────────────────
# If you're iterating on detekt-rules/ itself and stop seeing expected findings, run
# `./gradlew --stop` first — the Gradle daemon can cache a stale ServiceLoader classloader
# for the custom rule jar across invocations within the same daemon process.
.PHONY: lint
lint:
	$(GW) :app:detekt :detekt-rules:test

.PHONY: help
help:
	@echo "make test-shared       — run :shared:jvmTest (Phase 1 gate)"
	@echo "make test              — run all unit tests (google flavor)"
	@echo "make assemble          — build debug APK, google flavor (needs ANDROID_HOME)"
	@echo "make assemble-beta     — build beta APK, google flavor (release-signed, debug features on)"
	@echo "make assemble-release  — build release APK, google flavor (no debug tab, no coordinates)"
	@echo "make assemble-foss         — build debug APK, foss flavor (no Google Play Services — F-Droid)"
	@echo "make assemble-foss-beta    — build beta APK, foss flavor"
	@echo "make assemble-foss-release — build release APK, foss flavor"
	@echo "make check-foss        — compile-only check that the foss flavor still builds"
	@echo "make install           — install debug APK on connected device (google flavor)"
	@echo "make install-foss      — install debug APK on connected device (foss flavor)"
	@echo "make clean             — clean all build outputs"
	@echo "make fmt               — reformat all Kotlin sources with ktlint"
	@echo "make lint              — run detekt (custom a11y rule, covers all flavors) + its own tests"
	@echo "make adb-connect       — connect to phone via Tailscale (set PHONE_IP, PHONE_PORT)"
	@echo "make adb-pair          — pair phone for first-time wireless ADB (set PHONE_IP, PAIR_PORT)"
	@echo "make logcat            — tail app + crash logs"
	@echo "make db-backup [APP=google|foss] — pull the device database (read-only copy, incl. -wal)"
	@echo "make db-restore FROM=<dir> [APP=google|foss] — push a backup back onto the device"
	@echo "make xcframework       — build BoldExplorerShared.xcframework (macOS + Xcode only)"
