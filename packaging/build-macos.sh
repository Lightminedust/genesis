#!/usr/bin/env bash
# Build and check the self-contained macOS release on a Mac.
# The package matches the host CPU (arm64 or x86_64).
set -euo pipefail

if [[ "$(uname -s)" != Darwin ]]; then
    echo 'Run this script on macOS.' >&2
    exit 1
fi

version="${1:-0.4.1}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo 'Version must be major.minor.patch.' >&2
    exit 1
fi

case "$(uname -m)" in
    arm64) arch_name=arm64 ;;
    x86_64) arch_name=x64 ;;
    *) echo "Unsupported CPU: $(uname -m)" >&2; exit 1 ;;
esac

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
output="$repo/dist/macos"
work="$output/_work"
image="$output/Genesis.app"
python="${GENESIS_PYTHON:-$repo/logic/venv/bin/python}"
icon_src="$repo/assets/web-app-manifest-512x512.png"

is_jdk21() {
    local home="$1"
    [[ -n "$home" && -x "$home/bin/java" && -x "$home/bin/jpackage" ]] || return 1
    local raw
    raw="$("$home/bin/java" -version 2>&1 || true)"
    [[ "$raw" == *'"21'* ]]
}

java_home="${JAVA_HOME:-}"
if ! is_jdk21 "$java_home"; then
    java_home=""
    if [[ -x /usr/libexec/java_home ]]; then
        java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    fi
fi
if ! is_jdk21 "$java_home" && command -v brew >/dev/null 2>&1; then
    prefix="$(brew --prefix openjdk@21 2>/dev/null || true)"
    java_home="${prefix:+$prefix/libexec/openjdk.jdk/Contents/Home}"
fi
if ! is_jdk21 "$java_home"; then
    echo 'JDK 21 with jpackage is required. Install it and set JAVA_HOME to that JDK.' >&2
    echo '  brew install openjdk@21' >&2
    echo '  export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"' >&2
    exit 1
fi
export JAVA_HOME="$java_home"
export PATH="$JAVA_HOME/bin:$PATH"

for command in jpackage tar sips iconutil codesign hdiutil ditto; do
    command -v "$command" >/dev/null || { echo "$command is missing." >&2; exit 1; }
done
if command -v gradle >/dev/null 2>&1; then
    gradle_cmd=(gradle)
else
    gradle_cmd=("$repo/sim/gradlew")
    [[ -x "$repo/sim/gradlew" ]] || chmod +x "$repo/sim/gradlew"
fi
[[ -x "$python" ]] || { echo "Python environment is missing: $python" >&2; exit 1; }
[[ -f "$icon_src" ]] || { echo "macOS icon source is missing: $icon_src" >&2; exit 1; }

mkdir -p "$output"
for target in "$work" "$image"; do
    case "$target" in "$output/"*) rm -rf -- "$target" ;;
        *) echo "Refusing to remove outside $output: $target" >&2; exit 1 ;;
    esac
done
mkdir -p "$work/proto"

"$python" -c 'import grpc, numpy, scipy, mendeleev, radioactivedecay, pynucastro, chemicals, periodictable, PyInstaller'
"$python" -m grpc_tools.protoc -I"$repo/core" \
    --python_out="$work/proto" --grpc_python_out="$work/proto" \
    "$repo/core/genesis.proto"

"$python" -m PyInstaller --noconfirm --clean --onedir \
    --name GenesisEngine --paths "$work/proto" --paths "$repo/logic" \
    --collect-submodules numpy.core \
    --collect-all mendeleev --collect-all radioactivedecay --collect-all pynucastro --collect-all periodictable \
    --add-data "$repo/logic/chainlab/data:chainlab/data" \
    --add-data "$repo/logic/moleculelab/data:moleculelab/data" \
    --distpath "$work/python-dist" --workpath "$work/python-work" \
    --specpath "$work/python-spec" "$repo/packaging/macos_engine.py"

engine_dir="$work/python-dist/GenesisEngine"
engine="$engine_dir/GenesisEngine"
[[ -x "$engine" ]] || { echo "Frozen macOS engine is missing: $engine" >&2; exit 1; }
# jpackage copies the tree without symlinks. A real Python.framework then fails
# codesign --remove-signature, so the engine is stored as ordinary files.
"$python" - "$engine_dir" <<'PY'
import shutil
import sys
from pathlib import Path

root = Path(sys.argv[1])
internal = root / "_internal"
python_lib = internal / "Python"
if python_lib.is_symlink():
    real = python_lib.resolve()
    python_lib.unlink()
    shutil.copy2(real, python_lib)
framework = internal / "Python.framework"
if framework.exists():
    shutil.rmtree(framework)
for path in [item for item in root.rglob("*") if item.is_symlink()]:
    dest = path.resolve()
    if not dest.exists():
        raise SystemExit(f"Dangling symlink: {path}")
    path.unlink()
    if dest.is_file():
        shutil.copy2(dest, path)
    else:
        shutil.copytree(dest, path, symlinks=False)
if any(path.is_symlink() for path in root.rglob("*")):
    raise SystemExit("The frozen engine still contains symlinks.")
PY
# An ad-hoc signature lets the frozen binary run under Gatekeeper on this Mac.
codesign --force --sign - "$engine_dir/_internal/Python"
codesign --force --sign - "$engine"
"$python" "$repo/packaging/smoke_windows_engine.py" "$engine" "$work/proto"

(cd "$repo/sim" && "${gradle_cmd[@]}" :app:installDist --no-daemon)

input="$work/input"
mkdir -p "$input/engine"
cp -a "$repo/sim/app/build/install/app/lib/." "$input/"
cp -a "$work/python-dist/GenesisEngine/." "$input/engine/"
cp "$repo/EULA.txt" "$input/EULA.txt"
[[ -f "$input/app.jar" && -x "$input/engine/GenesisEngine" ]] || {
    echo 'The Java client or the bundled engine is missing.' >&2
    exit 1
}

iconset="$work/Genesis.iconset"
icon="$work/Genesis.icns"
mkdir -p "$iconset"
sips -z 16 16 "$icon_src" --out "$iconset/icon_16x16.png" >/dev/null
sips -z 32 32 "$icon_src" --out "$iconset/icon_16x16@2x.png" >/dev/null
sips -z 32 32 "$icon_src" --out "$iconset/icon_32x32.png" >/dev/null
sips -z 64 64 "$icon_src" --out "$iconset/icon_32x32@2x.png" >/dev/null
sips -z 128 128 "$icon_src" --out "$iconset/icon_128x128.png" >/dev/null
sips -z 256 256 "$icon_src" --out "$iconset/icon_128x128@2x.png" >/dev/null
sips -z 256 256 "$icon_src" --out "$iconset/icon_256x256.png" >/dev/null
sips -z 512 512 "$icon_src" --out "$iconset/icon_256x256@2x.png" >/dev/null
sips -z 512 512 "$icon_src" --out "$iconset/icon_512x512.png" >/dev/null
sips -z 1024 1024 "$icon_src" --out "$iconset/icon_512x512@2x.png" >/dev/null
iconutil -c icns "$iconset" -o "$icon"

# jpackage refuses a macOS app version whose first component is 0. The release
# version is written into Info.plist after the image exists.
jpackage_version="$version"
if [[ "$version" =~ ^0\. ]]; then
    jpackage_version="1.${version#0.}"
fi

jpackage --type app-image --name Genesis --app-version "$jpackage_version" --vendor Gemmie \
    --input "$input" --dest "$output" --icon "$icon" --main-jar app.jar \
    --main-class org.genesis.app.GenesisLauncher \
    --java-options '-Dprism.vsync=false' --java-options '-Dgenesis.bundled=true' \
    --java-options "-Dgenesis.version=$version" \
    --mac-package-identifier com.gemmie.genesis \
    --mac-package-name Genesis

[[ -x "$image/Contents/MacOS/Genesis" && -x "$image/Contents/app/engine/GenesisEngine" ]] || {
    echo 'The macOS application is incomplete.' >&2
    exit 1
}
plist="$image/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $version" "$plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $version" "$plist"
codesign --force --deep --sign - "$image"

archive="$output/Genesis-$version-macos-$arch_name.zip"
ditto -c -k --keepParent "$image" "$archive"

jpackage --type dmg --name Genesis --app-version "$jpackage_version" --vendor Gemmie \
    --app-image "$image" --dest "$output" \
    --mac-package-identifier com.gemmie.genesis \
    --license-file "$repo/EULA.txt"

dmg="$(find "$output" -maxdepth 1 -type f -name '*.dmg' -print -quit)"
[[ -n "$dmg" ]] || { echo 'The disk image was not created.' >&2; exit 1; }
mv -- "$dmg" "$output/Genesis-$version-macos-$arch_name.dmg"
echo "macOS packages: $archive and $output/Genesis-$version-macos-$arch_name.dmg"
