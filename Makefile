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

# ── Phase 2 (coming) ───────────────────────────────────────────────────────
.PHONY: test-db
test-db:
	$(GW) :app:testDebugUnitTest --tests "*.repository.*"

# ── Full suite ─────────────────────────────────────────────────────────────
.PHONY: test
test:
	$(GW) :shared:jvmTest :app:testDebugUnitTest

# ── Android build (requires ANDROID_HOME) ──────────────────────────────────
.PHONY: assemble
assemble:
	$(GW) :app:assembleDebug

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

.PHONY: help
help:
	@echo "make test-shared   — run :shared:jvmTest (Phase 1 gate)"
	@echo "make test          — run all unit tests"
	@echo "make assemble      — build debug APK (needs ANDROID_HOME)"
	@echo "make install       — install debug APK on connected device"
	@echo "make clean         — clean all build outputs"
