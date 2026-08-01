$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutName = "$([char]0x542f)$([char]0x52a8)$([char]0x622a)$([char]0x56fe)$([char]0x76f4)$([char]0x4f20)-Windows$([char]0x7aef).lnk"
$shortcutPath = Join-Path $desktop $shortcutName
$launcher = Get-ChildItem -LiteralPath $repo -Filter "*Windows*.bat" |
  Select-Object -First 1 -ExpandProperty FullName
$icon = Join-Path $repo "src-tauri\icons\icon.ico"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $launcher
$shortcut.Arguments = ""
$shortcut.WorkingDirectory = $repo
$shortcut.Description = "Start ScreenshotToPad Windows"
if (Test-Path $icon) {
  $shortcut.IconLocation = $icon
}
$shortcut.Save()

Write-Host "Created desktop shortcut: $shortcutPath"
