#!/usr/bin/env bash
# bootstrap-operit.sh -- provision a Linux box to build Briareus
#
# Reconciled against the live OVH box on 2026-11-24. The previous revision
# claimed to mirror .github/workflows/android-build.yml step for step but had
# drifted in ways that mattered; see "DRIFT" below. This is the description of
# what the box actually needs, not what the workflow happens to do.
#
# Usage:  bash scripts/bootstrap-operit.sh [repo-dir]
# Default repo: ~/briareus
set -uo pipefail

REPO="${1:-${REPO:-$HOME/briareus}}"
RUST_VER="1.88.0"
NODE_VER="22"

# Pinned to what the Gradle build declares, not to what CI installs:
#   app/build.gradle.kts:361   compileSdk = 36      -> platforms;android-36 REQUIRED
#   app/build.gradle.kts:399   ndkVersion = 25.1.8937393  (all native modules agree)
#   gradle/libs.versions.toml  agp = 8.13.2          -> build-tools 34/35 also useful
# DRIFT: the old script installed platforms;android-34 and build-tools;35.0.0
# only, so a fresh box could not satisfy compileSdk = 36 at all.
API_PLATFORMS="android-36 android-34"
BUILD_TOOLS="36.0.0 35.0.0 34.0.0"
NDK_VER="25.1.8937393"
CMAKE_VER="3.22.1"
# API_LEVEL 26 is the rust/musl link target, NOT the platform install level.
ANDROID_API_LEVEL="26"
SETUP_DIR="$HOME/briareus-setup"

# DRIFT: ANDROID_HOME was hardcoded to /opt/android-sdk, which requires root to
# create. Honour an existing SDK if the caller set one, else use the path this
# box was provisioned with.
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VER"
export PATH="$HOME/.local/node/bin:$HOME/.cargo/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

log() { printf '\n=== %s ===\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

log "0. preflight"
[ -d "$REPO" ] || die "repo not found: $REPO (clone Nifl-dev/Briareus first)"
[ -d "$ANDROID_HOME" ] || die "android sdk not found: $ANDROID_HOME"
[ -d "$REPO/.git" ] || die "$REPO is not a git checkout"
echo "repo=$REPO"
echo "android_home=$ANDROID_HOME"
if [ "$REPO" = "$HOME/operit-upstream" ]; then
    echo "WARNING: that is the upstream mirror, not the fork. Pass the Briareus path." >&2
fi

# ---------------------------------------------------------------- 1. system
log "1. system packages"
# python3-venv: Debian does not ship ensurepip, so `python3 -m venv` fails
#   without it and gdown cannot be installed. GitHub's image has it, which is
#   why the CI workflow never mentions it.
# cmake: the `cmake` on PATH is distinct from SDK cmake/3.22.1; native builds
#   resolved through Gradle use the SDK copy, but host tooling probes PATH.
MISSING=""
for c in cc gcc g++ make pkg-config unzip git java; do
    command -v "$c" >/dev/null || MISSING="$MISSING $c"
done
if [ -n "$MISSING" ]; then
    echo "installing missing: $MISSING"
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
        build-essential pkg-config unzip python3-venv \
        cmake openjdk-21-jdk-headless >/dev/null 2>&1 \
        || die "apt-get install failed"
fi
for c in cc gcc g++ make pkg-config unzip; do
    command -v "$c" >/dev/null || die "missing system tool: $c"
done
python3 -c 'import venv' || die "python3 venv module unavailable"

# ---------------------------------------------------------------- 2. sdk
log "2. android sdk packages"
command -v sdkmanager >/dev/null || die "sdkmanager not on PATH; install cmdline-tools first"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
for p in $API_PLATFORMS; do
    sdkmanager --install "platforms;$p" 2>&1 | tail -1
done
for b in $BUILD_TOOLS; do
    sdkmanager --install "build-tools;$b" 2>&1 | tail -1
done
sdkmanager --install "ndk;$NDK_VER" "cmake;$CMAKE_VER" 2>&1 | tail -2
[ -d "$ANDROID_NDK_HOME" ] || die "ndk not installed at $ANDROID_NDK_HOME"
[ -d "$ANDROID_HOME/cmake/$CMAKE_VER" ] || die "cmake $CMAKE_VER not installed"
# The check the old script lacked: without this, `compileSdk = 36` fails late
# and confusingly, after the SDK step has been reported as successful.
for p in $API_PLATFORMS; do
    [ -d "$ANDROID_HOME/platforms/$p" ] || die "platform $p missing after install"
done

# ---------------------------------------------------------------- 3. node
log "3. node $NODE_VER"
# DRIFT: the old script did `npm install -g pnpm` but assumed node already
# existed. It does not on a clean Debian box; node is installed under
# ~/.local/node so it needs no root.
if ! command -v node >/dev/null; then
    NODE_DIR="$HOME/.local/node"
    NODE_TARBALL="node-v22.23.5-linux-x64.tar.xz"
    mkdir -p "$NODE_DIR"
    if [ ! -x "$NODE_DIR/bin/node" ]; then
        ( curl -fsSL "https://nodejs.org/dist/v22.23.5/$NODE_TARBALL" -o "/tmp/$NODE_TARBALL" \
          && tar -xJf "/tmp/$NODE_TARBALL" -C /tmp \
          && cp -a "/tmp/node-v22.23.5-linux-x64/." "$NODE_DIR/" ) \
          || die "node install failed"
    fi
    export PATH="$NODE_DIR/bin:$PATH"
fi
command -v node >/dev/null || die "node still not on PATH"
echo "node $(node --version)"

# ---------------------------------------------------------------- 4. submodules
log "4. git submodules"
# DRIFT: the old script ran `git submodule update --init --depth 1 terminal`,
# but `terminal/` is NOT a submodule in this tree -- it is ordinary tracked
# files. The only submodule is tools/hotbuild/OperitNightlyRelease, and the old
# command therefore initialised nothing while reporting success.
cd "$REPO" || die "cannot cd to repo"
git submodule sync --recursive 2>&1 | tail -1
if git submodule status --recursive 2>/dev/null | grep -q '^-'; then
    git submodule update --init --recursive 2>&1 | tail -2 \
        || echo "WARNING: submodule init failed (network/SSH to github); non-fatal for app build"
fi
git submodule status --recursive 2>&1 | head -3

# ---------------------------------------------------------------- 5. drive payload
log "5. google-drive dependency archives (libs + subpack + jniLibs)"
# Guard on the artefact, not directory non-emptiness: a fresh clone has
# app/libs/.keep and assets/subpack/.keep placeholders, so `ls -A` is always
# non-empty and a naive guard silently skips the download (2026-11-23).
need_deps=0
[ -s "$REPO/app/libs/ffmpeg-kit-local.aar" ] || need_deps=1
if [ "$need_deps" -eq 1 ]; then
    VENV="$SETUP_DIR/gdown-venv"
    if [ ! -x "$VENV/bin/python" ]; then
        python3 -m venv "$VENV" || die "venv creation failed (install python3-venv)"
    fi
    "$VENV/bin/pip" install -q --disable-pip-version-check gdown==6.1.0 \
        || die "gdown install failed"
    # The downloader shells out to `gdown` on PATH, not the venv interpreter.
    PATH="$VENV/bin:$PATH" \
        RUNNER_TEMP="$SETUP_DIR" \
        bash ci/script/download_android_dependencies.sh full "$SETUP_DIR/manual-deps" 2>&1 | tail -5
    python3 ci/script/prepare_android_dependencies.py \
        --profile full \
        --archives "$SETUP_DIR/manual-deps" \
        --repository "$REPO" 2>&1 | tail -5
else
    echo "drive payload already present, skipping"
fi
[ -s "$REPO/app/libs/ffmpeg-kit-local.aar" ] || die "ffmpeg-kit-local.aar still missing"
echo "payload ok: aar $(stat -c %s "$REPO/app/libs/ffmpeg-kit-local.aar") bytes"

# ---------------------------------------------------------------- 6. ripgrep
log "6. native ripgrep (rust, aarch64-linux-android)"
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${ANDROID_API_LEVEL}-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
[ -x "$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" ] || die "ndk clang linker missing"
command -v rustup >/dev/null || die "rustup not on PATH (install rustup, then re-run)"
if ! rustup toolchain list | grep -q "^$RUST_VER"; then
    rustup toolchain install "$RUST_VER" --profile minimal 2>&1 | tail -2 \
        || die "rust $RUST_VER install failed"
fi
rustup target add --toolchain "$RUST_VER" aarch64-linux-android 2>&1 | tail -2
cargo "+$RUST_VER" build \
    --manifest-path tools/native_ripgrep/Cargo.toml \
    --release --target aarch64-linux-android --locked 2>&1 | tail -4
install -Dm755 tools/native_ripgrep/target/aarch64-linux-android/release/liboperit_ripgrep.so \
    app/src/main/jniLibs/arm64-v8a/liboperit_ripgrep.so
test -s app/src/main/jniLibs/arm64-v8a/liboperit_ripgrep.so \
    && echo "ripgrep ok $(stat -c %s app/src/main/jniLibs/arm64-v8a/liboperit_ripgrep.so) bytes" \
    || die "ripgrep build failed"

# ---------------------------------------------------------------- 7. js assets
log "7. javascript deps and asset builds"
command -v pnpm >/dev/null || npm install -g pnpm@10.34.5 2>&1 | tail -2
npm ci --no-audit --no-fund 2>&1 | tail -3
npm --prefix web-chat ci --no-audit --no-fund 2>&1 | tail -2
npm --prefix annual-report ci --no-audit --no-fund 2>&1 | tail -2
# Script names are read from package.json, not guessed: the repo exposes
# build:webchat / build:annualreport / build:examples:github and these match.
npm run build:webchat 2>&1 | tail -3
npm run build:annualreport 2>&1 | tail -3
npm run build:examples:github 2>&1 | tail -3
python3 tools/example_packages/sync_example_packages.py --no-hot-reload 2>&1 | tail -3

# ---------------------------------------------------------------- 8. local props
log "8. local.properties"
# sdk.dir is written only if absent: on the real box this file ALSO carries
# RELEASE_STORE_* signing config, and overwriting it would drop the release
# identity. The old script clobbered it unconditionally.
if [ -f local.properties ] && grep -q '^sdk.dir=' local.properties; then
    echo "local.properties exists with sdk.dir; leaving it untouched"
    sed -n 's/^\(sdk.dir=\).*/\1<kept>/p' local.properties
else
    echo "sdk.dir=$ANDROID_HOME" >> local.properties
    echo "appended sdk.dir=$ANDROID_HOME"
fi

# ---------------------------------------------------------------- 9. hooks
log "9. local git hooks"
# .git/hooks is untracked, so the CJK gate in it is lost on a fresh clone.
if [ -x scripts/install-hooks.sh ]; then
    bash scripts/install-hooks.sh 2>&1 | tail -2
fi

log "BOOTSTRAP COMPLETE"
echo "Next: cd $REPO && ./gradlew :app:assembleDebug --no-daemon"
