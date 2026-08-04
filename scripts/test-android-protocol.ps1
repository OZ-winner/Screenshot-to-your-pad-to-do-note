$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$kotlinc = "D:\AndroidStudio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"
$javaHome = "D:\AndroidStudio\jbr"
if (!(Test-Path $kotlinc)) {
  throw "Android Studio Kotlin compiler not found: $kotlinc"
}
if (!(Test-Path $javaHome)) {
  throw "Android Studio Java runtime not found: $javaHome"
}
$env:JAVA_HOME = $javaHome
$env:PATH = "$(Join-Path $javaHome 'bin');$env:PATH"

$outputDir = Join-Path $repo "android\build\protocol-tests"
$jar = Join-Path $outputDir "screenshot-protocol-check.jar"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

& $kotlinc `
  (Join-Path $repo "android\app\src\main\java\com\oz\tabletshotbridge\ScreenshotProtocol.kt") `
  (Join-Path $repo "android\protocol-tests\ScreenshotProtocolCheck.kt") `
  -include-runtime `
  -d $jar

if ($LASTEXITCODE -ne 0) {
  throw "Android screenshot protocol compilation failed."
}

& (Join-Path $javaHome "bin\java.exe") -jar $jar
if ($LASTEXITCODE -ne 0) {
  throw "Android screenshot protocol checks failed."
}
