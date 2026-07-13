#!/bin/bash
# Builds the RustRover plugin zip.
#
# core/ is a kotlin-toolchain module so its logic can be tested with `./kotlin test`.
# The plugin jar itself is compiled here with kotlinc against the local RustRover
# platform jars, because no declarative build tool supports IDE-local jar dependencies.
#
# The kotlinc version must be able to read the Kotlin metadata in the IDE jars
# (RustRover 2026.x ships metadata up to 2.4.0), so a pinned compiler is provisioned into
# .tools/ instead of relying on whatever is on PATH. Override with KOTLINC=<path>.
set -euo pipefail
cd "$(dirname "$0")"

KOTLIN_COMPILER_VERSION=2.4.0
KOTLIN_COMPILER_SHA256=ba1b9e6eb6ddc3275079224f2e9ea4a2b02eef7d59ce2d38404f04b22613c20a

RUSTROVER_HOME="${RUSTROVER_HOME:-$HOME/Applications/RustRover.app}"
PLATFORM_LIB="$RUSTROVER_HOME/Contents/lib"
if [[ ! -d "$PLATFORM_LIB" ]]; then
    echo "error: RustRover not found at $RUSTROVER_HOME (set RUSTROVER_HOME)" >&2
    exit 1
fi
command -v jar >/dev/null || { echo "error: jar not on PATH (needs a JDK)" >&2; exit 1; }

provision_kotlinc() {
    local dir=".tools/kotlinc-$KOTLIN_COMPILER_VERSION"
    if [[ ! -x "$dir/kotlinc/bin/kotlinc" ]]; then
        local zip=".tools/kotlin-compiler-$KOTLIN_COMPILER_VERSION.zip"
        if [[ ! -f "$zip" ]]; then
            echo "downloading kotlin-compiler $KOTLIN_COMPILER_VERSION ..." >&2
            mkdir -p .tools
            curl -fsSL -o "$zip" \
                "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_COMPILER_VERSION/kotlin-compiler-$KOTLIN_COMPILER_VERSION.zip"
        fi
        echo "$KOTLIN_COMPILER_SHA256  $zip" | shasum -a 256 -c - >&2
        mkdir -p "$dir"
        unzip -q -o "$zip" -d "$dir"
    fi
    echo "$dir/kotlinc/bin/kotlinc"
}

KOTLINC="${KOTLINC:-$(provision_kotlinc)}"

NAME=cargo-auto-features
VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' ide/resources/META-INF/plugin.xml)
OUT=build/plugin

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dist/$NAME/lib"

CLASSPATH=$(printf '%s:' "$PLATFORM_LIB"/*.jar)

echo "compiling against $RUSTROVER_HOME ..."
find core/src ide/src -name '*.kt' -print0 | xargs -0 "$KOTLINC" \
    -jvm-target 21 \
    -no-reflect \
    -classpath "$CLASSPATH" \
    -d "$OUT/classes"

jar --create --file "$OUT/dist/$NAME/lib/$NAME.jar" \
    -C "$OUT/classes" . \
    -C ide/resources .

(cd "$OUT/dist" && zip -qr "$NAME-$VERSION.zip" "$NAME")

echo "plugin zip: $OUT/dist/$NAME-$VERSION.zip"
echo "install: RustRover > Settings > Plugins > gear icon > Install Plugin from Disk"
