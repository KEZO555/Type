#!/bin/bash
# SessionStart hook: install the Android SDK so `./gradlew :app:assembleDebug` and
# `./gradlew :app:lintDebug` work in Claude Code on the web (the cloud container ships only a JDK).
#
# Idempotent and non-interactive: the SDK lands in a cached location, so after the first session it
# is reused. Logs go to a file; only a short status line is printed (SessionStart stdout becomes
# session context, so we keep it quiet).
#
# NETWORK: this needs Google's Android hosts, which are NOT in the default "Trusted" allowlist. Set
# the environment's Network access to "Custom", keep the default package managers, and add:
#     dl.google.com
#     maven.google.com
# Without them the SDK download (and Gradle's AndroidX/AGP resolution) fail with "Host not in allowlist".
set -euo pipefail

# Only run in the remote (web) environment; local machines already have their own SDK.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"   # Android command-line tools (stable)
LOG="$HOME/android-sdk-setup.log"
exec 3>"$LOG"   # fd 3 = verbose log

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# 1. Command-line tools (the bootstrap that provides sdkmanager).
if [ ! -x "$SDKMANAGER" ]; then
  echo "Installing Android command-line tools…"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" >&3 2>&1
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp" >&3 2>&1
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

# 2. The SDK packages this project builds against (see app/build.gradle: compileSdk 35, AGP 8.7.1).
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >&3 2>&1 || true
echo "Installing SDK packages (platform-tools, platforms;android-35, build-tools;35.0.0)…"
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" >&3 2>&1

# 3. Make the SDK discoverable by Gradle (local.properties) and by the session shell (env file).
echo "sdk.dir=$ANDROID_HOME" > "$CLAUDE_PROJECT_DIR/local.properties"
{
  echo "export ANDROID_HOME=\"$ANDROID_HOME\""
  echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
} >> "$CLAUDE_ENV_FILE"

echo "Android SDK ready at $ANDROID_HOME (log: $LOG)"
