param(
    [switch]$Rebuild
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "build\libs\sesagent.jar"

if ($Rebuild -or -not (Test-Path $jarPath)) {
    Write-Host "Building shadow jar..."
    & (Join-Path $projectRoot "gradlew.bat") shadowJar
}

if (-not (Test-Path $jarPath)) {
    throw "Build output not found: $jarPath"
}

$installDir = Join-Path $HOME ".sesagent\bin"
New-Item -ItemType Directory -Path $installDir -Force | Out-Null

$targetJar = Join-Path $installDir "sesagent.jar"
Copy-Item -LiteralPath $jarPath -Destination $targetJar -Force

$cmdPath = Join-Path $installDir "sesagent.cmd"
$cmdContent = @"
@echo off
setlocal
java -jar "%~dp0sesagent.jar" %*
"@
Set-Content -Path $cmdPath -Value $cmdContent -Encoding ASCII

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$segments = @()
if (-not [string]::IsNullOrWhiteSpace($userPath)) {
    $segments = $userPath.Split(";") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
}

$normalized = $segments | ForEach-Object { $_.TrimEnd('\') }
$targetNormalized = $installDir.TrimEnd('\')
if ($normalized -notcontains $targetNormalized) {
    $newPath = ($segments + $installDir) -join ";"
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    Write-Host "Added to user PATH: $installDir"
} else {
    Write-Host "User PATH already contains: $installDir"
}

Write-Host ""
Write-Host "Install complete."
Write-Host "Command: sesagent"
Write-Host "If current terminal cannot find it, open a new terminal window."
