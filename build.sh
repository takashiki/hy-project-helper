#!/usr/bin/env bash
# 不用 Gradle 的极简构建：aapt2 + javac + d8 + apksigner
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-/opt/android-sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-28/android.jar
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}
# 签名文件 release.jks 不进仓库，自己私下保存，构建时放到本目录
KS_PASS=${KS_PASS:-projhelper}

rm -rf build && mkdir -p build/classes build/dex

"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -classpath libs/lunar-1.7.7.jar \
  -d build/classes $(find src -name '*.java')

"$BT/d8" --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class') libs/lunar-1.7.7.jar

python3 - <<'EOF'
import zipfile
with zipfile.ZipFile('build/base.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
EOF

"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

if [ ! -f release.jks ]; then
  keytool -genkeypair -keystore release.jks -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias projhelper -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=projhelper" >/dev/null
fi
"$BT/apksigner" sign --ks release.jks --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out build/projector-helper.apk build/aligned.apk

echo "built: $(pwd)/build/projector-helper.apk"
