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
rm -f libs/openpdf-3*.jar
if [ ! -f "libs/openpdf-2.0.2.jar" ]; then
    echo "[*] Downloading OpenPDF..."
    wget -q -O libs/openpdf-2.0.2.jar "https://repo1.maven.org/maven2/com/github/librepdf/openpdf/2.0.2/openpdf-2.0.2.jar"
fi

# 1c. Download commonmark-java (Markdown parsing for report sections) if not present.
# Dependency-free single jar (~80KB) -- bundled the same way as OpenPDF, see step 4c.
if [ ! -f "libs/commonmark-0.30.0.jar" ]; then
    echo "[*] Downloading commonmark-java..."
    wget -q -O libs/commonmark-0.30.0.jar "https://repo1.maven.org/maven2/org/commonmark/commonmark/0.30.0/commonmark-0.30.0.jar"
fi

if [ ! -f "libs/commons-csv-1.10.0.jar" ]; then
    echo "[*] Downloading commons-csv..."
    wget -q -O libs/commons-csv-1.10.0.jar "https://repo1.maven.org/maven2/org/apache/commons/commons-csv/1.10.0/commons-csv-1.10.0.jar"
fi

EXTRA_LIBS=(
  "com/formdev/flatlaf/3.4.1/flatlaf-3.4.1.jar"
  "com/formdev/flatlaf-extras/3.4.1/flatlaf-extras-3.4.1.jar"
  "com/github/weisj/jsvg/1.4.0/jsvg-1.4.0.jar"
  "com/fifesoft/rsyntaxtextarea/3.3.3/rsyntaxtextarea-3.3.3.jar"
  "org/jsoup/jsoup/1.17.2/jsoup-1.17.2.jar"
)
# Previously compile-only, on the (wrong) assumption Burp's own runtime already exposes
# FlatLaf to extensions -- it doesn't (ClassNotFoundException on FlatLaf$DisabledIconProvider
# the moment FlatSVGIcon's constructor actually runs), so it has to ship in our jar like
# flatlaf-extras already does.
COMPILE_ONLY_LIBS=()
for path in "${EXTRA_LIBS[@]}" "${COMPILE_ONLY_LIBS[@]}"; do
    jarfile="libs/$(basename "$path")"
    if [ ! -f "$jarfile" ]; then
        echo "[*] Downloading $(basename "$path")..."
        wget -q -O "$jarfile" "https://repo1.maven.org/maven2/${path}"
    fi
done

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
EXTRA_CP=$(printf ':libs/%s' "${EXTRA_LIBS[@]##*/}")
COMPILE_ONLY_CP=$(printf ':libs/%s' "${COMPILE_ONLY_LIBS[@]##*/}")
javac -d build_manual/classes \
      -cp "libs/montoya-api-2026.7.jar:libs/openpdf-2.0.2.jar:libs/commonmark-0.30.0.jar:libs/commons-csv-1.10.0.jar${MCP_CP}${EXTRA_CP}${COMPILE_ONLY_CP}" \
      --release 19 \
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
unzip -q -o libs/openpdf-2.0.2.jar -d build_manual/classes -x "META-INF/*"
echo "[*] Bundling commonmark-java classes..."
unzip -q -o libs/commonmark-0.30.0.jar -d build_manual/classes -x "META-INF/*"
echo "[*] Bundling commons-csv classes..."
unzip -q -o libs/commons-csv-1.10.0.jar -d build_manual/classes -x "META-INF/*"
echo "[*] Bundling MCP SDK classes..."
for path in "${MCP_LIBS[@]}"; do
    unzip -q -o "libs/$(basename "$path")" -d build_manual/classes -x "META-INF/*"
done
echo "[*] Bundling EXTRA classes..."
for path in "${EXTRA_LIBS[@]}"; do
    unzip -q -o "libs/$(basename "$path")" -d build_manual/classes -x "META-INF/*"
done

# 5. Package into JAR
echo "[*] Packaging JAR..."
cd build_manual/classes
mkdir -p META-INF/services
echo "icarus.Icarus" > META-INF/services/burp.api.montoya.BurpExtension
jar cf "../libs/icarus-${VERSION}.jar" .
cd ../..

echo "[+] Build complete!"
echo "[+] Output: $PWD/build_manual/libs/icarus-${VERSION}.jar"
