$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$vswhere = "C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe"
if (!(Test-Path $vswhere)) {
  throw "Visual Studio Installer vswhere.exe not found. Install Visual Studio Build Tools with Desktop development with C++."
}

$vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$vsPath) {
  throw "Visual Studio C++ tools not found. Install Desktop development with C++."
}

$vcvars = Join-Path $vsPath "VC\Auxiliary\Build\vcvars64.bat"
if (!(Test-Path $vcvars)) {
  throw "vcvars64.bat not found at $vcvars"
}

if (!(Test-Path "node_modules")) {
  Write-Host "Installing npm dependencies..."
  npm install
}

Write-Host "Starting Tauri dev with Visual Studio C++ environment..."
cmd /c "`"$vcvars`" && npm run tauri dev"
