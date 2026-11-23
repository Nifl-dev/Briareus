#!/usr/bin/env bash
# bootstrap-operit.sh -- provision OVH for building Operit
# Mirrors .github/workflows/android-build.yml step for step.
# Derived from upstream CI on 2026-11-23. Idempotent where practical.
#
# Usage:  bash bootstrap-operit.sh [repo-dir]
# Default repo: ~/operit-upstream
set -uo pipefail

REPO="${1:-${REPO:-$HOME/operit-upstream}}"
NDK_VER="25.1.8937393"
CMAKE_VER="3.22.1"
API_LEVEL="26"
RUST_VER="1.88.0"
SETUP_DIR="$HOME/operit-setup"

export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VER"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$HOME/.local/node/bin:$HOME/.cargo/bin:$PATH"

log() { printf '\n=== %s ===\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

log "0. preflight"
[ -d "$REPO" ] || die "repo not found: $REPO"
[ -d "$ANDROID_HOME" ] || die "android sdk not found: $ANDROID_HOME"
echo "repo=$REPO"
echo "android_home=$ANDROID_HOME"

# ---------------------------------------------------------------- 1. system
log "1. system packages"
# NOTE: build-essential also provides cc, which rustup probes for on install.
#       python3-venv is required because Debian does not ship ensurepip by
#       default; without it `python3 -m venv` fails and gdown cannot install.
#       GitHub's ubuntu image provides all of these, which is why the CI
#       workflow never mentions them.
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    build-essential pkg-config unzip python3-venv >/dev/null 2>&1
for c in cc gcc g++ make pkg-config unzip; do
    command -v "$c" >/dev/null || die "missing system tool: $c"
done
python3 -c 'import venv' || die "python3 venv module unavailable"

# ---------------------------------------------------------------- 2. sdk
log "2. android sdk packages"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install \
    "platforms;android-34" \
    "build-tools;35.0.0" \
    "ndk;$NDK_VER" \
    "cmake;$CMAKE_VER" 2>&1 | tail -2
[ -d "$ANDROID_NDK_HOME" ] || die "ndk not installed at $ANDROID_NDK_HOME"
[ -d "$ANDROID_HOME/cmake/$CMAKE_VER" ] || die "cmake $CMAKE_VER not installed"

# ---------------------------------------------------------------- 3. submodule
log "3. terminal submodule"
cd "$REPO" || die "cannot cd to repo"
git submodule sync -- terminal 2>&1 | tail -1
git submodule update --init --recursive --depth 1 terminal 2>&1 | tail -2
git submodule status -- terminal

# ---------------------------------------------------------------- 4. drive payload
log "4. google-drive dependency archives (libs + subpack + jniLibs)"
# Guard on PAYLOAD, not directory non-emptiness: a fresh clone has app/libs/.keep
# and assets/subpack/.keep placeholders, so `ls -A` is always non-empty and a
# naive guard silently skips the download (observed 2026-11-23).
need_deps=0
[ -s "$REPO/app/libs/ffmpeg-kit-local.aar" ] || need_deps=1
if [ "$need_deps" -eq 1 ]; then
    VENV="$SETUP_DIR/gdown-venv"
    if [ ! -x "$VENV/bin/python" ]; then
        python3 -m venv "$VENV" || die "venv creation failed (install python3-venv)"
    fi
    "$VENV/bin/pip" install -q --disable-pip-version-check gdown==6.1.0 \
        || die "gdown install failed"
    # The upstream downloader shells out to `gdown` on PATH, not the venv.
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

# ---------------------------------------------------------------- 5. ripgrep
log "5. native ripgrep (rust, aarch64-linux-android)"
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API_LEVEL}-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
[ -x "$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" ] || die "ndk clang linker missing"
rustup target add --toolchain "$RUST_VER" aarch64-linux-android 2>&1 | tail -2
cargo "+$RUST_VER" build \
    --manifest-path tools/native_ripgrep/Cargo.toml \
    --release --target aarch64-linux-android --locked 2>&1 | tail -4
install -Dm755 tools/native_ripgrep/target/aarch64-linux-android/release/liboperit_ripgrep.so \
    app/src/main/jniLibs/arm64-v8a/liboperit_ripgrep.so
test -s app/src/main/jniLibs/arm64-v8a/liboperit_ripgrep.so \
    && echo "ripgrep ok $(stat -c %s app/src/main/jniLibs/arm64-v8a/liboperit_ripgrep.so) bytes" \
    || die "ripgrep build failed"

# ---------------------------------------------------------------- 6. js assets
log "6. javascript deps and asset builds"
npm install -g pnpm@10.34.5 2>&1 | tail -2
npm ci --no-audit --no-fund 2>&1 | tail -3
npm --prefix web-chat ci --no-audit --no-fund 2>&1 | tail -2
npm --prefix annual-report ci --no-audit --no-fund 2>&1 | tail -2
npm run build:webchat 2>&1 | tail -3
npm run build:annualreport 2>&1 | tail -3
npm run build:examples:github 2>&1 | tail -3
python3 tools/example_packages/sync_example_packages.py --no-hot-reload 2>&1 | tail -3

# ---------------------------------------------------------------- 7. local props
log "7. local.properties"
echo "sdk.dir=$ANDROID_HOME" > local.properties
cat local.properties

log "BOOTSTRAP COMPLETE"
echo "Next: cd $REPO && ./gradlew :app:assembleDebug --no-daemon"