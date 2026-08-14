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

# 1c. Download commonmark-java (Markdown parsing for report sections) if not present.
# Dependency-free single jar (~80KB) -- bundled the same way as OpenPDF, see step 4c.
if [ ! -f "libs/commonmark-0.30.0.jar" ]; then
    echo "[*] Downloading commonmark-java..."
    wget -q -O libs/commonmark-0.30.0.jar "https://repo1.maven.org/maven2/org/commonmark/commonmark/0.30.0/commonmark-0.30.0.jar"
fi

# 1d. Download the official Java MCP SDK (mcp-core) + its minimal required deps, so ICARUS
# can run a local MCP server for AI agents. Deliberately NOT downloading mcp's own default
# transport (needs a servlet container) or com.networknt:json-schema-validator (drags in
# jackson-dataformat-yaml/snakeyaml/itu) -- icarus.mcp hand-rolls the SSE transport against
# JDK's own HttpServer and supplies a permissive JsonSchemaValidator instead. mcp-json-jackson2
# is kept for its McpJsonMapper (correct record<->JSON mapping) without needing the validator
# it also ships (that class just sits unused/unlinked in the fat jar -- see icarus.mcp package
# javadoc for the full rationale).
MCP_LIBS=(
  "io/modelcontextprotocol/sdk/mcp-core/1.1.3/mcp-core-1.1.3.jar"
  "io/modelcontextprotocol/sdk/mcp-json-jackson2/1.1.3/mcp-json-jackson2-1.1.3.jar"
  "com/fasterxml/jackson/core/jackson-databind/2.20.1/jackson-databind-2.20.1.jar"
  "com/fasterxml/jackson/core/jackson-core/2.20.1/jackson-core-2.20.1.jar"
  "com/fasterxml/jackson/core/jackson-annotations/2.20/jackson-annotations-2.20.jar"
  "io/projectreactor/reactor-core/3.7.0/reactor-core-3.7.0.jar"
  "org/reactivestreams/reactive-streams/1.0.4/reactive-streams-1.0.4.jar"
  "org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"
)
for path in "${MCP_LIBS[@]}"; do
    jarfile="libs/$(basename "$path")"
    if [ ! -f "$jarfile" ]; then
        echo "[*] Downloading $(basename "$path")..."
        wget -q -O "$jarfile" "https://repo1.maven.org/maven2/${path}"
    fi
done

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
MCP_CP=$(printf ':libs/%s' "${MCP_LIBS[@]##*/}")
javac -d build_manual/classes \
      -cp "libs/montoya-api-2026.7.jar:libs/openpdf-3.0.5.jar:libs/commonmark-0.30.0.jar${MCP_CP}" \
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
echo "[*] Bundling commonmark-java classes..."
unzip -q -o libs/commonmark-0.30.0.jar -d build_manual/classes -x "META-INF/*"
echo "[*] Bundling MCP SDK classes..."
for path in "${MCP_LIBS[@]}"; do
    unzip -q -o "libs/$(basename "$path")" -d build_manual/classes -x "META-INF/*"
done

# 5. Package into JAR
echo "[*] Packaging JAR..."
cd build_manual/classes
jar cf "../libs/icarus-${VERSION}.jar" .
cd ../..

echo "[+] Build complete!"
echo "[+] Output: $PWD/build_manual/libs/icarus-${VERSION}.jar"
