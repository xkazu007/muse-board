#!/bin/bash
# Manual APK build for Muse Board — no Gradle required.
#
# Why: the Gradle daemon talks to its client over loopback TCP, and this
# sandbox denies Java processes that TCP ("other_tcp" is Deny in Muse
# settings). Gradle therefore cannot run here at all. This script builds
# the exact same sources with the plain SDK tools instead:
#   aapt2 (resources + manifest) -> kotlinc (Kotlin, in-process) ->
#   d8 (dex) -> zipalign -> apksigner (release keystore).
# On a normal machine `./gradlew assembleRelease` builds the same code;
# the Gradle files are kept as the canonical build.
#
# Needs: JDK 17, Android SDK (cmdline-tools, platform-tools,
# platforms;android-34, build-tools;34.0.0) at $ANDROID_HOME or ~/Android,
# and curl (only to fetch the Kotlin jars the first time).
# Output: android/muse-board-release.apk (release-signed when the keystore
# at ~/.muse-board-keystore/ exists, debug-signed otherwise).
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$APP_DIR/app/src/main"
BUILD="$APP_DIR/app/build/manual"
OUT="$APP_DIR/muse-board-release.apk"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android}"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
LIBS_DIR="${MUSEBOARD_LIBS:-$HOME/.muse-board-libs}"

# App version: read from gradle.properties, the same source Gradle uses.
prop() { sed -n "s/^$1=//p" "$APP_DIR/gradle.properties"; }
VERSION_CODE="$(prop museboard.versionCode)"
VERSION_NAME="$(prop museboard.versionName)"
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || { echo "museboard.version* missing from gradle.properties" >&2; exit 1; }

KOTLIN_VERSION=2.0.21
COROUTINES_VERSION=1.8.1
MAVEN=https://repo.maven.apache.org/maven2

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1" >&2; exit 1; }; }
[ -n "${JAVA_HOME:-}" ] || export JAVA_HOME="$HOME/jdk17"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
fi
export PATH="$JAVA_HOME/bin:$PATH"
need java; need curl; need unzip; need python3
[ -x "$BT/aapt2" ] || { echo "aapt2 not found in $BT — install build-tools;34.0.0" >&2; exit 1; }
[ -f "$PLATFORM" ] || { echo "android.jar not found — install platforms;android-34" >&2; exit 1; }

# --- Kotlin jars (downloaded once, cached in $LIBS_DIR) -------------------
mkdir -p "$LIBS_DIR"
fetch() { # fetch <file> <url>
    if [ ! -s "$LIBS_DIR/$1" ]; then
        echo "downloading $1 ..."
        curl -fSL -o "$LIBS_DIR/$1" "$2"
    fi
}
fetch "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar" \
    "$MAVEN/org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN_VERSION/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"
# Full compiler distribution: the embeddable jar alone is missing runtime
# deps the compiler needs (trove4j, ...). The distribution's lib/ has all
# of them, so the compiler is run with lib/* on its JVM classpath.
if [ ! -d "$LIBS_DIR/kotlinc" ]; then
    echo "downloading kotlin-compiler-$KOTLIN_VERSION.zip ..."
    curl -fSL -o "$LIBS_DIR/kotlin-compiler-$KOTLIN_VERSION.zip" \
        "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
    unzip -q -o "$LIBS_DIR/kotlin-compiler-$KOTLIN_VERSION.zip" -d "$LIBS_DIR"
fi
KOTLIN_HOME="$LIBS_DIR/kotlinc"
fetch "kotlin-stdlib-$KOTLIN_VERSION.jar" \
    "$MAVEN/org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION/kotlin-stdlib-$KOTLIN_VERSION.jar"
fetch "kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar" \
    "$MAVEN/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$COROUTINES_VERSION/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar"
fetch "kotlinx-coroutines-android-$COROUTINES_VERSION.jar" \
    "$MAVEN/org/jetbrains/kotlinx/kotlinx-coroutines-android/$COROUTINES_VERSION/kotlinx-coroutines-android-$COROUTINES_VERSION.jar"
fetch "annotations-13.0.jar" \
    "$MAVEN/org/jetbrains/annotations/13.0/annotations-13.0.jar"

STDLIB="$LIBS_DIR/kotlin-stdlib-$KOTLIN_VERSION.jar"
COROUTINES_CORE="$LIBS_DIR/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar"
COROUTINES_ANDROID="$LIBS_DIR/kotlinx-coroutines-android-$COROUTINES_VERSION.jar"
ANNOTATIONS="$LIBS_DIR/annotations-13.0.jar"
COMPILER="$LIBS_DIR/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"
CP="$PLATFORM:$STDLIB:$COROUTINES_CORE:$COROUTINES_ANDROID:$ANNOTATIONS"

rm -rf "$BUILD"
mkdir -p "$BUILD/compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

# --- 0. Widget layouts: RemoteViews-supported classes only -----------------
# Anything else (a plain <View>, <Space>, androidx.*) builds fine but makes
# the widget fail on the phone with "Couldn't add widget".
python3 - "$SRC"/res/layout/widget_*.xml <<'PY'
import sys, xml.etree.ElementTree as ET
ok = {"LinearLayout", "FrameLayout", "RelativeLayout", "GridLayout", "TextView",
      "Button", "ImageView", "ImageButton", "ProgressBar", "Chronometer",
      "AnalogClock", "TextClock", "ViewFlipper", "ListView", "GridView",
      "StackView", "AdapterViewFlipper", "ViewStub", "include", "merge"}
failed = False
for path in sys.argv[1:]:
    bad = sorted({el.tag for el in ET.parse(path).iter()} - ok)
    if bad:
        print(f"{path}: not allowed in a widget layout: {', '.join(bad)}", file=sys.stderr)
        failed = True
sys.exit(1 if failed else 0)
PY

# --- 1. Compile resources --------------------------------------------------
echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$BUILD/compiled.zip"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" \
    --manifest "$SRC/AndroidManifest.xml" \
    -I "$PLATFORM" \
    --java "$BUILD/gen" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    "$BUILD/compiled.zip"

# --- 2. Compile Kotlin (+ generated R.java) --------------------------------
echo "== kotlinc =="
# shellcheck disable=SC2046
"$JAVA_HOME/bin/java" -Xmx1024m -cp "$KOTLIN_HOME/lib/*" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 17 -no-stdlib \
    -cp "$CP" \
    -d "$BUILD/classes" \
    $(find "$SRC/java" -name '*.kt') $(find "$BUILD/gen" -name '*.java')

# --- 3. Dex ----------------------------------------------------------------
# d8's CLI rejects a bare classes directory ("Unsupported source file
# type"), so jar the compiled classes first.
echo "== jar classes =="
"$JAVA_HOME/bin/jar" --create --file "$BUILD/classes.jar" -C "$BUILD/classes" .

echo "== d8 =="
"$BT/d8" --release --lib "$PLATFORM" --min-api 26 \
    --output "$BUILD/dex" \
    "$BUILD/classes.jar" "$STDLIB" "$COROUTINES_CORE" "$COROUTINES_ANDROID" "$ANNOTATIONS"

# --- 4. Assemble, align, sign ----------------------------------------------
echo "== assemble =="
python3 - "$BUILD/app-unsigned.apk" "$BUILD/dex" <<'EOF'
import glob, sys, zipfile
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_STORED) as z:
    for dex in sorted(glob.glob(dexdir + "/classes*.dex")):
        z.write(dex, dex.split("/")[-1])
print("added:", ", ".join(sorted(glob.glob(dexdir + "/classes*.dex"))))
EOF

"$BT/zipalign" -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

KEYSTORE="$HOME/.muse-board-keystore/muse-board-release.keystore"
if [ -f "$KEYSTORE" ] && [ -f "$HOME/workspace/.muse-board-secrets/secrets.env" ]; then
    # Keystore passwords come from the secrets file (never echoed).
    set -a; . "$HOME/workspace/.muse-board-secrets/secrets.env"; set +a
    "$BT/apksigner" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias "${KEYSTORE_ALIAS:-museboard}" \
        --ks-pass "pass:$KEYSTORE_STORE_PASS" \
        --key-pass "pass:$KEYSTORE_KEY_PASS" \
        --out "$OUT" "$BUILD/app-aligned.apk"
    echo "signed with release keystore"
else
    echo "WARNING: release keystore not found — signing with a throwaway debug key" >&2
    DEBUG_KS="$BUILD/debug.keystore"
    "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$DEBUG_KS" \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 365 \
        -storepass android -keypass android \
        -dname "CN=Android Debug, O=Android" >/dev/null 2>&1
    "$BT/apksigner" sign \
        --ks "$DEBUG_KS" --ks-key-alias androiddebugkey \
        --ks-pass pass:android --key-pass pass:android \
        --out "$OUT" "$BUILD/app-aligned.apk"
fi

echo "== verify =="
"$BT/apksigner" verify --verbose "$OUT"
"$BT/aapt2" dump badging "$OUT" | head -5
echo "APK: $OUT ($(du -h "$OUT" | cut -f1))"
