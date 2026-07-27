#!/bin/bash
set -e

echo "[*] Building ICARUS Burp Extension without build tools..."

# 1. Download Montoya API dependency if not present
mkdir -p libs
if [ ! -f "libs/montoya-api-2025.6.jar" ]; then
    echo "[*] Downloading Montoya API..."
    wget -q -O libs/montoya-api-2025.6.jar "https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/2025.6/montoya-api-2025.6.jar"
fi

# 2. Prepare build directory
echo "[*] Preparing build directories..."
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
      -cp "libs/montoya-api-2025.6.jar" \
      --release 21 \
      @build_manual/sources.txt

# 5. Package into JAR
echo "[*] Packaging JAR..."
cd build_manual/classes
jar cf ../libs/icarus-1.1.1.jar .
cd ../..

echo "[+] Build complete!"
echo "[+] Output: $PWD/build_manual/libs/icarus-1.1.1.jar"
