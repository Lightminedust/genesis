#!/usr/bin/env bash
# Build and check the self-contained Linux x86-64 release on a Linux host.
set -euo pipefail

if [[ "$(uname -s)" != Linux ]]; then
    echo 'Run this script on Linux.' >&2
    exit 1
fi

version="${1:-0.3.0}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo 'Version must be major.minor.patch.' >&2
    exit 1
fi

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
output="$repo/dist/linux"
work="$output/_work"
image="$output/Genesis"
python="${GENESIS_PYTHON:-$repo/logic/venv/bin/python}"
icon="$repo/assets/web-app-manifest-512x512.png"

for command in gradle jpackage tar; do
    command -v "$command" >/dev/null || { echo "$command is missing." >&2; exit 1; }
done
[[ -x "$python" ]] || { echo "Python environment is missing: $python" >&2; exit 1; }
[[ -f "$icon" ]] || { echo "Linux icon is missing: $icon" >&2; exit 1; }

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
    --collect-data chainlab --collect-data moleculelab \
    --distpath "$work/python-dist" --workpath "$work/python-work" \
    --specpath "$work/python-spec" "$repo/packaging/linux_engine.py"

engine="$work/python-dist/GenesisEngine/GenesisEngine"
[[ -x "$engine" ]] || { echo "Frozen Linux engine is missing: $engine" >&2; exit 1; }
"$python" "$repo/packaging/smoke_windows_engine.py" "$engine" "$work/proto"

(cd "$repo/sim" && gradle :app:installDist --no-daemon)

input="$work/input"
mkdir -p "$input/engine"
cp -a "$repo/sim/app/build/install/app/lib/." "$input/"
cp -a "$work/python-dist/GenesisEngine/." "$input/engine/"
cp "$repo/EULA.txt" "$input/EULA.txt"
[[ -f "$input/app.jar" && -x "$input/engine/GenesisEngine" ]] || {
    echo 'The Java client or the bundled engine is missing.' >&2
    exit 1
}

jpackage --type app-image --name Genesis --app-version "$version" --vendor Gemmie \
    --input "$input" --dest "$output" --icon "$icon" --main-jar app.jar \
    --main-class org.genesis.app.GenesisLauncher \
    --java-options '-Dprism.vsync=false' --java-options '-Dgenesis.bundled=true'

[[ -x "$image/bin/Genesis" && -x "$image/lib/app/engine/GenesisEngine" ]] || {
    echo 'The portable Linux application is incomplete.' >&2
    exit 1
}

archive="$output/Genesis-$version-linux-x64.tar.gz"
tar -C "$output" -czf "$archive" Genesis

jpackage --type deb --name Genesis --app-version "$version" --vendor Gemmie \
    --app-image "$image" --dest "$output" --linux-package-name genesis \
    --linux-shortcut --license-file "$repo/EULA.txt"

deb="$(find "$output" -maxdepth 1 -type f -name '*.deb' -print -quit)"
[[ -n "$deb" ]] || { echo 'The Debian installer was not created.' >&2; exit 1; }
mv -- "$deb" "$output/Genesis-$version-linux-x64.deb"
dpkg-deb --info "$output/Genesis-$version-linux-x64.deb" >/dev/null
echo "Linux packages: $archive and $output/Genesis-$version-linux-x64.deb"
