# Draws the block textures: a brushed dark casing in the style of Nova's Machines addon, with a
# Refined-Storage-flavoured front on top of it.
#
# The casing is synthesised rather than copied, so these textures are original work — only the cable
# texture is derived (see tools/gen-cable-texture.ps1). It is matched to Machines by eye: neutral grey
# spanning roughly 48-124, a dark 1px border, a lighter inner top-left bevel, faint diagonal brushing
# and a soft highlight towards the centre.
#
# Each face is a 16x16 character map painted over the casing:
#   .  leave the casing showing
#   d  panel border          k  panel interior (the recessed "screen")      b  bolt head
#   1  accent, dim           2  accent, bright        3  accent, highlight
#   4  secondary, dim        5  secondary, bright
#
# The palette keys are digits rather than letter case because PowerShell hashtable keys are
# case-insensitive, so 'c' and 'C' would collide.
#
# The two hubs - connector and interface - contribute two maps each, and both are read at 1:1 texel
# scale rather than stretched over a whole face:
#
#   <hub>        the core cube, 8 units across, so only pixels 4-11 are ever sampled
#   <hub>_port   the flange of a port, sampled at 2-13 with its middle hidden behind the neck, so what
#                is left on screen is the ring at 2-5 and 10-13
#
# Their geometry lives in tools/gen-hub-models.ps1, not here.
#
# Usage:  pwsh -File tools/gen-block-textures.ps1

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'src\main\resources\assets\textures\block'

$PANEL_BORDER = @(30, 30, 34)
$PANEL_INNER = @(17, 17, 20)
$BOLT = @(168, 168, 172)

$textures = @(
    @{
        # Plain casing, used for the top and bottom of every device: a screen on the underside of a
        # terminal would look absurd.
        name = 'storage_casing'
        palette = @{}
        rows = @(
            '................', '................', '................', '................',
            '................', '................', '................', '................',
            '................', '................', '................', '................',
            '................', '................', '................', '................'
        )
    }
    @{
        # A caged energy core, the way Refined Storage shows its controller is alive. It breathes.
        name = 'storage_controller'
        palette = @{ '1' = @(45, 150, 185); '2' = @(90, 215, 240); '3' = @(225, 252, 255) }
        off = $true
        frametime = 45
        frames = @(
            @{}
            @{ '1' = @(22, 74, 92); '2' = @(45, 108, 120); '3' = @(112, 126, 128) }
        )
        rows = @(
            '................',
            '................',
            '..dddddddddddd..',
            '..dkkkkkkkkkkd..',
            '..dkkkk11kkkkd..',
            '..dkkk1221kkkd..',
            '..dkk123321kkd..',
            '..dk12333321kd..',
            '..dk12333321kd..',
            '..dkk123321kkd..',
            '..dkkk1221kkkd..',
            '..dkkkk11kkkkd..',
            '..dkkkkkkkkkkd..',
            '..dddddddddddd..',
            '................',
            '................'
        )
    }
    @{
        # Six disk bays with an activity LED each — one per slot at base capacity. The three rows blink
        # out of phase, which is what a rack of disks doing work looks like; one row per accent so the
        # phases can be driven from the palette rather than from three copies of the map.
        name = 'drive_bay'
        palette = @{ '2' = @(110, 245, 130); '3' = @(110, 245, 130); '4' = @(110, 245, 130) }
        off = $true
        frametime = 30
        frames = @(
            @{ '3' = @(28, 74, 40); '4' = @(28, 74, 40) }
            @{ '2' = @(28, 74, 40); '4' = @(28, 74, 40) }
            @{ '2' = @(28, 74, 40); '3' = @(28, 74, 40) }
        )
        rows = @(
            '................',
            '................',
            '.dddddd..dddddd.',
            '.dkkkd2..dkkkd2.',
            '.dddddd..dddddd.',
            '................',
            '.dddddd..dddddd.',
            '.dkkkd3..dkkkd3.',
            '.dddddd..dddddd.',
            '................',
            '.dddddd..dddddd.',
            '.dkkkd4..dkkkd4.',
            '.dddddd..dddddd.',
            '................',
            '................',
            '................'
        )
    }
    @{
        # A screen listing what the network holds, plus the row of buttons under it. The buttons pulse
        # and the screen text flickers a little behind them — enough to read as powered, not enough to
        # be something you notice twice.
        name = 'storage_terminal'
        palette = @{ '1' = @(55, 145, 165); '2' = @(95, 225, 240) }
        off = $true
        frametime = 55
        frames = @(
            @{}
            @{ '1' = @(28, 72, 84); '2' = @(74, 172, 184) }
        )
        rows = @(
            '................',
            '..dddddddddddd..',
            '..dkkkkkkkkkkd..',
            '..dk222222kkkd..',
            '..dkkkkkkkkkkd..',
            '..dk22222222kd..',
            '..dkkkkkkkkkkd..',
            '..dk2222kkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dk2222222kkd..',
            '..dkkkkkkkkkkd..',
            '..dddddddddddd..',
            '................',
            '..11..11..11....',
            '..11..11..11....',
            '................'
        )
    }
    @{
        # Same screen, showing a crafting grid instead of a list.
        name = 'crafting_terminal'
        palette = @{ '1' = @(150, 105, 40); '5' = @(235, 180, 75) }
        off = $true
        frametime = 55
        frames = @(
            @{}
            @{ '1' = @(76, 54, 22); '5' = @(178, 138, 62) }
        )
        rows = @(
            '................',
            '..dddddddddddd..',
            '..dkkkkkkkkkkd..',
            '..dk55k55k55kd..',
            '..dk55k55k55kd..',
            '..dkkkkkkkkkkd..',
            '..dk55k55k55kd..',
            '..dk55k55k55kd..',
            '..dkkkkkkkkkkd..',
            '..dk55k55k55kd..',
            '..dk55k55k55kd..',
            '..dkkkkkkkkkkd..',
            '..dddddddddddd..',
            '................',
            '..11..11..11....',
            '................'
        )
    }
    @{
        # An emitter with two arcs coming off it — the shape everyone already reads as "signal", which is
        # the only thing this block does.
        name = 'wireless_access_point'
        palette = @{ '1' = @(120, 220, 255); '2' = @(120, 220, 255); '3' = @(120, 220, 255) }
        off = $true
        frametime = 25
        # The pulse travels outwards: the near arc lights, then the far one, then both fade. The emitter
        # is its own character so it can stay lit through all of it — overriding the arcs would otherwise
        # take the dot with them.
        #
        # A null turns an arc off rather than dimming it, which leaves the casing's own grain showing
        # instead of a flat grey patch where a line used to be.
        frames = @(
            @{ '1' = @(120, 220, 255); '2' = $null }
            @{ '1' = @(58, 120, 152); '2' = @(120, 220, 255) }
            @{ '1' = $null; '2' = @(58, 120, 152) }
        )
        rows = @(
            '................',
            '................',
            '................',
            '.....222222.....',
            '...22......22...',
            '..2..........2..',
            '................',
            '......1111......',
            '....11....11....',
            '................',
            '.......dd.......',
            '......d33d......',
            '......d33d......',
            '.......dd.......',
            '................',
            '................'
        )
    }
    @{
        # A gauge with two tanks in it, the way a fluid readout is drawn everywhere: one column per fluid
        # Nova has, blue for water and amber for lava.
        name = 'fluid_terminal'
        palette = @{ '1' = @(60, 120, 210); '2' = @(120, 190, 255); '4' = @(190, 90, 30); '5' = @(255, 165, 60) }
        off = $true
        frametime = 60
        # the two gauges swell out of phase, so the block reads as two separate things being measured
        frames = @(
            @{ '4' = @(112, 54, 20); '5' = @(160, 104, 40) }
            @{ '1' = @(34, 70, 124); '2' = @(74, 118, 158) }
        )
        rows = @(
            '................',
            '..dddddddddddd..',
            '..dkkkkkkkkkkd..',
            '..dkddkkkkddkd..',
            '..dkdkkddkkdkd..',
            '..dkdkkddkkdkd..',
            '..dkdkkdd44dkd..',
            '..dkd11dd44dkd..',
            '..dkd11dd55dkd..',
            '..dkd22dd55dkd..',
            '..dkddddddddkd..',
            '..dkkkkkkkkkkd..',
            '..dddddddddddd..',
            '................',
            '..11..44..11....',
            '................'
        )
    }
    @{
        # A recessed window between two hoops. The item and the count are display entities floating just
        # in front of it, so the front is deliberately empty: it is a frame, not a picture.
        name = 'storage_barrel'
        palette = @{ '1' = @(120, 78, 42); '2' = @(176, 122, 66) }
        rows = @(
            '................',
            '2222222222222222',
            '1111111111111111',
            '..dddddddddddd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dddddddddddd..',
            '1111111111111111',
            '2222222222222222',
            '................'
        )
    }
    @{
        # The same front with a padlock riveted across the top band, for a barrel locked onto its item.
        #
        # The badge goes in the top right corner because that is the only part of the face the display
        # entities leave free: the item floats over the middle of the window down to its fifth pixel row,
        # and the count over its bottom edge. The two textures are otherwise identical on purpose —
        # locking a barrel must not make it look like a different block, only like the same one with a
        # padlock on it.
        name = 'storage_barrel_locked'
        palette = @{ '1' = @(120, 78, 42); '2' = @(176, 122, 66) }
        rows = @(
            '...........b....',
            '2222222222b2b222',
            '1111111111bbb111',
            '..ddddddddbdbd..',
            '..dkkkkkkkbbbd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dddddddddddd..',
            '1111111111111111',
            '2222222222222222',
            '................'
        )
    }
    @{
        # The nozzle the valve wears towards a machine. It is there whether or not that side is being
        # fed: what it says is "there is a machine here", and whether power is going through is the
        # core's job to say. Same flange as the other hubs so the family reads as one, in the valve's
        # amber, and with the dark twin the off variant needs.
        name = 'energy_valve_port'
        palette = @{ '2' = @(232, 178, 96) }
        off = $true
        rows = @(
            '................',
            '................',
            '..dddddddddddd..',
            '..db........bd..',
            '..d.dddddddd.d..',
            '..d.dkk22kkd.d..',
            '..d.dkkkkkkd.d..',
            '..d.d2kkkk2d.d..',
            '..d.d2kkkk2d.d..',
            '..d.dkkkkkkd.d..',
            '..d.dkk22kkd.d..',
            '..d.dddddddd.d..',
            '..db........bd..',
            '..dddddddddddd..',
            '................',
            '................'
        )
    }
    @{
        # A handwheel, drawn in the middle 8x8 because this is a hub core and that is all its model
        # samples. Lit while power is passing, dark the moment the machine it watches has nowhere to put
        # its output.
        name = 'energy_valve'
        palette = @{ '1' = @(120, 78, 42); '2' = @(232, 178, 96); '3' = @(255, 232, 176) }
        off = $true
        rows = @(
            '................',
            '................',
            '................',
            '................',
            '....dddddddd....',
            '....dk2222kd....',
            '....d23kk32d....',
            '....d2k33k2d....',
            '....d2k33k2d....',
            '....d23kk32d....',
            '....dk2222kd....',
            '....dddddddd....',
            '................',
            '................',
            '................',
            '................'
        )
    }
    @{
        # A terminal listing what the wall holds — the same screen and button row the storage terminal
        # has, because that is what the block does — but rendered warm instead of cold.
        #
        # It overrides the shared panel colours as well as the accents, which nothing else here does:
        # the point is a brass-and-amber readout rather than the blue-grey of the network devices, so
        # that a barrel wall reads as its own family of block at a glance.
        name = 'barrel_controller'
        palette = @{
            'd' = @(52, 36, 24); 'k' = @(34, 24, 16)
            '1' = @(120, 78, 42); '2' = @(232, 178, 96)
        }
        off = $true
        frametime = 55
        frames = @(
            @{}
            @{ '1' = @(74, 50, 28); '2' = @(168, 128, 70) }
        )
        rows = @(
            '................',
            '..dddddddddddd..',
            '..dkkkkkkkkkkd..',
            '..dk222222kkkd..',
            '..dkkkkkkkkkkd..',
            '..dk22222222kd..',
            '..dkkkkkkkkkkd..',
            '..dk2222kkkkkd..',
            '..dkkkkkkkkkkd..',
            '..dk2222222kkd..',
            '..dkkkkkkkkkkd..',
            '..dddddddddddd..',
            '................',
            '..11..11..11....',
            '..11..11..11....',
            '................'
        )
    }
    @{
        # The connector's hub, seen from all six sides. Only the middle 8x8 is ever sampled: the core
        # cube is 8 units across and its faces map onto it at 1:1, so the rest of the sheet is padding.
        name = 'storage_connector'
        palette = @{ '2' = @(255, 210, 70) }
        off = $true
        frametime = 70
        frames = @(
            @{}
            @{ '2' = @(148, 122, 42) }
        )
        rows = @(
            '................',
            '................',
            '................',
            '................',
            '....dddddddd....',
            '....dkkkkkkd....',
            '....dk2kk2kd....',
            '....dkkkkkkd....',
            '....dkkkkkkd....',
            '....dk2kk2kd....',
            '....dkkkkkkd....',
            '....dddddddd....',
            '................',
            '................',
            '................',
            '................'
        )
    }
    @{
        # The port the connector grows against a container. Sampled at uv 2-14 by the flange, whose
        # middle is covered by the neck, so only the four pixel ring at 2-5 and 10-13 is ever seen.
        name = 'storage_connector_port'
        palette = @{ '2' = @(255, 210, 70) }
        # same frame count and same frametime as the core, so a connector and its ports breathe together:
        # Minecraft clocks texture animations off the world time, not off when the sprite came into view
        off = $true
        frametime = 70
        frames = @(
            @{}
            @{ '2' = @(148, 122, 42) }
        )
        rows = @(
            '................',
            '................',
            '..dddddddddddd..',
            '..db........bd..',
            '..d.dddddddd.d..',
            '..d.dkk22kkd.d..',
            '..d.dkkkkkkd.d..',
            '..d.d2kkkk2d.d..',
            '..d.d2kkkk2d.d..',
            '..d.dkkkkkkd.d..',
            '..d.dkk22kkd.d..',
            '..d.dddddddd.d..',
            '..db........bd..',
            '..dddddddddddd..',
            '................',
            '................'
        )
    }
    @{
        # The interface's core, seen from all six sides, split on the diagonal: teal in, amber out. The
        # old face carried two arrows, which do not survive being cut down to an 8x8 - the colours do.
        name = 'storage_interface'
        palette = @{ '2' = @(85, 225, 205); '5' = @(235, 185, 85) }
        off = $true
        frametime = 55
        # in and out alternate, which is the one thing this block is about
        frames = @(
            @{ '5' = @(122, 100, 50) }
            @{ '2' = @(46, 118, 108) }
        )
        rows = @(
            '................',
            '................',
            '................',
            '................',
            '....dddddddd....',
            '....dkkkkkkd....',
            '....dk22kkkd....',
            '....dk2kkkkd....',
            '....dkkkk5kd....',
            '....dkkk55kd....',
            '....dkkkkkkd....',
            '....dddddddd....',
            '................',
            '................',
            '................',
            '................'
        )
    }
    @{
        # The port the interface grows against whatever it feeds. Same ring as the connector's, with the
        # same diagonal split as its core.
        name = 'storage_interface_port'
        palette = @{ '2' = @(85, 225, 205); '5' = @(235, 185, 85) }
        off = $true
        frametime = 55
        frames = @(
            @{ '5' = @(122, 100, 50) }
            @{ '2' = @(46, 118, 108) }
        )
        rows = @(
            '................',
            '................',
            '..dddddddddddd..',
            '..db........bd..',
            '..d.dddddddd.d..',
            '..d.dkk22kkd.d..',
            '..d.dkkkkkkd.d..',
            '..d.d2kkkk5d.d..',
            '..d.d2kkkk5d.d..',
            '..d.dkkkkkkd.d..',
            '..d.dkk55kkd.d..',
            '..d.dddddddd.d..',
            '..db........bd..',
            '..dddddddddddd..',
            '................',
            '................'
        )
    }
    @{
        # The fluid connector and its port, and the fluid interface and its port.
        #
        # Deliberately the same shapes as the four above, in water blue instead of amber. A player has
        # already learnt that a small cube with four lit corners is a connector and that a diagonal split
        # is an interface; making the fluid pair a different shape would be teaching them twice. The
        # colour is what says which of the two a block moves, and it is the one thing that differs.
        name = 'fluid_connector'
        palette = @{ '2' = @(90, 190, 255) }
        off = $true
        frametime = 70
        frames = @(
            @{}
            @{ '2' = @(52, 110, 148) }
        )
        rows = @(
            '................',
            '................',
            '................',
            '................',
            '....dddddddd....',
            '....dkkkkkkd....',
            '....dk2kk2kd....',
            '....dkkkkkkd....',
            '....dkkkkkkd....',
            '....dk2kk2kd....',
            '....dkkkkkkd....',
            '....dddddddd....',
            '................',
            '................',
            '................',
            '................'
        )
    }
    @{
        name = 'fluid_connector_port'
        palette = @{ '2' = @(90, 190, 255) }
        off = $true
        frametime = 70
        frames = @(
            @{}
            @{ '2' = @(52, 110, 148) }
        )
        rows = @(
            '................',
            '................',
            '..dddddddddddd..',
            '..db........bd..',
            '..d.dddddddd.d..',
            '..d.dkk22kkd.d..',
            '..d.dkkkkkkd.d..',
            '..d.d2kkkk2d.d..',
            '..d.d2kkkk2d.d..',
            '..d.dkkkkkkd.d..',
            '..d.dkk22kkd.d..',
            '..d.dddddddd.d..',
            '..db........bd..',
            '..dddddddddddd..',
            '................',
            '................'
        )
    }
    @{
        # The same diagonal split the item interface has: one colour for what goes in, one for what comes
        # out. Both are blues here, because both halves are the same fluid going opposite ways.
        name = 'fluid_interface'
        palette = @{ '2' = @(120, 215, 255); '5' = @(60, 130, 210) }
        off = $true
        frametime = 55
        frames = @(
            @{ '5' = @(32, 68, 110) }
            @{ '2' = @(64, 112, 132) }
        )
        rows = @(
            '................',
            '................',
            '................',
            '................',
            '....dddddddd....',
            '....dkkkkkkd....',
            '....dk22kkkd....',
            '....dk2kkkkd....',
            '....dkkkk5kd....',
            '....dkkk55kd....',
            '....dkkkkkkd....',
            '....dddddddd....',
            '................',
            '................',
            '................',
            '................'
        )
    }
    @{
        name = 'fluid_interface_port'
        palette = @{ '2' = @(120, 215, 255); '5' = @(60, 130, 210) }
        off = $true
        frametime = 55
        frames = @(
            @{ '5' = @(32, 68, 110) }
            @{ '2' = @(64, 112, 132) }
        )
        rows = @(
            '................',
            '................',
            '..dddddddddddd..',
            '..db........bd..',
            '..d.dddddddd.d..',
            '..d.dkk22kkd.d..',
            '..d.dkkkkkkd.d..',
            '..d.d2kkkk5d.d..',
            '..d.d2kkkk5d.d..',
            '..d.dkkkkkkd.d..',
            '..d.dkk55kkd.d..',
            '..d.dddddddd.d..',
            '..db........bd..',
            '..dddddddddddd..',
            '................',
            '................'
        )
    }
)

function Get-Noise([int] $x, [int] $y) {
    # deterministic, so regenerating never churns the files
    $h = (($x * 73856093) -bxor ($y * 19349663)) -band 0x7FFFFFFF
    return ($h % 11) - 5
}

function New-Casing {
    $bmp = [System.Drawing.Bitmap]::new(16, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)

    for ($y = 0; $y -lt 16; $y++) {
        for ($x = 0; $x -lt 16; $x++) {
            # faint diagonal brushing, like a milled metal plate
            $v = 86 + [Math]::Sin(($x * 0.9 + $y * 1.7)) * 4.5 + [Math]::Sin((($x - $y) * 2.1)) * 2.5
            $v += (Get-Noise $x $y)

            # soft highlight towards the middle
            $dx = $x - 7.5
            $dy = $y - 7.5
            $v += [Math]::Max(0.0, 8.0 - [Math]::Sqrt($dx * $dx + $dy * $dy)) * 2.2

            # bevel: dark rim, lit top-left, shaded bottom-right
            if ($x -eq 0 -or $y -eq 0 -or $x -eq 15 -or $y -eq 15) {
                $v = 50 + (Get-Noise $x $y) * 0.4
            } elseif ($x -eq 1 -or $y -eq 1) {
                $v += 16
            } elseif ($x -eq 14 -or $y -eq 14) {
                $v -= 12
            }

            $g = [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($v)))
            # a touch warmer than neutral so it doesn't read as flat grey
            $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $g, $g, [Math]::Max(0, $g - 3)))
        }
    }

    return $bmp
}

# --- rendering ----------------------------------------------------------------------------------

# Which characters are "lit": an accent rather than structure. They get a halo on the recessed panel
# around them, which is what makes a two pixel LED read as a light rather than as a coloured dot.
$STRUCTURE = @('d', 'k', 'b')

function Test-Lit([string] $ch) {
    return $ch -ne '.' -and ($STRUCTURE -notcontains $ch)
}

<#
The same face with its lights out, for a device the controller is not powering.

Derived from the lit palette rather than listed beside it: an unlit LED is the same LED, dark, and two
hand-written palettes would drift the moment one of them was tweaked. Structure keeps its colour —
a dead machine still has a panel and bolts.
#>
function New-OffPalette([hashtable] $palette) {
    $off = @{}
    foreach ($entry in $palette.GetEnumerator()) {
        if ($STRUCTURE -contains $entry.Key) {
            $off[$entry.Key] = $entry.Value
            continue
        }

        $rgb = $entry.Value
        $off[$entry.Key] = @(
            [int]($rgb[0] * 0.22),
            [int]($rgb[1] * 0.22),
            [int]($rgb[2] * 0.22)
        )
    }
    return $off
}

<#
Paints one frame: the casing, then the character map over it, then a halo around whatever is lit.

$palette maps a character to an RGB triple. A character mapped to $null is *not painted at all*, which
is how a frame turns a light off without leaving a flat patch where the casing's grain should be.
#>
function New-Frame([hashtable] $tex, [hashtable] $palette) {
    $bmp = New-Casing

    for ($y = 0; $y -lt 16; $y++) {
        $row = $tex.rows[$y]
        if ($row.Length -ne 16) {
            throw "$($tex.name): row $y has $($row.Length) characters, expected 16"
        }

        for ($x = 0; $x -lt 16; $x++) {
            $ch = [string]$row[$x]
            if ($ch -eq '.') { continue }
            if (-not $palette.ContainsKey($ch)) {
                throw "$($tex.name): row $y uses '$ch', which is not in the palette"
            }

            $rgb = $palette[$ch]
            if ($null -eq $rgb) { continue }

            # keep a little of the casing's grain in the glyph so it doesn't look pasted on
            $n = (Get-Noise $x $y) * 0.5
            $clamp = { param($v) [Math]::Max(0, [Math]::Min(255, [int]($v + $n))) }
            $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(
                255, (& $clamp $rgb[0]), (& $clamp $rgb[1]), (& $clamp $rgb[2])
            ))
        }
    }

    # halo: pull the recessed panel next to a lit pixel a quarter of the way towards its colour
    for ($y = 0; $y -lt 16; $y++) {
        for ($x = 0; $x -lt 16; $x++) {
            if ([string]$tex.rows[$y][$x] -ne 'k') { continue }

            $glow = $null
            foreach ($d in @(@(-1, 0), @(1, 0), @(0, -1), @(0, 1))) {
                $nx = $x + $d[0]; $ny = $y + $d[1]
                if ($nx -lt 0 -or $nx -gt 15 -or $ny -lt 0 -or $ny -gt 15) { continue }

                $nch = [string]$tex.rows[$ny][$nx]
                if (-not (Test-Lit $nch)) { continue }
                if ($null -eq $palette[$nch]) { continue }

                $glow = $palette[$nch]
                break
            }
            if ($null -eq $glow) { continue }

            $base = $bmp.GetPixel($x, $y)
            $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(
                255,
                [int]($base.R * 0.75 + $glow[0] * 0.25),
                [int]($base.G * 0.75 + $glow[1] * 0.25),
                [int]($base.B * 0.75 + $glow[2] * 0.25)
            ))
        }
    }

    return $bmp
}

New-Item -ItemType Directory -Force $outDir | Out-Null

$animated = 0

foreach ($tex in $textures) {
    $basePalette = @{ 'd' = $PANEL_BORDER; 'k' = $PANEL_INNER; 'b' = $BOLT }
    foreach ($entry in $tex.palette.GetEnumerator()) {
        $basePalette[$entry.Key] = $entry.Value
    }

    # No animation means one frame and no mcmeta beside it. The outer @() is load-bearing: PowerShell
    # unwraps a one element array out of an if, and a lone hashtable's .Count is its key count.
    $frames = @(if ($tex.ContainsKey('frames')) { $tex.frames } else { @{} })

    $sheet = [System.Drawing.Bitmap]::new(16, 16 * $frames.Count, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $canvas = [System.Drawing.Graphics]::FromImage($sheet)
    try {
        for ($f = 0; $f -lt $frames.Count; $f++) {
            $palette = @{}
            foreach ($entry in $basePalette.GetEnumerator()) { $palette[$entry.Key] = $entry.Value }
            foreach ($entry in $frames[$f].GetEnumerator()) { $palette[$entry.Key] = $entry.Value }

            $frame = New-Frame $tex $palette
            try {
                $canvas.DrawImageUnscaled($frame, 0, $f * 16)
            } finally {
                $frame.Dispose()
            }
        }
    } finally {
        $canvas.Dispose()
    }

    $file = Join-Path $outDir "$($tex.name).png"
    $sheet.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $sheet.Dispose()

    # the unlit twin: one frame, no mcmeta, because a machine that is off is not doing anything
    if ($tex.ContainsKey('off') -and $tex.off) {
        $offFrame = New-Frame $tex (New-OffPalette $basePalette)
        try {
            $offFrame.Save((Join-Path $outDir "$($tex.name)_off.png"), [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $offFrame.Dispose()
        }
    }

    $meta = "$file.mcmeta"
    if ($frames.Count -gt 1) {
        # Interpolate, so two frames are enough for a breath and three for a wave. Without it the same
        # effect would need a dozen frames hand-listed, and would still step.
        #
        # Frametimes are in ticks and deliberately long — two to three seconds a frame. These are lights
        # on machinery a player walks past all day, not something asking to be looked at; anything
        # quicker turns a room full of them into a disco.
        $frametime = if ($tex.ContainsKey('frametime')) { $tex.frametime } else { 40 }
        $json = @{ animation = [ordered]@{ frametime = $frametime; interpolate = $true } } | ConvertTo-Json -Depth 5 -Compress
        [System.IO.File]::WriteAllText($meta, $json)
        $animated++
        Write-Host "  $($tex.name).png ($($frames.Count) frames)"
    } else {
        # a texture that stopped being animated must not keep its mcmeta, or the client reads one frame
        # of a strip that is no longer there
        if (Test-Path $meta) { Remove-Item $meta }
        Write-Host "  $($tex.name).png"
    }
}

Write-Host "Generated $($textures.Count) block textures ($animated animated) in $outDir" -ForegroundColor Green

# --- models -------------------------------------------------------------------------------------
# Emitted here rather than hand-written so a texture and the model that references it cannot drift.

$modelDir = Join-Path $root 'src\main\resources\assets\models\block'
New-Item -ItemType Directory -Force $modelDir | Out-Null

function Write-BlockModel([string] $name, [hashtable] $faceTextures) {
    $textureRefs = [ordered]@{ particle = '#0' }
    $index = 0
    $byTexture = @{}
    foreach ($tex in ($faceTextures.Values | Select-Object -Unique)) {
        $byTexture[$tex] = "#$index"
        $textureRefs["$index"] = "smartstorage:block/$tex"
        $index++
    }

    $faces = [ordered]@{}
    foreach ($face in @('north', 'east', 'south', 'west', 'up', 'down')) {
        $faces[$face] = [ordered]@{ uv = @(0, 0, 16, 16); texture = $byTexture[$faceTextures[$face]] }
    }

    $model = [ordered]@{
        parent = 'nova:block/base'
        textures = $textureRefs
        elements = @(
            [ordered]@{ from = @(0, 0, 0); to = @(16, 16, 16); faces = $faces }
        )
    }

    $json = $model | ConvertTo-Json -Depth 10 -Compress
    [System.IO.File]::WriteAllText((Join-Path $modelDir "$name.json"), $json)
    Write-Host "  $name.json"
}

# The two non-directional devices: their glyphs read fine from any angle, so each wraps all four sides
# with plain casing on top and bottom.
foreach ($device in @('storage_controller', 'wireless_access_point')) {
    foreach ($suffix in @('', '_off')) {
        Write-BlockModel "$device$suffix" @{
            north = "$device$suffix"; east = "$device$suffix"
            south = "$device$suffix"; west = "$device$suffix"
            up = 'storage_casing'; down = 'storage_casing'
        }
    }
}

# `rotated()` is a no-op for FACING=NORTH, so every model below is authored as the FACING=NORTH variant
# and Nova rotates it for the others.
#
# Everything distinctive goes on the FACING side, i.e. the model's north face. Nova derives FACING as
# the opposite of the placer's look direction (DefaultBlockStateProperties.FACING_*), so that is the
# side turned towards the player.

# Devices with a real front panel: a screen, a bank of disk bays or a barrel's window belongs on the
# side you look at. The barrel has two fronts, one per value of its `locked` block state property.
foreach ($device in @('drive_bay', 'storage_terminal', 'crafting_terminal', 'fluid_terminal', 'barrel_controller')) {
    foreach ($suffix in @('', '_off')) {
        Write-BlockModel "$device$suffix" @{
            north = "$device$suffix"
            east = 'storage_casing'; south = 'storage_casing'; west = 'storage_casing'
            up = 'storage_casing'; down = 'storage_casing'
        }
    }
}

# The barrel has no lights to put out — its front is a frame around two display entities — so it has one
# model per value of its `locked` property and nothing else.
foreach ($device in @('storage_barrel', 'storage_barrel_locked')) {
    Write-BlockModel $device @{
        north = $device
        east = 'storage_casing'; south = 'storage_casing'; west = 'storage_casing'
        up = 'storage_casing'; down = 'storage_casing'
    }
}

# The connector and the interface are not cubes and not built here at all: they are hubs that grow
# an arm per wired device and a port per served side, so their geometry lives in
# tools/gen-hub-models.ps1. Only their core and port textures come out of this file.

Write-Host "Generated block models in $modelDir" -ForegroundColor Green
