# Publishes the Nova addons SmartStorage compiles against into the local Maven repository.
#
# Only `simple-upgrades` is needed: the copy on repo.xenondevs.xyz is stuck at 1.5-alpha.2
# (built against Nova ~0.19) and does not link against Nova 0.24. Logistics is a runtime-only
# soft dependency, so it is deliberately not built here.
#
# Usage:  pwsh -File tools/setup-deps.ps1

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$depsDir = Join-Path $root '.deps'
$repoDir = Join-Path $depsDir 'Nova-Addons'

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set. A JDK 25 toolchain is required.'
}

New-Item -ItemType Directory -Force $depsDir | Out-Null

if (Test-Path (Join-Path $repoDir '.git')) {
    Write-Host "Updating $repoDir ..."
    git -C $repoDir fetch --depth 1 origin main
    git -C $repoDir reset --hard origin/main
} else {
    Write-Host "Cloning Nova-Addons into $repoDir ..."
    # core.longpaths avoids checkout failures on the deeper machines/ sources
    git -c core.longpaths=true clone --depth 1 https://github.com/xenondevs/Nova-Addons.git $repoDir
    git -C $repoDir config core.longpaths true
}

Write-Host 'Publishing :simple-upgrades to mavenLocal ...'
Push-Location $repoDir
try {
    & (Join-Path $repoDir 'gradlew.bat') ':simple-upgrades:publishToMavenLocal' '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw "Gradle publish failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$published = Join-Path $env:USERPROFILE '.m2\repository\xyz\xenondevs\nova\addon\simple-upgrades'
if (Test-Path $published) {
    Write-Host ''
    Write-Host 'Published versions:' -ForegroundColor Green
    Get-ChildItem $published -Directory | ForEach-Object { "  $($_.Name)" }
} else {
    throw "Expected artifacts under $published but found none."
}
