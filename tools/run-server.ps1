# Builds SmartStorage into the local test server and starts it.
#
# The server itself lives in .server/ and is gitignored. See tools/setup-test-server.ps1 for how it
# was created.
#
# Usage:  pwsh -File tools/run-server.ps1
#         pwsh -File tools/run-server.ps1 -SkipBuild

param(
    [switch] $SkipBuild,
    # Keeps Nova's cached resource pack instead of forcing a rebuild.
    #
    # The rebuild is on by default because two things bite otherwise. Nova only persists the pack's
    # download URL during a graceful shutdown, so any hard kill loses it — and it only mints a new one
    # when its resources hash changes, which does not cover everything an addon can ship (language files,
    # for one). The result is clients silently stop receiving the pack. Ten seconds of rebuild per start
    # is a cheap price for "what the client sees is what I just built".
    [switch] $KeepPack,
    [string] $Memory = '4G'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$server = Join-Path $root '.server'

if (-not (Test-Path $server)) {
    throw "No test server at $server. Run tools/setup-test-server.ps1 first."
}

$paper = Get-ChildItem $server -Filter 'paper-*.jar' | Select-Object -First 1
if (-not $paper) {
    throw "No paper-*.jar in $server."
}

$nova = Get-ChildItem (Join-Path $server 'plugins') -Filter 'Nova-*.jar' | Select-Object -First 1
if (-not $nova) {
    throw "No Nova-*.jar in $server\plugins."
}

if (-not $SkipBuild) {
    # The jar carries the version in its name, so a version bump leaves the previous one sitting beside
    # the new one and Nova loads the same addon twice. What that looks like is not "duplicate addon" but
    # the server dying during registry freeze with a missing Nova config entry, which sends you looking
    # in entirely the wrong place. Clear ours out first; every other jar here is somebody else's.
    Get-ChildItem (Join-Path $server 'plugins') -Filter 'SmartStorage-*.jar' | ForEach-Object {
        Write-Host "Removing previous $($_.Name)" -ForegroundColor DarkGray
        Remove-Item $_.FullName -Force
    }

    Write-Host 'Building SmartStorage into the test server ...' -ForegroundColor Cyan
    Push-Location $root
    try {
        & (Join-Path $root 'gradlew.bat') addonJar "-PoutDir=$server\plugins"
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

# This server is normally stopped by killing the process, so anything Paper has not autosaved is lost.
# At the stock 5 minute interval that silently reverts recent building work — a block placed a minute
# before a restart comes back with its default block state, which reads exactly like it "forgot" its
# orientation. Ten seconds keeps the loss below noticing.
$bukkitYml = Join-Path $server 'bukkit.yml'
if (Test-Path $bukkitYml) {
    $yml = Get-Content $bukkitYml -Raw
    if ($yml -match '(?m)^\s*autosave:\s*6000\s*$') {
        ($yml -replace '(?m)^(\s*)autosave:\s*6000\s*$', '$1autosave: 200') | Out-File -Encoding utf8 $bukkitYml
        Write-Host 'Lowered autosave to 200 ticks so hard restarts do not lose recent world state.' -ForegroundColor Cyan
    }
}

if (-not $KeepPack) {
    $hash = Join-Path $server 'plugins\Nova\.internal_data\storage\resources_hash.json'
    if (Test-Path $hash) {
        Remove-Item $hash
        Write-Host 'Cleared the resources hash: Nova will rebuild and re-host the pack.' -ForegroundColor Cyan
    }
}

# Nova instruments the server at load time and refuses to start without this agent
$agent = "-javaagent:plugins/$($nova.Name)"

Write-Host "Starting $($paper.Name) with $agent ..." -ForegroundColor Cyan
Push-Location $server
try {
    & "$env:JAVA_HOME\bin\java.exe" "-Xms1G" "-Xmx$Memory" '-XX:+UseG1GC' $agent '-jar' $paper.FullName 'nogui'
} finally {
    Pop-Location
}
