#!/usr/bin/env bash
# Bold Explorer KMP — one-shot dev environment setup for Debian/Ubuntu (WSL or native).
# Run once: bash setup.sh
set -euo pipefail

echo "==> Bold Explorer KMP setup"

# ── JDK 17 ────────────────────────────────────────────────────────────────
if command -v java &>/dev/null && java -version 2>&1 | grep -q 'version "17\|version "21'; then
    echo "[ok] JDK already present: $(java -version 2>&1 | head -1)"
else
    echo "[..] Installing JDK 17 via apt..."
    sudo apt-get update -q
    sudo apt-get install -y openjdk-17-jdk-headless
    echo "[ok] JDK 17 installed."
fi

# ── Make gradlew executable ────────────────────────────────────────────────
chmod +x gradlew
echo "[ok] gradlew is executable."

# ── Android SDK (command-line tools only, no Android Studio) ───────────────
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -d "$ANDROID_HOME/cmdline-tools" ]; then
    echo "[..] Downloading Android command-line tools..."
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    TMP=$(mktemp -d)
    # URL from https://developer.android.com/studio#command-tools
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
        -o "$TMP/cmdtools.zip"
    unzip -q "$TMP/cmdtools.zip" -d "$TMP"
    mv "$TMP/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$TMP"
    echo "[ok] Android command-line tools installed to $ANDROID_HOME."
else
    echo "[ok] Android command-line tools already present."
fi

# ── Accept SDK licenses ────────────────────────────────────────────────────
export ANDROID_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null
echo "[ok] Android SDK platforms installed."

# ── Shell env snippet ──────────────────────────────────────────────────────
ENV_SNIPPET="
# Android SDK (added by bold-explorer-kmp/setup.sh)
export ANDROID_HOME=\"$ANDROID_HOME\"
export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\"
"

SHELL_RC="$HOME/.bashrc"
if [ -n "${ZSH_VERSION:-}" ] || [ "$(basename "$SHELL")" = "zsh" ]; then
    SHELL_RC="$HOME/.zshrc"
fi

if ! grep -q "ANDROID_HOME" "$SHELL_RC" 2>/dev/null; then
    echo "$ENV_SNIPPET" >> "$SHELL_RC"
    echo "[ok] Added ANDROID_HOME to $SHELL_RC — restart your shell or: source $SHELL_RC"
else
    echo "[ok] ANDROID_HOME already in $SHELL_RC."
fi

# ── ADB wireless / Tailscale setup ────────────────────────────────────────
# You do NOT need a USB cable for day-to-day installs. If your Android device
# and this machine are both on Tailscale, ADB wireless works over the VPN.
#
# One-time pairing (do this once from any network):
#   On the device: Settings → Developer options → Wireless debugging → Pair device with pairing code
#   Then on this machine:
#     adb pair <device-tailscale-ip>:<pairing-port>   # port shown on device screen
#   Enter the 6-digit code shown on the device.
#
# After pairing, connect any time:
#   adb connect <device-tailscale-ip>:<debug-port>    # port shown under "Wireless debugging"
#   adb devices                                        # confirm it shows up
#
# Then `make install` works exactly the same as USB.
#
# Tip: put this alias in your ~/.bashrc:
#   alias adb-phone='adb connect <your-device-tailscale-ip>:<port>'

# ── Verify ─────────────────────────────────────────────────────────────────
echo ""
echo "==> Running Phase 1 gate: ./gradlew :shared:jvmTest"
./gradlew :shared:jvmTest

echo ""
echo "==> Setup complete. Common commands:"
echo "    make test-shared   — run shared module tests"
echo "    make assemble      — build debug APK"
echo "    make install       — push APK to device (USB or ADB-over-Tailscale)"
echo ""
echo "==> ADB over Tailscale (no USB needed):"
echo "    On phone: Developer Options → Wireless debugging → Pair device with pairing code"
echo "    adb pair <phone-tailscale-ip>:<pairing-port>"
echo "    adb connect <phone-tailscale-ip>:<debug-port>"
echo "    make install"
