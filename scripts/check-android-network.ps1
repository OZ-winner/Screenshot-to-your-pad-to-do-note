$ErrorActionPreference = "Continue"

Write-Host "== Android dependency network check =="

$targets = @(
  "maven.google.com",
  "repo.maven.apache.org",
  "plugins.gradle.org",
  "maven.aliyun.com",
  "repo.huaweicloud.com",
  "mirrors.aliyun.com"
)

foreach ($target in $targets) {
  $result = Test-NetConnection $target -Port 443 -WarningAction SilentlyContinue
  Write-Host ("{0,-28} 443 -> {1}" -f $target, $result.TcpTestSucceeded)
}

$proxy = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
if ($proxy) {
  Write-Host ""
  Write-Host "Windows proxy:"
  Write-Host "ProxyEnable=$($proxy.ProxyEnable)"
  Write-Host "ProxyServer=$($proxy.ProxyServer)"
  if ($proxy.ProxyServer -match "127\.0\.0\.1:(\d+)") {
    $port = [int]$Matches[1]
    $proxyResult = Test-NetConnection 127.0.0.1 -Port $port -WarningAction SilentlyContinue
    Write-Host "Local proxy port $port listening -> $($proxyResult.TcpTestSucceeded)"
  }
}

Write-Host "== Done =="
