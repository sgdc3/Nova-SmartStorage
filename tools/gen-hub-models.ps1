# Generates the models of the two hubs - the storage connector and the storage interface. Both are
# built the same way: a core that grows an arm towards every device they are wired to and a port against
# every thing they serve.
#
# Three families come out of here, per device:
#
#   block/<hub>/<0-63>    the hub itself. Six boolean state properties say which sides carry a storage
#                         network connection, the tile entity encodes them into an int, and Nova asks
#                         for the matching model. Same bit order and same arm geometry as
#                         tools/gen-cable-models.ps1, deliberately: the arms have to meet a cable's
#                         arms without a seam, so they share the shape, the texture and the per-face
#                         UV rotations.
#
#   block/<hub>/attachment  the port. Not part of the block model - what a hub serves is not block
#                         state, it can appear and vanish without one - so the tile entity renders one
#                         display entity per served side, exactly as Nova's own pipes do. Authored
#                         pointing south, which is what the display entity rotation in
#                         StorageHub.updateAttachments expects.
#
#   item/<hub>            what you hold. Model 0 is a bare core floating in the middle of nothing, so
#                         the item gets a core with one port on it instead.
#
# Usage:  pwsh -File tools/gen-hub-models.ps1

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$modelsDir = Join-Path $root 'src\main\resources\assets\models'
$itemDir = Join-Path $modelsDir 'item'
New-Item -ItemType Directory -Force $itemDir | Out-Null

$TEX_CABLE = 'smartstorage:block/cable'
$TEX_CASING = 'smartstorage:block/storage_casing'

$hubs = @(
    @{ name = 'connector'; core = 'smartstorage:block/storage_connector'; port = 'smartstorage:block/storage_connector_port' }
    @{ name = 'interface'; core = 'smartstorage:block/storage_interface'; port = 'smartstorage:block/storage_interface_port' }
)

# Every family below is written twice, lit and dark, because a hub the controller is not powering should
# not be blinking. A model cannot choose a texture at runtime, so the choice has to be a second model —
# hence `block/<hub>/off/<0-63>` beside `block/<hub>/<0-63>`, and an `attachment_off` beside the port.
$variants = @(
    @{ suffix = ''; texture = '' }
    @{ suffix = 'off/'; texture = '_off' }
)

# The core: 8 units across, so its faces sample the middle 8x8 of the core texture at 1:1.
function New-Core {
    $face = { [ordered]@{ uv = @(4.0, 4.0, 12.0, 12.0); texture = '#1' } }
    return [ordered]@{
        from = @(4.0, 4.0, 4.0)
        to = @(12.0, 12.0, 12.0)
        faces = [ordered]@{
            north = & $face; east = & $face; south = & $face
            west = & $face; up = & $face; down = & $face
        }
    }
}

# Lifted verbatim from tools/gen-cable-models.ps1, rotations included. The rotations are not decorative:
# the cable texture's stripe runs along a 16x3 band, so without them it crosses the arm instead of
# running down it, and the values differ per direction because opposite arms are mirror images.
$arms = @(
    @{ name = 'north'; from = @(6.75, 6.75, 0.0); to = @(9.25, 9.25, 8.0); faces = [ordered]@{ east = 0; west = 0; up = 90; down = 90 } }
    @{ name = 'east'; from = @(8.0, 6.75, 6.75); to = @(16.0, 9.25, 9.25); faces = [ordered]@{ north = 0; south = 0; up = 180; down = 0 } }
    @{ name = 'south'; from = @(6.75, 6.75, 8.0); to = @(9.25, 9.25, 16.0); faces = [ordered]@{ east = 0; west = 0; up = 270; down = 270 } }
    @{ name = 'west'; from = @(0.0, 6.75, 6.75); to = @(8.0, 9.25, 9.25); faces = [ordered]@{ north = 0; south = 0; up = 0; down = 180 } }
    @{ name = 'up'; from = @(6.75, 8.0, 6.75); to = @(9.25, 16.0, 9.25); faces = [ordered]@{ north = 270; east = 270; south = 90; west = 90 } }
    @{ name = 'down'; from = @(6.75, 0.0, 6.75); to = @(9.25, 8.0, 9.25); faces = [ordered]@{ north = 270; east = 90; south = 90; west = 270 } }
)

function New-Arm($arm) {
    $faces = [ordered]@{}
    foreach ($face in $arm.faces.Keys) {
        $rotation = $arm.faces[$face]
        $def = [ordered]@{ uv = @(0.0, 0.0, 8.0, 3.0); texture = '#0' }
        if ($rotation -ne 0) { $def['rotation'] = $rotation }
        $faces[$face] = $def
    }
    return [ordered]@{ from = $arm.from; to = $arm.to; faces = $faces }
}

# The port, pointing south: a neck off the core, then a flange flat against what it serves. The flange's
# north face samples the port texture at uv 2-14; its middle is behind the neck, so what is left on
# screen is the ring at 2-5 and 10-13.
function New-Port {
    return @(
        [ordered]@{
            from = @(6.0, 6.0, 12.0); to = @(10.0, 10.0, 14.0)
            faces = [ordered]@{
                east = [ordered]@{ uv = @(7.0, 6.0, 9.0, 10.0); texture = '#2' }
                west = [ordered]@{ uv = @(7.0, 6.0, 9.0, 10.0); texture = '#2' }
                up = [ordered]@{ uv = @(6.0, 7.0, 10.0, 9.0); texture = '#2' }
                down = [ordered]@{ uv = @(6.0, 7.0, 10.0, 9.0); texture = '#2' }
            }
        }
        [ordered]@{
            from = @(2.0, 2.0, 14.0); to = @(14.0, 14.0, 16.0)
            faces = [ordered]@{
                north = [ordered]@{ uv = @(2.0, 2.0, 14.0, 14.0); texture = '#3' }
                south = [ordered]@{ uv = @(2.0, 2.0, 14.0, 14.0); texture = '#2' }
                east = [ordered]@{ uv = @(0.0, 2.0, 2.0, 14.0); texture = '#2' }
                west = [ordered]@{ uv = @(0.0, 2.0, 2.0, 14.0); texture = '#2' }
                up = [ordered]@{ uv = @(2.0, 0.0, 14.0, 2.0); texture = '#2' }
                down = [ordered]@{ uv = @(2.0, 0.0, 14.0, 2.0); texture = '#2' }
            }
        }
    )
}

function Write-Model([string] $path, $textures, $elements) {
    $model = [ordered]@{ parent = 'nova:block/base'; textures = $textures; elements = $elements }
    $json = $model | ConvertTo-Json -Depth 10 -Compress
    [System.IO.File]::WriteAllText($path, $json)
}

foreach ($hub in $hubs) {
    $blockDir = Join-Path $modelsDir "block\$($hub.name)"
    New-Item -ItemType Directory -Force $blockDir | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $blockDir 'off') | Out-Null

    foreach ($variant in $variants) {
        $core = "$($hub.core)$($variant.texture)"
        $port = "$($hub.port)$($variant.texture)"

        $coreTextures = [ordered]@{ particle = '#1'; '0' = $TEX_CABLE; '1' = $core }
        $portTextures = [ordered]@{ particle = '#2'; '2' = $TEX_CASING; '3' = $port }

        for ($id = 0; $id -lt 64; $id++) {
            $elements = @()
            for ($bit = 0; $bit -lt 6; $bit++) {
                if (($id -band (1 -shl $bit)) -eq 0) { continue }
                $elements += New-Arm $arms[$bit]
            }

            # the core goes last so it renders over the arm seams
            $elements += New-Core

            Write-Model (Join-Path $blockDir "$($variant.suffix)$id.json") $coreTextures $elements
        }

        $attachment = if ($variant.suffix -eq '') { 'attachment.json' } else { 'attachment_off.json' }
        Write-Model (Join-Path $blockDir $attachment) $portTextures (New-Port)
    }

    # what you hold is always lit: an item is not on anybody's network
    $itemTextures = [ordered]@{ particle = '#1'; '1' = $hub.core; '2' = $TEX_CASING; '3' = $hub.port }
    Write-Model (Join-Path $itemDir "$($hub.name).json") $itemTextures (@(New-Core) + (New-Port))

    Write-Host "  $($hub.name): 128 models, 2 attachments, item"
}

Write-Host "Generated the hub models in $modelsDir" -ForegroundColor Green
