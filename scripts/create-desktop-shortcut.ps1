$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutName = "$([char]0x622a)$([char]0x56fe)$([char]0x76f4)$([char]0x4f20).lnk"
$shortcutPath = Join-Path $desktop $shortcutName
$releaseExe = Join-Path $repo "src-tauri\target\release\tablet-shot-bridge.exe"
$runningExe = Get-Process -Name "tablet-shot-bridge" -ErrorAction SilentlyContinue |
  Where-Object { $_.Path -and (Test-Path -LiteralPath $_.Path) } |
  Select-Object -First 1 -ExpandProperty Path
$launcher = if ($runningExe) { $runningExe } elseif (Test-Path $releaseExe) { $releaseExe } else { $null }
if (!$launcher) {
  throw "Release executable not found. Install the Windows release or run scripts\build-windows.ps1 first."
}
$icon = Join-Path $repo "src-tauri\icons\icon.ico"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $launcher
$shortcut.Arguments = ""
$shortcut.WorkingDirectory = $repo
$shortcut.Description = "Start ScreenshotToPad"
if (Test-Path $icon) {
  $shortcut.IconLocation = $icon
}
$shortcut.Save()

Write-Host "Created desktop shortcut: $shortcutPath"
