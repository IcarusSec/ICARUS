#!/bin/bash
set -e

# Extract version from Icarus.java (single source of truth)
VERSION=$(grep -oP 'VERSION = "\K[^"]+' src/main/java/icarus/Icarus.java)
echo "[*] Building ICARUS Burp Extension v${VERSION}..."

# 1. Download Montoya API dependency if not present
mkdir -p libs
if [ ! -f "libs/montoya-api-2026.7.jar" ]; then
    echo "[*] Downloading Montoya API..."
    wget -q -O libs/montoya-api-2026.7.jar "https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/2026.7/montoya-api-2026.7.jar"
fi

# 1b. Download OpenPDF (PDF export) if not present. Unlike Montoya (provided by Burp at
# runtime), OpenPDF must ship inside our jar -- see step 4c. icu4j is a real Maven dependency
# of OpenPDF but is only needed for RTL/complex-script text layout we don't use; confirmed
# Document/Paragraph/PdfPTable/Image all work fine without it, so it's deliberately not
# downloaded here (it's a ~15MB jar vs. OpenPDF's own ~2MB).
if [ ! -f "libs/openpdf-3.0.5.jar" ]; then
    echo "[*] Downloading OpenPDF..."
    wget -q -O libs/openpdf-3.0.5.jar "https://repo1.maven.org/maven2/com/github/librepdf/openpdf/3.0.5/openpdf-3.0.5.jar"
fi

# 2. Prepare build directory
rm -rf build_manual
mkdir -p build_manual/classes
mkdir -p build_manual/libs

# 3. Find all Java files
echo "[*] Discovering source files..."
find src/main/java -name "*.java" > build_manual/sources.txt
SOURCE_COUNT=$(wc -l < build_manual/sources.txt)
echo "[+] Found $SOURCE_COUNT Java files"

# 4. Compile
echo "[*] Compiling sources..."
javac -d build_manual/classes \
      -cp "libs/montoya-api-2026.7.jar:libs/openpdf-3.0.5.jar" \
      --release 21 \
      @build_manual/sources.txt

# 4b. Copy resources onto the classpath
if [ -d "src/main/resources" ]; then
    cp -r src/main/resources/. build_manual/classes/
fi

# 4c. Bundle OpenPDF's classes into ours -- Burp only loads the one jar we point it to, so
# unlike Montoya (compileOnly, provided by Burp itself) OpenPDF's classes must physically live
# alongside icarus's in the packaged jar. META-INF is excluded so its MANIFEST.MF/module-info
# don't clobber ours.
echo "[*] Bundling OpenPDF classes..."
unzip -q -o libs/openpdf-3.0.5.jar -d build_manual/classes -x "META-INF/*"

# 5. Package into JAR
echo "[*] Packaging JAR..."
cd build_manual/classes
jar cf "../libs/icarus-${VERSION}.jar" .
cd ../..

echo "[+] Build complete!"
echo "[+] Output: $PWD/build_manual/libs/icarus-${VERSION}.jar"
