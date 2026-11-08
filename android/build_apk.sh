#!/usr/bin/env bash
# Build a game's Android APK on macOS/Linux (mirrors build_apk.ps1):
# cmake (NDK) -> lib<game>.so -> aapt2 -> javac/d8 -> zipalign -> apksigner.
# Usage: android/build_apk.sh [--game <game>] [--nce] [--release] [--install]
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

game=p5r
install=0
nce=0
release=0

while [ $# -gt 0 ]; do
    case "$1" in
        --game) game="$2"; shift 2 ;;
        --nce) nce=1; shift ;;
        --release) release=1; shift ;;
        --install) install=1; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ "$game" = hades ]; then priv="$root/private"; else priv="$root/private/$game"; fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
    if [ -d "$HOME/Library/Android/sdk" ]; then
        sdk="$HOME/Library/Android/sdk"
    elif [ -d "/usr/local/lib/android/sdk" ]; then
        sdk="/usr/local/lib/android/sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        sdk="$HOME/Android/Sdk"
    else
        echo "ERROR: Android SDK not found. Set ANDROID_HOME." >&2
        exit 1
    fi
fi

if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    ndk="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    ndk="$ANDROID_NDK_ROOT"
elif [ -d "$sdk/ndk/28.2.13676358" ]; then
    ndk="$sdk/ndk/28.2.13676358"
else
    ndk="$(find "$sdk/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1)"
fi

if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
    echo "ERROR: Android NDK not found in $sdk/ndk." >&2
    exit 1
fi

if [ -d "$sdk/build-tools/35.0.0" ]; then
    bt="$sdk/build-tools/35.0.0"
else
    bt="$(find "$sdk/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1)"
fi

if [ -f "$sdk/platforms/android-35/android.jar" ]; then
    jar="$sdk/platforms/android-35/android.jar"
else
    jar="$(find "$sdk/platforms" -name "android.jar" 2>/dev/null | sort -V | tail -n 1)"
fi

strip_bin="$(find "$ndk/toolchains/llvm/prebuilt" -name "llvm-strip" 2>/dev/null | head -n 1)"
if [ -z "$strip_bin" ]; then
    strip_bin="$(find "$ndk/toolchains/llvm/prebuilt" -name "llvm-objcopy" 2>/dev/null | head -n 1)"
fi
if [ -z "$strip_bin" ]; then
    strip_bin="$(command -v llvm-strip 2>/dev/null || true)"
fi

export VULKAN_SDK="${VULKAN_SDK:-$(brew --prefix vulkan-headers 2>/dev/null || true)}"

if [ "$nce" = 1 ]; then
    out="$priv/apk_nce"
    build="$priv/ba_nce"
    nce_flag="ON"
else
    out="$priv/apk"
    build="$priv/ba"
    nce_flag="OFF"
fi

cmake_args=(
    -S "$root"
    -B "$build"
    -G Ninja
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a
    -DANDROID_PLATFORM=android-29
    -DANDROID_STL=c++_static
    -DNX_GAME="$game"
    -DNX_NCE="$nce_flag"
)
if [ "$release" = 1 ]; then
    cmake_args+=(-DNX_RELEASE=ON)
fi

targets=("$game")
extra_libs=()
if [ "$nce" = 1 ] && [ "$game" = "p5r" ]; then
    harness="$root/private/build/spirv_harness_android/libspirvharness.so"
    if [ ! -f "$harness" ]; then
        echo "ERROR: missing $harness" >&2
        echo "Download or build it once into $harness" >&2
        exit 1
    fi
    targets+=("p5rimport")
    extra_libs+=("$build/libp5rimport.so" "$harness")
fi

cmake "${cmake_args[@]}"
cmake --build "$build" --target "${targets[@]}"

rm -rf "$out"
mkdir -p "$out/lib/arm64-v8a" "$out/res"

if [ -n "$strip_bin" ] && [ -x "$strip_bin" ]; then
    "$strip_bin" --strip-unneeded -o "$out/lib/arm64-v8a/lib${game}.so" "$build/lib${game}.so"
else
    cp "$build/lib${game}.so" "$out/lib/arm64-v8a/lib${game}.so"
fi

for elib in "${extra_libs[@]}"; do
    lib_name="$(basename "$elib")"
    if [ "$lib_name" = "libspirvharness.so" ]; then
        cp "$elib" "$out/lib/arm64-v8a/$lib_name"
    elif [ -n "$strip_bin" ] && [ -x "$strip_bin" ]; then
        "$strip_bin" --strip-unneeded -o "$out/lib/arm64-v8a/$lib_name" "$elib"
    else
        cp "$elib" "$out/lib/arm64-v8a/$lib_name"
    fi
done

res_zips=()
if [ -d "$root/android/res" ]; then
    "$bt/aapt2" compile --dir "$root/android/res" -o "$out/res.zip"
    res_zips+=("$out/res.zip")
fi

manifest="$root/games/$game/AndroidManifest.xml"
icon_res=""
if [ -d "$root/games/$game/icon/res" ]; then
    icon_res="$root/games/$game/icon/res"
elif [ -d "$priv/icon/res" ]; then
    icon_res="$priv/icon/res"
fi

if [ -n "$icon_res" ]; then
    "$bt/aapt2" compile --dir "$icon_res" -o "$out/icon.zip"
    res_zips+=("$out/icon.zip")
    sed 's/<application/<application android:icon="@mipmap\/ic_launcher"/' "$manifest" > "$out/AndroidManifest.xml"
    manifest="$out/AndroidManifest.xml"
fi

"$bt/aapt2" link -o "$out/base.apk" -I "$jar" --manifest "$manifest" "${res_zips[@]}"

# Native libraries into APK
(cd "$out" && zip -q -r base.apk lib/)

# Java classes
java_dir="$root/games/$game/java"
if [ -d "$java_dir" ]; then
    mkdir -p "$out/classes" "$out/dex"
    sources=()
    while IFS= read -r f; do sources+=("$f"); done < <(find "$java_dir" -name '*.java')
    javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$jar" -cp "$jar" -d "$out/classes" "${sources[@]}"
    classes=()
    while IFS= read -r f; do classes+=("$f"); done < <(find "$out/classes" -name '*.class')
    "$bt/d8" --release --min-api 30 --lib "$jar" --output "$out/dex" "${classes[@]}"
    (cd "$out/dex" && zip -q "$out/base.apk" classes.dex)
fi

"$bt/zipalign" -f -p 4 "$out/base.apk" "$out/aligned.apk"

repo_keystore="$root/android/keystore/release.keystore"
if [ -f "$repo_keystore" ]; then
    keystore="$repo_keystore"
else
    keystore="$HOME/.android/debug.keystore"
    if [ ! -f "$keystore" ]; then
        mkdir -p "$(dirname "$keystore")"
        keytool -genkeypair -v -keystore "$keystore" -storepass android -alias androiddebugkey -keypass android \
            -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    fi
fi

"$bt/apksigner" sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
    --out "$out/${game}.apk" "$out/aligned.apk"
"$bt/apksigner" verify "$out/${game}.apk"

echo "APK: $out/${game}.apk ($(du -h "$out/${game}.apk" | cut -f1))"

if [ "$install" = 1 ]; then "$sdk/platform-tools/adb" install -r "$out/${game}.apk"; fi
