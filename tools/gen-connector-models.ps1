# SUPERSEDED — DO NOT RUN. Use tools/gen-hub-models.ps1 instead.
#
# This generated the connector's models back when the connector was the only hub. It has since been
# replaced by gen-hub-models.ps1, which does the same job for the connector *and* the interface, and for
# both power states — the dark `off/` variants a device wears when no controller is keeping it running.
#
# It is kept only because this project has no version control to recover it from. Running it would write
# over block/connector/0-63, attachment.json and item/connector.json with a third of the current output:
# no interface, no off/ variants. It also refers below to StorageConnector.updateAttachments, a method
# that no longer exists — the ports are placed by StorageHub.applyPortModels now.
#
# Original description follows.
#
# Generates the models of the storage connector: a hub that grows an arm towards every device it is
# wired to and a port against every container it is mounted on.
#
# Three families come out of here:
#
#   block/connector/<0-63>    the hub itself. Six boolean state properties say which sides carry a
#                             storage network connection, StorageConnector encodes them into an int,
#                             and Nova asks for the matching model. Same bit order and same arm
#                             geometry as tools/gen-cable-models.ps1, deliberately: the arms have to
#                             meet a cable's arms without a seam, so they share the shape, the texture
#                             and the per-face UV rotations.
#
#   block/connector/attachment  the port. Not part of the block model - containers are not block state,
#                             they can appear and vanish without one - so StorageConnector renders one
#                             display entity per mounted container, exactly as Nova's own pipes do.
#                             Authored pointing south, which is what the display entity rotation in
#                             StorageConnector.updateAttachments expects.
#
#   item/connector            what you hold. Model 0 is a bare hub floating in the middle of nothing,
#                             so the item gets a hub with one port on it instead.
#
# Usage:  pwsh -File tools/gen-connector-models.ps1

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$blockDir = Join-Path $root 'src\main\resources\assets\models\block\connector'
$itemDir = Join-Path $root 'src\main\resources\assets\models\item'
New-Item -ItemType Directory -Force $blockDir | Out-Null
New-Item -ItemType Directory -Force $itemDir | Out-Null

$TEX_CABLE = 'smartstorage:block/cable'
$TEX_HUB = 'smartstorage:block/storage_connector'
$TEX_CASING = 'smartstorage:block/storage_casing'
$TEX_PORT = 'smartstorage:block/storage_connector_port'

# The hub: 8 units across, so its faces sample the middle 8x8 of the hub texture at 1:1.
$hub = [ordered]@{
    from = @(4.0, 4.0, 4.0)
    to = @(12.0, 12.0, 12.0)
    faces = [ordered]@{
        north = [ordered]@{ uv = @(4.0, 4.0, 12.0, 12.0); texture = '#1' }
        east = [ordered]@{ uv = @(4.0, 4.0, 12.0, 12.0); texture = '#1' }
        south = [ordered]@{ uv = @(4.0, 4.0, 12.0, 12.0); texture = '#1' }
        west = [ordered]@{ uv = @(4.0, 4.0, 12.0, 12.0); texture = '#1' }
        up = [ordered]@{ uv = @(4.0, 4.0, 12.0, 12.0); texture = '#1' }
        down = [ordered]@{ uv = @(4.0, 4.0, 12.0, 12.0); texture = '#1' }
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

# The port, pointing south: a neck off the hub, then a flange flat against the container. The flange's
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

$hubTextures = [ordered]@{ particle = '#1'; '0' = $TEX_CABLE; '1' = $TEX_HUB }
$portTextures = [ordered]@{ particle = '#2'; '2' = $TEX_CASING; '3' = $TEX_PORT }
$itemTextures = [ordered]@{ particle = '#1'; '1' = $TEX_HUB; '2' = $TEX_CASING; '3' = $TEX_PORT }

for ($id = 0; $id -lt 64; $id++) {
    $elements = @()
    for ($bit = 0; $bit -lt 6; $bit++) {
        if (($id -band (1 -shl $bit)) -eq 0) { continue }
        $elements += New-Arm $arms[$bit]
    }

    # the hub goes last so it renders over the arm seams
    $elements += $hub

    Write-Model (Join-Path $blockDir "$id.json") $hubTextures $elements
}

Write-Model (Join-Path $blockDir 'attachment.json') $portTextures (New-Port)
Write-Model (Join-Path $itemDir 'connector.json') $itemTextures (@($hub) + (New-Port))

Write-Host "Generated 64 connector models, the attachment and the item model in $blockDir" -ForegroundColor Green
