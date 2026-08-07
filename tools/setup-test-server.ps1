# Creates the local Paper test server in .server/ (gitignored).
#
# Downloads Paper and Nova, builds Simple-Upgrades and Logistics from the Nova-Addons clone that
# tools/setup-deps.ps1 made, and writes a test-friendly server.properties.
#
# It deliberately does NOT write eula.txt: accepting Mojang's EULA is the operator's call.
# See https://aka.ms/MinecraftEULA — then put `eula=true` in .server/eula.txt yourself.
#
# Usage:  pwsh -File tools/setup-test-server.ps1

param(
    [string] $PaperVersion = '26.2',
    [string] $NovaVersion = '0.24.0'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$server = Join-Path $root '.server'
# Nova addons are Paper plugins in their own right (they ship a paper-plugin.yml pointing at Nova's
# addon loader), so they live in plugins/ — not in an addons subfolder.
$addons = Join-Path $server 'plugins'
$deps = Join-Path $root '.deps\Nova-Addons'

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set. A JDK 25 toolchain is required.'
}
if (-not (Test-Path $deps)) {
    throw "No Nova-Addons clone at $deps. Run tools/setup-deps.ps1 first."
}

New-Item -ItemType Directory -Force $addons | Out-Null

# --- Paper -------------------------------------------------------------------------------------
Write-Host "Resolving latest Paper build for $PaperVersion ..." -ForegroundColor Cyan
$build = Invoke-RestMethod "https://fill.papermc.io/v3/projects/paper/versions/$PaperVersion/builds/latest"
$download = $build.downloads.'server:default'
$paperJar = Join-Path $server $download.name

if (-not (Test-Path $paperJar)) {
    Write-Host "Downloading $($download.name) ..." -ForegroundColor Cyan
    & curl.exe -L --fail --progress-bar -o $paperJar $download.url
    if ($LASTEXITCODE -ne 0) { throw 'Paper download failed' }
} else {
    Write-Host "$($download.name) already present"
}

# --- Nova --------------------------------------------------------------------------------------
$novaJar = Join-Path $server "plugins\Nova-$NovaVersion.jar"
if (-not (Test-Path $novaJar)) {
    $url = "https://github.com/xenondevs/Nova/releases/download/$NovaVersion/Nova-$NovaVersion%2BMC-$PaperVersion.jar"
    Write-Host "Downloading Nova $NovaVersion ..." -ForegroundColor Cyan
    & curl.exe -L --fail --progress-bar -o $novaJar $url
    if ($LASTEXITCODE -ne 0) { throw 'Nova download failed' }
} else {
    Write-Host "Nova $NovaVersion already present"
}

# --- Addons ------------------------------------------------------------------------------------
Write-Host 'Building Simple-Upgrades and Logistics ...' -ForegroundColor Cyan
Push-Location $deps
try {
    & (Join-Path $deps 'gradlew.bat') ':simple-upgrades:addonJar' ':logistics:addonJar' "-PoutDir=$addons" '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

# --- server.properties -------------------------------------------------------------------------
$properties = Join-Path $server 'server.properties'
if (-not (Test-Path $properties)) {
    Write-Host 'Writing server.properties ...' -ForegroundColor Cyan
    @'
# Test server for the SmartStorage addon.
# Superflat, creative, peaceful: the point is to place blocks and click menus, not to survive.
level-name=world
level-type=minecraft:flat
generate-structures=false
gamemode=creative
force-gamemode=true
difficulty=peaceful
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
spawn-protection=0
online-mode=true
max-players=4
view-distance=6
simulation-distance=4
motd=SmartStorage test server
enable-command-block=false
allow-flight=true
sync-chunk-writes=false
'@ | Out-File -Encoding utf8 $properties
}

Write-Host ''
if (-not (Test-Path (Join-Path $server 'eula.txt'))) {
    Write-Host 'Next: accept the Minecraft EULA by putting `eula=true` in .server\eula.txt' -ForegroundColor Yellow
    Write-Host '      https://aka.ms/MinecraftEULA' -ForegroundColor Yellow
}
Write-Host 'Then start it with: pwsh -File tools/run-server.ps1' -ForegroundColor Green
