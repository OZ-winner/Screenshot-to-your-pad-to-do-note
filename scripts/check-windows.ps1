$ErrorActionPreference = "Continue"

Write-Host "== Windows dev environment check =="

function Test-Command($Name) {
  $cmd = Get-Command $Name -ErrorAction SilentlyContinue
  if ($cmd) {
    Write-Host "[OK] $Name -> $($cmd.Source)"
    return $true
  }
  Write-Host "[MISS] $Name"
  return $false
}

$null = Test-Command "node"
$null = Test-Command "npm"
$null = Test-Command "rustc"
$null = Test-Command "cargo"

$vswhere = "C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe"
if (Test-Path $vswhere) {
  $vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
  if ($vsPath) {
    Write-Host "[OK] Visual Studio C++ tools -> $vsPath"
  } else {
    Write-Host "[MISS] Visual Studio C++ tools"
  }
} else {
  Write-Host "[MISS] vswhere.exe"
}

$webview2 = Get-ItemProperty "HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\*" -ErrorAction SilentlyContinue |
  Where-Object { $_.name -like "*WebView2*" } |
  Select-Object -First 1
if ($webview2) {
  Write-Host "[OK] WebView2 Runtime -> $($webview2.pv)"
} else {
  Write-Host "[MISS] WebView2 Runtime"
}

$npmNet = Test-NetConnection registry.npmjs.org -Port 443 -WarningAction SilentlyContinue
Write-Host "[NET] npm registry 443 -> $($npmNet.TcpTestSucceeded)"

$cargoNet = Test-NetConnection index.crates.io -Port 443 -WarningAction SilentlyContinue
Write-Host "[NET] crates.io 443 -> $($cargoNet.TcpTestSucceeded)"

Write-Host "== Done =="
