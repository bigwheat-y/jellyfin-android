#!/usr/bin/env bash
set -euo pipefail

# 锁定官方源码，Android 接口和本客户端适配代码必须一起升级。
readonly TAILSCALE_REVISION=9b62cdc07e3e2b2981894dbd18798721902273a1
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_ROOT="$PROJECT_ROOT/.tailscale-build"
SOURCE_ROOT="$BUILD_ROOT/source"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GOBIN="$BUILD_ROOT/bin"
export PATH="$GOBIN:$PATH"
export GOTOOLCHAIN=auto
# 部分代理会中断 Go 校验服务的 HTTP/2 流；仅构建进程使用 HTTP/1.1。
export GODEBUG="${GODEBUG:+$GODEBUG,}http2client=0"

command -v go >/dev/null || { echo 'Go is required.' >&2; exit 1; }
command -v javac >/dev/null || { echo 'JDK 21 is required.' >&2; exit 1; }
[[ -d "$ANDROID_HOME/ndk" ]] || [[ -n "${ANDROID_NDK_HOME:-}" ]] || {
    echo 'Install Android NDK (r28+ recommended), then set ANDROID_HOME or ANDROID_NDK_HOME.' >&2
    exit 1
}
mkdir -p "$BUILD_ROOT" "$GOBIN" "$PROJECT_ROOT/app/libs"
if [[ ! -d "$SOURCE_ROOT/.git" ]]; then
    git init "$SOURCE_ROOT"
    git -C "$SOURCE_ROOT" remote add origin https://github.com/tailscale/tailscale-android.git
fi
if ! git -C "$SOURCE_ROOT" cat-file -e "$TAILSCALE_REVISION^{commit}" 2>/dev/null; then
    git -C "$SOURCE_ROOT" fetch --depth 1 origin "$TAILSCALE_REVISION"
fi
git -C "$SOURCE_ROOT" checkout --detach "$TAILSCALE_REVISION"
[[ "$(git -C "$SOURCE_ROOT" rev-parse HEAD)" == "$TAILSCALE_REVISION" ]]
[[ -z "$(git -C "$SOURCE_ROOT" status --porcelain)" ]] || { echo 'Native source must be clean.' >&2; exit 1; }
cd "$SOURCE_ROOT"
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind

# 使用标准 Go 工具链；覆盖现有客户端的全部四种 ABI，避免旧设备缺少 Go 动态库。
# Android 16KB 页面对齐由链接器参数保证，API 23 与客户端最低版本一致。
gomobile bind -target android -androidapi 23 \
    -tags 'not_tailscale_go,ts_omit_cachenetmap' \
    -ldflags '-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' \
    -o "$BUILD_ROOT/libtailscale.aar" ./libtailscale
cp "$BUILD_ROOT/libtailscale.aar" "$PROJECT_ROOT/app/libs/libtailscale.aar"
cp LICENSE "$PROJECT_ROOT/app/src/main/assets/tailscale-LICENSE.txt"
printf 'Built Tailscale engine from %s\n' "$TAILSCALE_REVISION"
