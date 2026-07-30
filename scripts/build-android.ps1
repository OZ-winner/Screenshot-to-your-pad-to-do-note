$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $repo "android"
$studioJbr = "D:\AndroidStudio\jbr"

if (!(Test-Path $studioJbr)) {
  throw "Android Studio JBR not found at $studioJbr"
}

$gradleBat = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.10.2-bin" -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
  Select-Object -First 1 -ExpandProperty FullName

if (!$gradleBat) {
  throw "Gradle 8.10.2 is not downloaded yet. Open Android Studio once or retry Gradle sync."
}

$env:JAVA_HOME = $studioJbr
$env:Path = "$studioJbr\bin;$env:Path"

Set-Location $androidDir
& $gradleBat ":app:assembleDebug" "--stacktrace"

if ($LASTEXITCODE -ne 0) {
  Write-Host ""
  Write-Host "Android build failed. Read the '* What went wrong:' section above for the specific cause." -ForegroundColor Yellow
  Write-Host "Common causes: Maven proxy/network failure, missing SDK package, or Android/Kotlin compile errors." -ForegroundColor Yellow
  exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Android debug APK built successfully." -ForegroundColor Green
Write-Host "APK: $androidDir\app\build\outputs\apk\debug\app-debug.apk"
