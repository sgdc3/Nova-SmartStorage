# Generates the menu background textures by adapting Nova's own `vanilla/generic_9xN` GUI textures.
#
# Nova draws these over the vanilla container background, which is how a menu stops looking like a
# chest. Starting from Nova's textures rather than from scratch keeps the panel, the player inventory
# and the hotbar pixel-identical to every other Nova menu — only the upper slot grid is ours.
#
# Geometry (measured from Nova 0.24 assets, matches vanilla containers):
#   panel width 176, height 167 + (rows - 3) * 18
#   upper slot grid origin (7, 17), 18 px pitch
#   panel background RGB(198,198,198)
#
# Usage:  pwsh -File tools/gen-gui-textures.ps1
#         pwsh -File tools/gen-gui-textures.ps1 -NovaJar path\to\Nova-0.24.0.jar

param(
    [string] $NovaJar
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'src\main\resources\assets\textures\gui'

if (-not $NovaJar) {
    $NovaJar = Get-ChildItem (Join-Path $root '.server\plugins') -Filter 'Nova-*.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $NovaJar -or -not (Test-Path $NovaJar)) {
    throw 'Nova jar not found. Pass -NovaJar, or set up the test server with tools/setup-test-server.ps1.'
}

$SLOT_X = 7
$SLOT_Y = 17
$PITCH = 18
$WIDTH = 176
$PANEL = [System.Drawing.Color]::FromArgb(255, 198, 198, 198)

# Cell kinds:
#   .  flat panel, no slot          (buttons and display items sit directly on the panel)
#   x  plain slot
#   i  input slot    (blue tint)    — deposit
#   c  craft slot    (green tint)
#   r  result slot   (amber tint)
#   u  locked slot   (dark grey)    — drive bay slots that only unlock with a storage upgrade
#   f  filter slot   (purple tint)
$TINTS = @{
    'i' = [System.Drawing.Color]::FromArgb(255, 120, 150, 200)
    'c' = [System.Drawing.Color]::FromArgb(255, 130, 180, 140)
    'r' = [System.Drawing.Color]::FromArgb(255, 210, 175, 110)
    'u' = [System.Drawing.Color]::FromArgb(255, 25, 25, 30)
    'f' = [System.Drawing.Color]::FromArgb(255, 170, 140, 210)
}
$TINT_STRENGTH = 0.38
# locked slots need to read as "off", not merely "different", so they get blended much harder
$TINT_STRENGTHS = @{ 'u' = 0.62 }

# One entry per menu. Each row string has 9 characters, one per column.
$layouts = @(
    @{
        name = 'storage_terminal'
        rows = @(
            'xxxxxxxx.',
            'xxxxxxxx.',
            'xxxxxxxx.',
            'xxxxxxxx.',
            'xxxxxxxx.',
            'iiiiiiii.'
        )
    }
    @{
        name = 'crafting_terminal'
        rows = @(
            'xxxxxxccc',
            'xxxxxxccc',
            'xxxxxxccc',
            'xxxxxx.r.',
            'xxxxxx...',
            'iiiiiiii.'
        )
    }
    @{
        # one drop-off slot for filled buckets; the fluids themselves are display items, not slots
        name = 'fluid_terminal'
        rows = @(
            '.........',
            '.........',
            '...i.....'
        )
    }
    @{
        # the storage terminal's list without the deposit row: an item you carry has nowhere to park
        # what the network will not take
        name = 'wireless_terminal'
        rows = @(
            'xxxxxxxx.',
            'xxxxxxxx.',
            'xxxxxxxx.',
            'xxxxxxxx.',
            'xxxxxxxx.',
            'xxxxxxxx.'
        )
    }
    @{
        # the same screen with the crafting grid taking the right third, and no deposit row
        name = 'wireless_crafting'
        rows = @(
            'xxxxxxccc',
            'xxxxxxccc',
            'xxxxxxccc',
            'xxxxxx.r.',
            'xxxxxx...',
            'xxxxxx...'
        )
    }
    @{
        # one slot for the terminal's range upgrades
        name = 'wireless_upgrades'
        rows = @(
            '.........',
            '....x....',
            '.........'
        )
    }
    @{
        name = 'wireless_access_point'
        rows = @(
            '.........',
            '.........',
            '.........'
        )
    }
    @{
        name = 'storage_controller'
        rows = @(
            '.........',
            '.........',
            '.........',
            '.........',
            '.........'
        )
    }
    @{
        # slots 0-5 are always available, 6-11 unlock two at a time with storage upgrades
        name = 'drive_bay'
        rows = @(
            '.........',
            '.xxxxxx..',
            '.uuuuuu..',
            '.........'
        )
    }
    @{
        # nothing belongs to the block any more; the menu is a summary of its six sides
        name = 'storage_interface'
        rows = @(
            '.........',
            '.........',
            '.........'
        )
    }
    @{
        name = 'storage_connector'
        rows = @(
            '.........',
            '.f.......',
            '.........'
        )
    }
    @{
        # the one-side menu of an interface port: the two filter slots sit under the two item switches
        name = 'interface_side'
        rows = @(
            '.........',
            '..ff.....',
            '.........'
        )
    }
    @{
        # one drop-off slot; the contents themselves are a display item, not a slot
        name = 'storage_barrel'
        rows = @(
            '.........',
            '..i......',
            '.........'
        )
    }
    @{
        # One row per screenful of the connected barrels, with the buttons in the last two columns and
        # the drop-off slots in the corner they leave free. Those are drawn as input slots, the same
        # blue as the terminal's deposit row, because that is the whole job of the tint: an empty slot
        # that takes what you shift-click has to be told apart from a panel that does nothing.
        name = 'barrel_controller'
        rows = @(
            'xxxxxxx..',
            'xxxxxxx..',
            'xxxxxxx..',
            'xxxxxxxi.'
        )
    }
)

function Get-Blend([System.Drawing.Color] $base, [System.Drawing.Color] $tint, [double] $t) {
    return [System.Drawing.Color]::FromArgb(
        $base.A,
        [int][Math]::Round($base.R * (1 - $t) + $tint.R * $t),
        [int][Math]::Round($base.G * (1 - $t) + $tint.G * $t),
        [int][Math]::Round($base.B * (1 - $t) + $tint.B * $t)
    )
}

New-Item -ItemType Directory -Force $outDir | Out-Null
$zip = [System.IO.Compression.ZipFile]::OpenRead($NovaJar)

try {
    foreach ($layout in $layouts) {
        $rowCount = $layout.rows.Count
        $height = 167 + ($rowCount - 3) * $PITCH

        $entryName = "assets/nova/textures/gui/vanilla/generic_9x$rowCount.png"
        $entry = $zip.GetEntry($entryName)
        if (-not $entry) { throw "Nova jar has no $entryName" }

        # Nova ships these on a padded 256x256 canvas; crop to the actual panel.
        $stream = $entry.Open()
        $source = [System.Drawing.Bitmap]::FromStream($stream)
        $bmp = [System.Drawing.Bitmap]::new($WIDTH, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        for ($y = 0; $y -lt $height; $y++) {
            for ($x = 0; $x -lt $WIDTH; $x++) {
                $bmp.SetPixel($x, $y, $source.GetPixel($x, $y))
            }
        }
        $source.Dispose()
        $stream.Dispose()

        for ($row = 0; $row -lt $rowCount; $row++) {
            $line = $layout.rows[$row]
            for ($col = 0; $col -lt 9; $col++) {
                $kind = $line[$col]
                $x0 = $SLOT_X + $col * $PITCH
                $y0 = $SLOT_Y + $row * $PITCH

                if ($kind -eq '.') {
                    # remove the slot entirely: each cell is self-contained, so neighbours keep their bevels
                    for ($y = $y0; $y -lt $y0 + $PITCH; $y++) {
                        for ($x = $x0; $x -lt $x0 + $PITCH; $x++) {
                            $bmp.SetPixel($x, $y, $PANEL)
                        }
                    }
                } elseif ($TINTS.ContainsKey([string]$kind)) {
                    # keep the slot frame, tint only the 16x16 interior so the role reads at a glance
                    $tint = $TINTS[[string]$kind]
                    $strength = if ($TINT_STRENGTHS.ContainsKey([string]$kind)) { $TINT_STRENGTHS[[string]$kind] } else { $TINT_STRENGTH }
                    for ($y = $y0 + 1; $y -lt $y0 + $PITCH - 1; $y++) {
                        for ($x = $x0 + 1; $x -lt $x0 + $PITCH - 1; $x++) {
                            $bmp.SetPixel($x, $y, (Get-Blend $bmp.GetPixel($x, $y) $tint $strength))
                        }
                    }
                }
            }
        }

        $file = Join-Path $outDir "$($layout.name).png"
        $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        Write-Host ("  {0,-20} {1}x{2}  ({3} rows)" -f "$($layout.name).png", $WIDTH, $height, $rowCount)
    }
} finally {
    $zip.Dispose()
}

Write-Host "Generated $($layouts.Count) gui textures in $outDir" -ForegroundColor Green
