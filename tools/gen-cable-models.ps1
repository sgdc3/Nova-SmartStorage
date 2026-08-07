# Generates the 64 connection permutations of the storage cable block model.
#
# The block has six boolean state properties (one per cartesian direction); StorageCable encodes them
# into a single int and asks Nova for `block/cable/<id>`. Rather than hand-writing 64 near-identical
# files, this script emits them from one description of the core cube and the six arms.
#
# Bit order matches StorageCable.encodeConnections: 0=north 1=east 2=south 3=west 4=up 5=down
#
# Usage:  pwsh -File tools/gen-cable-models.ps1

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'src\main\resources\assets\models\block\cable'
New-Item -ItemType Directory -Force $outDir | Out-Null

# the centre cube, present in every permutation
$core = @{
    from  = @(6.0, 6.0, 6.0)
    to    = @(10.0, 10.0, 10.0)
    faces = [ordered]@{
        north = [ordered]@{ uv = @(12.0, 12.0, 16.0, 16.0); texture = '#0' }
        east  = [ordered]@{ uv = @(8.0, 12.0, 12.0, 16.0); texture = '#0' }
        south = [ordered]@{ uv = @(4.0, 12.0, 8.0, 16.0); texture = '#0' }
        west  = [ordered]@{ uv = @(0.0, 12.0, 4.0, 16.0); texture = '#0' }
        up    = [ordered]@{ uv = @(0.0, 12.0, 4.0, 16.0); texture = '#0' }
        down  = [ordered]@{ uv = @(12.0, 12.0, 16.0, 16.0); texture = '#0' }
    }
}

# One arm per direction, reaching from the block edge to the centre; the two end caps are omitted
# because they are always hidden (one inside the core, the other against the neighbour).
#
# The per-face UV rotations matter: the texture's stripe runs along the 16x3 band at the top, so without
# them the stripe crosses the arm instead of running down it. The values below are the ones Logistics
# uses for the same geometry — note that they differ per direction, not per axis, because opposite arms
# are mirror images of each other.
$arms = @(
    @{ name = 'north'; from = @(6.75, 6.75, 0.0); to = @(9.25, 9.25, 8.0); faces = [ordered]@{ east = 0; west = 0; up = 90; down = 90 } }
    @{ name = 'east'; from = @(8.0, 6.75, 6.75); to = @(16.0, 9.25, 9.25); faces = [ordered]@{ north = 0; south = 0; up = 180; down = 0 } }
    @{ name = 'south'; from = @(6.75, 6.75, 8.0); to = @(9.25, 9.25, 16.0); faces = [ordered]@{ east = 0; west = 0; up = 270; down = 270 } }
    @{ name = 'west'; from = @(0.0, 6.75, 6.75); to = @(8.0, 9.25, 9.25); faces = [ordered]@{ north = 0; south = 0; up = 0; down = 180 } }
    @{ name = 'up'; from = @(6.75, 8.0, 6.75); to = @(9.25, 16.0, 9.25); faces = [ordered]@{ north = 270; east = 270; south = 90; west = 90 } }
    @{ name = 'down'; from = @(6.75, 0.0, 6.75); to = @(9.25, 8.0, 9.25); faces = [ordered]@{ north = 270; east = 90; south = 90; west = 270 } }
)

# An axis with both of its directions connected is one straight tube, not two arms meeting at a joint,
# so the two are merged into a single box running the full 16 and textured over the full 16. The merged
# box inherits the UVs of whichever arm starts at coordinate 0 - north, west, down - so the stripe runs
# on from where that arm's own mapping began.
$axes = @(
    @{ low = 0; high = 2; along = 'z' }  # north / south
    @{ low = 3; high = 1; along = 'x' }  # west / east
    @{ low = 5; high = 4; along = 'y' }  # down / up
)

function New-Tube($arm, [double] $uvLength, $from, $to) {
    $faces = [ordered]@{}
    foreach ($face in $arm.faces.Keys) {
        $rotation = $arm.faces[$face]
        $def = [ordered]@{ uv = @(0.0, 0.0, $uvLength, 3.0); texture = '#0' }
        if ($rotation -ne 0) { $def['rotation'] = $rotation }
        $faces[$face] = $def
    }
    return [ordered]@{ from = $from; to = $to; faces = $faces }
}

for ($id = 0; $id -lt 64; $id++) {
    $elements = @()
    $merged = 0
    $connections = 0

    foreach ($axis in $axes) {
        $lowSet = ($id -band (1 -shl $axis.low)) -ne 0
        $highSet = ($id -band (1 -shl $axis.high)) -ne 0
        $connections += [int] $lowSet + [int] $highSet

        if ($lowSet -and $highSet) {
            $low = $arms[$axis.low]
            $from = $low.from.Clone()
            $to = $low.to.Clone()
            switch ($axis.along) {
                'x' { $to[0] = 16.0 }
                'y' { $to[1] = 16.0 }
                'z' { $to[2] = 16.0 }
            }

            $elements += New-Tube $low 16.0 $from $to
            $merged++
            continue
        }

        foreach ($bit in @($axis.low, $axis.high)) {
            if (($id -band (1 -shl $bit)) -eq 0) { continue }
            $arm = $arms[$bit]
            $elements += New-Tube $arm 8.0 $arm.from $arm.to
        }
    }

    # A cable that only runs straight through is a single tube: putting the joint cube in the middle of
    # it would draw a corner where the run has none. Everything else keeps it, and the core goes last so
    # it renders on top of the arm seams.
    if (-not ($merged -eq 1 -and $connections -eq 2)) {
        $elements += [ordered]@{ from = $core.from; to = $core.to; faces = $core.faces }
    }

    $model = [ordered]@{
        parent   = 'nova:block/base'
        textures = [ordered]@{ particle = '#0'; '0' = 'smartstorage:block/cable' }
        elements = $elements
    }

    $json = $model | ConvertTo-Json -Depth 10 -Compress
    [System.IO.File]::WriteAllText((Join-Path $outDir "$id.json"), $json)
}

Write-Host "Generated 64 cable models in $outDir" -ForegroundColor Green
