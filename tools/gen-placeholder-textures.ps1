# Generates the 16x16 *item* textures for SmartStorage.
#
# Most of these are still placeholders: a dark panel with a glyph on it, no design pass. Replace the PNGs
# under src/main/resources/assets/textures/item/ with real ones and nothing in the code has to change.
#
# Three styles:
#   panel  - dark border plus a distinguishing glyph, from the 8x8 maps below
#   glyph  - transparent background, glyph only; for placeholders drawn on top of an empty slot
#   sprite - a full 16x16 character map with its own palette, for items that are a *thing* rather than a
#            labelled box. '.' is transparent. Same idea as tools/gen-block-textures.ps1, minus the
#            casing underneath, because an item has no casing to sit on.
#
# Usage:  pwsh -File tools/gen-placeholder-textures.ps1

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$texRoot = Join-Path $root 'src\main\resources\assets\textures'

# glyphs are 8x8 bitmaps drawn in the centre of the tile, one row per string
$glyphs = @{
    controller = @('..####..', '.#....#.', '#..##..#', '#.####.#', '#.####.#', '#..##..#', '.#....#.', '..####..')
    drive      = @('########', '#......#', '#.####.#', '#......#', '#.####.#', '#......#', '#.####.#', '########')
    terminal   = @('########', '#......#', '#.#..#.#', '#......#', '#.####.#', '#......#', '#..##..#', '########')
    crafting   = @('########', '#.#.#.#.', '#.#.#.#.', '########', '#.#.#.#.', '#.#.#.#.', '########', '........')
    interface  = @('#......#', '##....##', '#.#..#.#', '#..##..#', '#..##..#', '#.#..#.#', '##....##', '#......#')
    cell       = @('.######.', '.#....#.', '.#.##.#.', '.#.##.#.', '.#.##.#.', '.#....#.', '.######.', '..####..')
    connector  = @('..####..', '.#....#.', '#.####.#', '#.#..#.#', '#.#..#.#', '#.####.#', '.#....#.', '..####..')
    upgrade    = @('...##...', '..####..', '.######.', '####v###', '...##...', '...##...', '.######.', '.######.')
    filter     = @('########', '.######.', '..####..', '...##...', '...##...', '...##...', '..####..', '.######.')
    port       = @('########', '#......#', '#.####.#', '#.#..#.#', '#.#..#.#', '#.####.#', '#......#', '########')
    # a droplet, for anything to do with fluids
    droplet    = @('...##...', '..####..', '.######.', '########', '########', '########', '.######.', '..####..')
    # a hole with nothing in it, for the void upgrade
    void       = @('..####..', '.#....#.', '#......#', '#......#', '#......#', '#......#', '.#....#.', '..####..')
    # an indicator lamp, lit or dark depending on the accent it is drawn in
    lamp       = @('..####..', '.######.', '########', '########', '########', '########', '.######.', '..####..')
    # a screen throwing signal, for the wireless terminal
    wireless   = @('#......#', '.#....#.', '..#..#..', '..####..', '..#..#..', '..#..#..', '..####..', '........')
}

# Items only. Block textures come from tools/gen-block-textures.ps1 and the cable texture from
# tools/gen-cable-texture.ps1 — listing a block here would overwrite the real artwork with a placeholder.
$textures = @(
    @{ path = 'item\storage_cell_1k.png'; style = 'panel'; color = @(70, 76, 86); glyph = 'cell'; accent = @(170, 180, 190) }
    @{ path = 'item\storage_cell_4k.png'; style = 'panel'; color = @(70, 76, 86); glyph = 'cell'; accent = @(120, 220, 140) }
    @{ path = 'item\storage_cell_16k.png'; style = 'panel'; color = @(70, 76, 86); glyph = 'cell'; accent = @(120, 180, 255) }
    @{ path = 'item\storage_cell_64k.png'; style = 'panel'; color = @(70, 76, 86); glyph = 'cell'; accent = @(220, 150, 255) }
    # fluid cells: the same casing as a storage cell with a droplet on it, tiered by the same accent ramp
    @{ path = 'item\fluid_cell_16b.png'; style = 'panel'; color = @(58, 70, 84); glyph = 'droplet'; accent = @(170, 180, 190) }
    @{ path = 'item\fluid_cell_64b.png'; style = 'panel'; color = @(58, 70, 84); glyph = 'droplet'; accent = @(120, 220, 140) }
    @{ path = 'item\fluid_cell_256b.png'; style = 'panel'; color = @(58, 70, 84); glyph = 'droplet'; accent = @(120, 180, 255) }
    @{ path = 'item\fluid_cell_1024b.png'; style = 'panel'; color = @(58, 70, 84); glyph = 'droplet'; accent = @(220, 150, 255) }
    @{ path = 'item\storage_upgrade.png'; style = 'panel'; color = @(72, 64, 52); glyph = 'upgrade'; accent = @(255, 190, 110) }
    @{ path = 'item\gui\storage_upgrade.png'; style = 'panel'; color = @(72, 64, 52); glyph = 'upgrade'; accent = @(255, 190, 110) }
    # Not a placeholder: a tablet, drawn as one. A screen showing a list, a bezel, a home button.
    @{
        path = 'item\wireless_terminal.png'; style = 'sprite'
        palette = @{
            'd' = @(24, 26, 30)      # bezel edge
            'b' = @(108, 116, 128)   # body
            's' = @(16, 24, 32)      # screen
            '1' = @(170, 180, 192)   # home button
            '2' = @(110, 220, 255)   # what is on the screen
        }
        rows = @(
            '................',
            '..dddddddddddd..',
            '..dbbbbbbbbbbd..',
            '..dbssssssssbd..',
            '..dbs222222sbd..',
            '..dbssssssssbd..',
            '..dbs2222sssbd..',
            '..dbssssssssbd..',
            '..dbs22222ssbd..',
            '..dbssssssssbd..',
            '..dbs222ssssbd..',
            '..dbssssssssbd..',
            '..dbbbbbbbbbbd..',
            '..dbbbb11bbbbd..',
            '..dddddddddddd..',
            '................'
        )
    }
    @{ path = 'item\void_upgrade.png'; style = 'panel'; color = @(40, 32, 44); glyph = 'void'; accent = @(210, 90, 200) }
    @{ path = 'item\gui\void_upgrade.png'; style = 'panel'; color = @(40, 32, 44); glyph = 'void'; accent = @(210, 90, 200) }
    @{ path = 'item\gui\status_online.png'; style = 'panel'; color = @(26, 34, 28); glyph = 'lamp'; accent = @(110, 240, 130) }
    @{ path = 'item\gui\status_offline.png'; style = 'panel'; color = @(34, 26, 26); glyph = 'lamp'; accent = @(120, 60, 60) }
    # transparent: drawn on top of an empty slot, so only the glyph may be opaque
    @{ path = 'item\gui\placeholder\filter.png'; style = 'glyph'; color = @(0, 0, 0); glyph = 'filter'; accent = @(150, 130, 180) }
)

function Get-Shade([int[]] $rgb, [double] $factor) {
    $clamp = { param($v) [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($v))) }
    return [System.Drawing.Color]::FromArgb(
        (& $clamp ($rgb[0] * $factor)),
        (& $clamp ($rgb[1] * $factor)),
        (& $clamp ($rgb[2] * $factor))
    )
}

# fixed seed so regenerating does not churn the files
$rng = [System.Random]::new(20260804)

foreach ($tex in $textures) {
    $bmp = [System.Drawing.Bitmap]::new(16, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        # only the panel style has a background; a glyph is drawn onto a slot and a sprite paints every
        # pixel it wants itself
        if ($tex.style -eq 'panel') {
            for ($y = 0; $y -lt 16; $y++) {
                for ($x = 0; $x -lt 16; $x++) {
                    $factor = 1.0 + ($rng.NextDouble() - 0.5) * 0.14
                    $bmp.SetPixel($x, $y, (Get-Shade $tex.color $factor))
                }
            }
        }

        if ($tex.style -eq 'glyph') {
            $rows = $glyphs[$tex.glyph]
            $accent = [System.Drawing.Color]::FromArgb(255, $tex.accent[0], $tex.accent[1], $tex.accent[2])
            for ($y = 0; $y -lt 8; $y++) {
                $row = $rows[$y]
                for ($x = 0; $x -lt 8; $x++) {
                    if ($row[$x] -eq '#') {
                        $bmp.SetPixel($x + 4, $y + 4, $accent)
                    }
                }
            }
        }

        if ($tex.style -eq 'sprite') {
            for ($y = 0; $y -lt 16; $y++) {
                $row = $tex.rows[$y]
                if ($row.Length -ne 16) {
                    throw "$($tex.path): row $y has $($row.Length) characters, expected 16"
                }

                for ($x = 0; $x -lt 16; $x++) {
                    $ch = [string]$row[$x]
                    if ($ch -eq '.') { continue }
                    if (-not $tex.palette.ContainsKey($ch)) {
                        throw "$($tex.path): row $y uses '$ch', which is not in the palette"
                    }

                    # a touch of grain, the same trick the block textures use, so a flat fill does not
                    # read as plastic
                    $rgb = $tex.palette[$ch]
                    $n = ($rng.NextDouble() - 0.5) * 10
                    $clamp = { param($v) [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($v + $n))) }
                    $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(
                        255, (& $clamp $rgb[0]), (& $clamp $rgb[1]), (& $clamp $rgb[2])
                    ))
                }
            }
        }

        if ($tex.style -eq 'panel') {
            for ($i = 0; $i -lt 16; $i++) {
                $bmp.SetPixel($i, 0, (Get-Shade $tex.color 0.55))
                $bmp.SetPixel($i, 15, (Get-Shade $tex.color 0.55))
                $bmp.SetPixel(0, $i, (Get-Shade $tex.color 0.55))
                $bmp.SetPixel(15, $i, (Get-Shade $tex.color 0.55))
            }

            $rows = $glyphs[$tex.glyph]
            $accent = [System.Drawing.Color]::FromArgb($tex.accent[0], $tex.accent[1], $tex.accent[2])
            for ($y = 0; $y -lt 8; $y++) {
                $row = $rows[$y]
                for ($x = 0; $x -lt 8; $x++) {
                    if ($row[$x] -eq '#') {
                        $bmp.SetPixel($x + 4, $y + 4, $accent)
                    }
                }
            }
        }

        $file = Join-Path $texRoot $tex.path
        New-Item -ItemType Directory -Force (Split-Path -Parent $file) | Out-Null
        $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Host "  $($tex.path)"
    } finally {
        $bmp.Dispose()
    }
}

Write-Host "Generated $($textures.Count) placeholder textures in $texRoot" -ForegroundColor Green
