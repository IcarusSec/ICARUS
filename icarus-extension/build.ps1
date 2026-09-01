# Windows build for the ICARUS Burp extension. Mirrors build.sh.
# Uses Burp's bundled JDK (its jre/ has javac; no `jar`, so we zip with .NET).
# Run from icarus-extension/:  powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem

# --- locate a JDK: Burp's bundle first, then JAVA_HOME, then PATH ---
$candidates = @(
  (Join-Path $env:LOCALAPPDATA 'Programs\BurpSuite\jre\bin'),
  (Join-Path ${env:ProgramFiles} 'BurpSuitePro\jre\bin'),
  (Join-Path ${env:ProgramFiles} 'BurpSuiteCommunity\jre\bin'),
  $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' })
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'javac.exe')) }
$javacDir = $candidates | Select-Object -First 1
$javac = if ($javacDir) { Join-Path $javacDir 'javac.exe' }
         elseif (Get-Command javac -ErrorAction SilentlyContinue) { 'javac' }
         else { throw "No javac found. Install Burp (bundled JDK) or set JAVA_HOME." }
Write-Host "[*] javac: $javac"

$VERSION = (Select-String -Path 'src\main\java\icarus\Icarus.java' -Pattern 'VERSION = "([^"]+)"').Matches[0].Groups[1].Value
Write-Host "[*] Building ICARUS Burp Extension v$VERSION..."

# --- dependencies (same set/versions as build.sh) ---
New-Item -ItemType Directory -Force -Path libs | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue libs\openpdf-3*.jar
$BUNDLED = @(
  'net/portswigger/burp/extensions/montoya-api/2026.7/montoya-api-2026.7.jar'   # compile-only, provided by Burp
  'com/github/librepdf/openpdf/2.0.2/openpdf-2.0.2.jar'
  'org/commonmark/commonmark/0.30.0/commonmark-0.30.0.jar'
  'org/commonmark/commonmark-ext-gfm-tables/0.30.0/commonmark-ext-gfm-tables-0.30.0.jar'
  'org/apache/commons/commons-csv/1.10.0/commons-csv-1.10.0.jar'
  'com/formdev/flatlaf/3.4.1/flatlaf-3.4.1.jar'
  'com/formdev/flatlaf-extras/3.4.1/flatlaf-extras-3.4.1.jar'
  'com/github/weisj/jsvg/1.4.0/jsvg-1.4.0.jar'
  'com/fifesoft/rsyntaxtextarea/3.3.3/rsyntaxtextarea-3.3.3.jar'
  'org/jsoup/jsoup/1.17.2/jsoup-1.17.2.jar'
  'io/modelcontextprotocol/sdk/mcp-core/1.1.3/mcp-core-1.1.3.jar'
  'io/modelcontextprotocol/sdk/mcp-json-jackson2/1.1.3/mcp-json-jackson2-1.1.3.jar'
  'com/fasterxml/jackson/core/jackson-databind/2.20.1/jackson-databind-2.20.1.jar'
  'com/fasterxml/jackson/core/jackson-core/2.20.1/jackson-core-2.20.1.jar'
  'com/fasterxml/jackson/core/jackson-annotations/2.20/jackson-annotations-2.20.jar'
  'io/projectreactor/reactor-core/3.7.0/reactor-core-3.7.0.jar'
  'org/reactivestreams/reactive-streams/1.0.4/reactive-streams-1.0.4.jar'
  'org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar'
)
foreach ($p in $BUNDLED) {
  $jar = "libs\" + (Split-Path $p -Leaf)
  if (-not (Test-Path $jar)) {
    Write-Host "[*] Downloading $(Split-Path $p -Leaf)..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/$p" -OutFile $jar -UseBasicParsing
  }
}

# --- fresh build dir ---
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue build_manual
$classes = 'build_manual\classes'
New-Item -ItemType Directory -Force -Path $classes, 'build_manual\libs' | Out-Null

# --- sources ---
Write-Host "[*] Discovering source files..."
$srcDirs = @('src\main\java') + $(if (Test-Path 'src\test\java') { 'src\test\java' } else { @() })
$sources = Get-ChildItem -Recurse -Filter *.java -Path $srcDirs | ForEach-Object FullName
[System.IO.File]::WriteAllLines((Join-Path $PWD 'build_manual\sources.txt'), $sources)  # UTF-8, no BOM (javac chokes on a BOM'd @argfile)
Write-Host "[+] Found $($sources.Count) Java files"

# --- compile (montoya + everything else on the classpath; ';' separator on Windows) ---
Write-Host "[*] Compiling sources..."
$cp = ((Get-ChildItem libs\*.jar | ForEach-Object FullName) -join ';')
& $javac -d $classes -cp $cp --release 19 "@build_manual\sources.txt"
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

# --- resources ---
if (Test-Path 'src\main\resources') { Copy-Item -Recurse -Force 'src\main\resources\*' $classes }

# --- bundle dependency classes into ours (Burp loads only our one jar).
#     montoya is compile-only (Burp provides it at runtime) so it's NOT bundled. ---
Write-Host "[*] Bundling dependency classes..."
$tmp = Join-Path $env:TEMP ("icarus-bundle-" + [guid]::NewGuid())
foreach ($jar in Get-ChildItem libs\*.jar) {
  if ($jar.Name -like 'montoya-api-*') { continue }
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  [System.IO.Compression.ZipFile]::ExtractToDirectory($jar.FullName, $tmp)
  Copy-Item -Recurse -Force "$tmp\*" $classes
  Remove-Item -Recurse -Force $tmp
}
# our ServiceLoader entry wins over any bundled META-INF
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path $classes 'META-INF')
New-Item -ItemType Directory -Force -Path (Join-Path $classes 'META-INF\services') | Out-Null
Set-Content -Path (Join-Path $classes 'META-INF\services\burp.api.montoya.BurpExtension') -Value 'icarus.Icarus' -Encoding ascii

# --- package (.jar == .zip; Burp's jre has no `jar`).
#     Build entries by hand: .NET Framework's CreateFromDirectory writes '\' separators,
#     which Java's classloader can't read -- jar entry names must use '/'. ---
Write-Host "[*] Packaging JAR..."
$out = Join-Path $PWD "build_manual\libs\icarus-$VERSION.jar"
Remove-Item -Force -ErrorAction SilentlyContinue $out
$root = (Resolve-Path $classes).Path.TrimEnd('\') + '\'
$zip = [System.IO.Compression.ZipFile]::Open($out, 'Create')
try {
  foreach ($f in Get-ChildItem -Recurse -File $classes) {
    $name = $f.FullName.Substring($root.Length).Replace('\', '/')
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $name)
  }
} finally { $zip.Dispose() }

Write-Host "[+] Build complete!"
Write-Host "[+] Output: $out"
