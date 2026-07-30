$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop "截图直传 Windows端.lnk"
$script = Join-Path $repo "scripts\dev-windows.ps1"
$icon = Join-Path $repo "src-tauri\icons\icon.ico"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
$shortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$script`""
$shortcut.WorkingDirectory = $repo
$shortcut.Description = "启动截图直传 Windows 端"
if (Test-Path $icon) {
  $shortcut.IconLocation = $icon
}
$shortcut.Save()

Write-Host "已创建桌面快捷方式：$shortcutPath"
