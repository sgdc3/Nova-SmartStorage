# Derives the storage cable texture from Logistics' cable texture by rotating its hue.
#
# The cable models this addon generates reuse Logistics' UV layout (a 16x3 band at the top for the arms,
# a 4x4 patch at the bottom right for the core), so the texture has to follow the same layout. Rather
# than redraw it, this rotates the hue of the source: saturation and lightness are untouched, so the
# dark casing — which is very nearly grey — stays exactly as it was and only the tier stripe changes
# colour.
#
# Logistics' own tiers sit at hues 0 (basic), 16 (advanced), 112 (elite), 201 (ultimate) and 287
# (creative). The default 64 is the middle of the widest free gap, at least 48 degrees away from all
# of them, so a storage cable is never mistaken for a Logistics one.
#
# NOTE: Nova-Addons is LGPL-3.0. A texture derived from it carries that licence with it — swap in
# original art if this addon should ship under different terms.
#
# Usage:  pwsh -File tools/gen-cable-texture.ps1
#         pwsh -File tools/gen-cable-texture.ps1 -Hue 156

param(
    [double] $Hue = 64,
    [string] $Source
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot

if (-not $Source) {
    $Source = Join-Path $root '.deps\Nova-Addons\logistics\src\main\resources\assets\textures\block\cable\basic.png'
}
if (-not (Test-Path $Source)) {
    throw "Source texture not found at $Source. Run tools/setup-deps.ps1 first, or pass -Source."
}

function ConvertFrom-Hsl([double] $h, [double] $s, [double] $l) {
    $c = (1 - [Math]::Abs(2 * $l - 1)) * $s
    $hp = ($h % 360) / 60.0
    $x = $c * (1 - [Math]::Abs(($hp % 2) - 1))
    $m = $l - $c / 2

    switch ([Math]::Floor($hp)) {
        0 { $r = $c; $g = $x; $b = 0 }
        1 { $r = $x; $g = $c; $b = 0 }
        2 { $r = 0; $g = $c; $b = $x }
        3 { $r = 0; $g = $x; $b = $c }
        4 { $r = $x; $g = 0; $b = $c }
        default { $r = $c; $g = 0; $b = $x }
    }

    $clamp = { param($v) [Math]::Max(0, [Math]::Min(255, [int][Math]::Round(($v + $m) * 255))) }
    return @((& $clamp $r), (& $clamp $g), (& $clamp $b))
}

$src = [System.Drawing.Bitmap]::FromFile($Source)
$dst = [System.Drawing.Bitmap]::new($src.Width, $src.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)

try {
    for ($y = 0; $y -lt $src.Height; $y++) {
        for ($x = 0; $x -lt $src.Width; $x++) {
            $c = $src.GetPixel($x, $y)
            if ($c.A -eq 0) {
                $dst.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
                continue
            }

            $rgb = ConvertFrom-Hsl $Hue $c.GetSaturation() $c.GetBrightness()
            $dst.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($c.A, $rgb[0], $rgb[1], $rgb[2]))
        }
    }

    $out = Join-Path $root 'src\main\resources\assets\textures\block\cable.png'
    $dst.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Host "Wrote $out (hue $Hue, from $(Split-Path -Leaf $Source))" -ForegroundColor Green
} finally {
    $dst.Dispose()
    $src.Dispose()
}
