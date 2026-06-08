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
	$(GW) :app:testDebugUnitTest --tests "com.boldexplorer.db.*"

# ── Full suite ─────────────────────────────────────────────────────────────
.PHONY: test
test:
	$(GW) :shared:jvmTest :app:testDebugUnitTest

# ── iOS XCFramework (requires macOS + Xcode) ───────────────────────────────
.PHONY: xcframework
xcframework:
	$(GW) :shared:assembleSharedXCFramework

# ── Android build (requires ANDROID_HOME) ──────────────────────────────────
# debug:   dev sideload, debug key, full logging incl. coordinates
# beta:    release-signed, debug tab + coordinate logging on — distribute for testing
# release: production, no debug tab, no coordinates in logs
.PHONY: assemble
assemble:
	$(GW) :app:assembleDebug

.PHONY: assemble-beta
assemble-beta:
	$(GW) :app:assembleBeta

.PHONY: assemble-release
assemble-release:
	$(GW) :app:assembleRelease

.PHONY: install
install:
	$(GW) :app:installDebug

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

# ── Formatting ─────────────────────────────────────────────────────────────
.PHONY: fmt
fmt:
	@command -v ktlint >/dev/null 2>&1 || { echo "ktlint not found — install with: curl -sSLO https://github.com/pinterest/ktlint/releases/latest/download/ktlint && chmod +x ktlint && sudo mv ktlint /usr/local/bin/"; exit 1; }
	ktlint --format "**/*.kt"

.PHONY: help
help:
	@echo "make test-shared       — run :shared:jvmTest (Phase 1 gate)"
	@echo "make test              — run all unit tests"
	@echo "make assemble          — build debug APK (needs ANDROID_HOME)"
	@echo "make assemble-beta     — build beta APK (release-signed, debug features on)"
	@echo "make assemble-release  — build release APK (no debug tab, no coordinates)"
	@echo "make install           — install debug APK on connected device"
	@echo "make clean             — clean all build outputs"
	@echo "make fmt               — reformat all Kotlin sources with ktlint"
	@echo "make adb-connect       — connect to phone via Tailscale (set PHONE_IP, PHONE_PORT)"
	@echo "make adb-pair          — pair phone for first-time wireless ADB (set PHONE_IP, PAIR_PORT)"
	@echo "make logcat            — tail app + crash logs"
	@echo "make xcframework       — build BoldExplorerShared.xcframework (macOS + Xcode only)"
