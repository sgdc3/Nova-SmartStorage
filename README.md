# SmartStorage

[![CI](https://github.com/sgdc3/Nova-SmartStorage/actions/workflows/ci.yml/badge.svg)](https://github.com/sgdc3/Nova-SmartStorage/actions/workflows/ci.yml)
[![Licence: LGPL-3.0](https://img.shields.io/badge/licence-LGPL--3.0-blue.svg)](LICENSE)

A [Nova](https://github.com/xenondevs/Nova) addon that brings AE2 / Refined Storage style centralized
item storage to Paper.

Built against **Nova 0.24.0** (Minecraft 26.2).

## What it does

Build a network out of storage cables, give it a controller and some power, plug in drive bays full of
storage cells, and access everything from a single terminal.

| Block | What it's for |
|---|---|
| **Storage Cable** | Connects devices that aren't touching. Devices placed against each other connect directly. |
| **Storage Controller** | One per network. Draws energy from Nova's energy network and enforces the device limit. |
| **Drive Bay** | Six slots for storage cells, up to twelve with Storage Upgrades. Adjustable priority: high priority fills first and empties last. |
| **Storage Terminal** | Search, sort, take out (click / right-click / shift-click), click anywhere with an item to store it. |
| **Crafting Terminal** | Same, plus a 3×3 grid that refills itself from the network after every craft. |
| **Fluid Terminal** | The network's water and lava, a bucket at a time. |
| **Wireless Access Point** | A place a network can be reached from. Does nothing else. |
| **Storage Interface** | Mounts on a block and exposes the whole network to Nova's **item** network. Two filter slots per side, one per direction. Moves a fixed number of items per network tick; Speed Upgrades raise it. |
| **Storage Connector** | Turns every container it touches — chest, barrel, shulker box, hopper, Logistics storage unit — into network storage, all six sides at once. A filter, a priority and direction switches per side. |
| **Fluid Interface** | The same for Nova's **fluid** network: everything the system holds, as a pair of tanks. A fluid picker per side rather than a filter. Rate-limited the same way, with the same upgrade. |
| **Fluid Connector** | Turns every tank it touches into network fluid storage, all six sides at once. A priority and direction switches per side. |
| **Storage Barrel** | Holds one kind of item, thousands of them, and shows which and how many on its front. 32 stacks, doubling with each Storage Upgrade. |
| **Barrel Controller** | Speaks for every barrel it can reach through touching neighbours, so one pipe or one connector serves a whole wall. Searchable list of what the wall holds. |

Every device tells you from across the room whether it is running: its lights blink while the controller
is keeping it alive, and it goes dark and still when it is not. So a network that has lost power, gained
a second controller or run past its device limit is visible without opening anything.

Open one and a lamp in its menu says the same thing in words, and says *which* thing: no controller, two
controllers, past the device limit, out of energy, or — the one the block works out about itself rather
than reads off the network — cut off from the network it was on. Every device shows the same lamp, in the
same place, because "is this thing on the network" is one question and deserves one answer.

| Item | Types | Items |
|---|---|---|
| 1k Storage Cell | 8 | 8 192 |
| 4k Storage Cell | 16 | 32 768 |
| 16k Storage Cell | 32 | 131 072 |
| 64k Storage Cell | 64 | 524 288 |

| Item | Buckets |
|---|---|
| 16B Fluid Cell | 16 |
| 64B Fluid Cell | 64 |
| 256B Fluid Cell | 256 |
| 1024B Fluid Cell | 1024 |

Cell contents live on the item, so a cell keeps everything when you pull it out and carry it elsewhere.
Fluid cells go in the **same drive bay** as storage cells, in any mixture — it is the disk rack, and disks
come in two kinds.

**Storage Upgrade** adds two disk slots to a drive bay, three upgrades taking it from six to a hard cap
of twelve, and *doubles* a storage barrel's capacity, three upgrades taking it from 32 stacks to 256. It
is registered as a Simple-Upgrades upgrade type, so it goes in each block's upgrades menu alongside any
others. Drive bay slots that are still locked are drawn dark in the menu.

The two curves are per block and both live under `upgrade_values.storage` in the block's own config, so
"more disk slots" and "more room in the barrel" being the same item is a choice you can undo.

**Void Upgrade** makes a storage barrel take items it has no room for and burn the excess. It is a
switch rather than a scale — one per barrel, which Simple-Upgrades enforces from the two-entry value list
— and it is the one place in this addon where "stored" means "dealt with" rather than "kept". A voiding
barrel still refuses a second item type, so one set to void cobblestone cannot swallow somebody's
diamonds by being pointed at.

### Filters

Filter slots accept any item carrying Nova's `ItemFilterContainer` behavior — Logistics' Item Filters
work, and so would any other addon's. On the Storage Connector the single filter decides what may be
*stored* in that container; whatever is already inside stays visible and extractable, because hiding a
player's items would be a worse surprise than a chest refusing new ones.

On the Storage Interface every side has **its own pair**, one per direction. An interface wedged between
a furnace and a pipe is doing two unrelated jobs, and a single pair of filters could only describe one of
them. They are Nova's own per-face filter maps, so Nova applies them in its own distributor, persists
them, and drops them when the block breaks; this addon only puts a slot in front of them.

**The two directions are not symmetrical, and that is the point.** No insert filter means anything may
enter, because the worst an unfiltered input can do is store something in the wrong place and it is still
there afterwards. **No extract filter means nothing may leave that side.** An unfiltered output is not an
awkward mistake, it is a hole: put a side against a hopper, forget the filter, and the network hands over
everything it has, in order, until it is empty — and noticing quickly does not undo it.

So a side's extract filter is an allow-list that has to exist, and the block holds its own config to that
rule rather than merely declining to break it: turning extraction on for a side with no filter turns
straight back off, and pulling the filter out of a side that was extracting closes it. The switch says
why when it will not move.

**Filters are about items and nothing else.** A filter is a list of the many things that may pass, and
"which of Nova's two fluids" is not a question shaped like that. A Fluid Interface side answers it with a
picker; a Fluid Connector side does not ask, because the tank against it has already decided. Neither has
a filter slot, and that is an omission on purpose.

There is deliberately no side config menu on the interface. It would be a second way to set the same
things, and a second way that does not know about the rule above.

Every device that has a priority runs on the same 0–100 scale Logistics gives its pipes, and starts at
50 — the midpoint, so there is as much room to demote something as to promote it. Higher priority is
filled first and emptied last, which is the single rule that lets a player say "keep the iron in the
drive bays and let the chests take the overflow": raise the bays and the chests only ever hold what is
spilling over. The rule is written on the priority button itself, in both menus.

None of these four is drawn as a cube, and none has a facing: they are hubs, live on all six sides, built
like the junction Nova's own pipes make where they meet a container. A core, an arm towards every device
they are wired to, and a port against every side they serve. The fluid pair is the same geometry in water
blue rather than amber — the shape tells you *what* the block is, the colour *what flows through it*, and
giving them different silhouettes would teach you the same thing twice.

That means one connector wedged between two chests serves both, and a row of barrels needs one connector
rather than one per barrel. The connector's menu counts what it found; the interface grows a port
against each endpoint of its own network type next to it.

Each port configures the one side it faces. Right-click a port and you get that side alone: on a storage
connector its own filter, its own priority and switches for storing and taking, because a chest kept for
overflow and a barrel kept for iron are two different pieces of storage that happen to share a connector;
on a storage interface, the same two switches and that face's pair of filters; on either fluid block, the
switches and the priority, plus the fluid picker on the interface. Right-click the core instead for the
summary, which lists all six sides — including the ones with nothing on them, which is where you go to
switch a side back on.

The interface used to keep Nova's own side config behind a button here as well, on the grounds that it
shows all six faces at once. It was removed: it is the wrong answer to "what does *this* nozzle do",
which is the question a player is asking when they right-click a nozzle — and, more to the point, it is
a second way to set the same things that does not know the extract-filter rule above.

Turn both directions off and the side is retired outright: the port comes off the model, the container
drops out of the network, and the block state stops counting that side as occupied — so the chain behind
the model goes too, if that port was the only thing earning it.

A connector takes two kinds of storage, and they cost very different things to reach.

A vanilla container can only be *found* through Bukkit, so the six sides are re-read on the server
thread every tick and the resolved reference is then used from the network tick. Anything Nova considers
storage — a Logistics storage unit, or any other end point that registers an inventory as a `BUFFER` —
needs neither half of that: it is found in Nova's own chunk data with no Bukkit call at all, and its
inventory is a `NetworkedInventory`, the interface Nova's item network drives from its own ticker.

Nova is therefore asked **first**, before the block state is ever built. The order is not cosmetic: going
through Bukkit first meant constructing a `CraftBlockState` for every Nova machine beside a connector,
six times a second, to answer a question Nova could answer from a map — and reading a block loads its
chunk to do so, which a tile entity tick has no business doing to its neighbour.

`BUFFER` is the line between storage and a working slot. A machine's input registers as `INSERT` and its
output as `EXTRACT`, and pulling a furnace's fuel back out from under it is not what anyone means by
attaching storage.

The arms share their geometry, texture and UV rotations with the cable generator, so a hub's arm and a
cable's arm meet without a seam. The ports are display entities rather than block model geometry,
because what a hub serves is not block state: a chest can be placed or broken beside a connector without
the connector's own state changing, so there would be nothing to hang a block state update on. Nova
renders its pipe attachments the same way, for the same reason.

Neither hub is a solid block. The vanilla state Nova puts behind the model is a chain when the hub runs
straight through — the same trick Logistics uses to give a cable run a sensible collider — and a
structure void otherwise. Both are real blocks, so hardness, tool requirement and right-click all still
work; they are simply shaped like a rod and a small cube rather than a full one.

A chain spans its whole block, so it is only laid where *both* ends of the axis are taken — by an arm or
by a port, since a port reaches the block edge just as an arm does. Lay one for a single arm and the
collider juts out into the empty side, which reads as an invisible wall where the model plainly shows
nothing.

The six booleans of a hub's block state therefore mark occupied sides rather than arms, even though only
arms are drawn from them. Marking a port's side costs nothing visually: the arm it draws is swallowed
whole by the neck and flange in front of it.

That leaves the ports, which sit outside the rod. They get a virtual hitbox each, and only to catch a
right-click: breaking has to stay with the chain, because a hitbox that breaks on click breaks
*instantly* — Minecraft runs its mining timer against real blocks only.

### The Storage Interface is the important one

Because it sits on both the storage network and Nova's item network, everything that already speaks
"Nova item network" works with a storage network out of the box:

- place one against a chest, barrel or furnace → items flow both ways
- run Logistics item cables into it → routing and item filters, for free
- feed a Nova machine from it, or dump machine output into it

That is why this addon has no import or export buses of its own: they'd be a worse version of what
already exists.

### Throughput

Both interfaces move a bounded amount per network tick, and both take **Speed Upgrades** to move more.

They need a rate of their own because Nova will not give them one. A network's throughput is the lowest
among its *cables*, with no floor under it — and an interface bolted straight onto a chest or a tank
belongs to a network with no cable at all, so it had no limit. Fluid networks tick every tick, which made
that visible immediately: the whole system emptied into the tank the instant it was attached.

The budget is *set* each tick rather than accumulated. An interface nobody used for a minute has not
banked a minute's worth of throughput, or the first thing to touch it would empty the system after all.
It is counted per direction, so a side being drained does not slow the one being filled.

Stock rates are 8 items and 100 fluid units per network tick — with Nova's own timings, about 8 items and
2 buckets a second — and ten Speed Upgrades take either to eleven times that. Both live in the block's
own config.

### Fluids

Nova has exactly two fluids, water and lava, counted in units of which 1000 make a bucket. That one fact
shapes everything here: what a fluid cell is worth is its capacity, not how many kinds of thing it can
hold, so a fluid cell has one number and no type limit. Put four buckets of lava in a 16B cell and there
is that much less room for water.

The same network carries both kinds of storage. A **fluid cell** in a drive bay, or a **tank** a Fluid
Connector is mounted on, is capacity the network can use; the bay's priority and the connector's per-side
priority order fluids exactly as they order items.

There is no vanilla half to the fluid connector. A cauldron is not a tank in any sense the network could
use: three levels of water, no type it will report, and no way to put lava in it. A fluid side is a Nova
end point with a tank on it, or it is nothing.

The **Fluid Interface** presents everything the system holds as one tank per fluid. It has to be two,
because Nova's fluid containers hold one type at a time and a storage network does not — so choosing a
side's fluid is really a choice about what comes *out* of it; either tank will take whatever it is
handed. Both accept every fluid type on paper, because Nova skips a whole channel if any container on it
disallows the fluid being moved, and an interface that quietly stopped somebody's lava pipes by being
attached to them would be a worse bug than the one it prevents.

Fluid sides start at **insert only**. Nothing leaves one until somebody opens its extract switch — the
same rule as the item side, without the filter that enforces it there.

Fluids used to live on the Storage Interface and the Storage Connector, and splitting them out is worth
the two extra recipes: the merged blocks made every side ask two unrelated questions — is there a chest
here, is there a tank here — and answer both in one menu, when in practice a side has one thing against
it. **Blocks placed before the split keep only their item half**; a world that was using them for fluids
needs the new pair put down.

The **Fluid Terminal** is a separate block rather than a tab in the item terminal, because the two have
almost nothing in common past the name: one is a scrolling, searchable, sortable list of hundreds of
things, the other is two rows. Click a fluid with an empty bucket to fill it, with a full one to pour it
in; the slot underneath takes a stack of filled buckets and empties them one per tick.

### Wireless

Right-click a **Storage Controller** with a **Wireless Terminal** to bind it to that network. After that
the terminal opens anywhere within reach of a **Wireless Access Point** on the same network — the same
list, the same clicks, and a button that switches it to a crafting grid and back.

Those are two separate questions on purpose. The binding says *which* system you are looking at, which
never changes by walking around; the reach says whether you can see it from here, which is the only thing
a player should have to think about.

**All of the reach is on the terminal.** An access point has none of its own: the terminal is the thing
that gets carried away from the network and the thing that takes Range Upgrades, so it is the thing that
answers "can I see it from here". Putting a number on the point as well would mean two places to look
when the answer is no. The reach is measured in chunks, because that is what a player navigates in: one
chunk out of the box, three more per Range Upgrade, sixteen chunks — 256 blocks — with all five in.

The upgrades have their own window — sneak and right-click — rather than a slot inside the terminal
screen. A terminal that is out of range does not open, so a slot living inside it could never be reached
by the one player who needs it, which is the player standing too far away. The slot is a *view* of a
count kept on the item, which is what makes closing the window unable to lose an upgrade: there was never
anything in it to lose.

The terminal remembers the controller's **position** rather than its identity, because a position is
something this addon can turn back into a controller without keeping an index of every one on the server.
The cost is that moving a controller breaks the binding — visible on the item, and fixed by right-clicking
the new one.

The wireless crafting grid is not persisted, unlike the crafting terminal's: an item you carry has
nowhere to keep nine stacks between uses, so closing the window puts the parts back into the network,
then into your inventory, then on the floor.

### Barrels

A **Storage Barrel** holds one kind of item and a great many of them, and says on its front which item
and how many. Capacity is counted in *stacks*, so it takes as many shulker boxes as it does cobblestone
— which is the rule that keeps it fair — and each Storage Upgrade doubles it: 32 → 64 → 128 → 256 stacks.

Right-click with an item to put it in, sneak-right-click to empty every stack of that item you are
carrying into it, and right-click empty-handed for the menu: upgrades, a drop-off slot, and the contents
themselves, which take the same clicks as a terminal entry. An empty barrel takes on the first item
offered to it and forgets it again once it runs dry; **lock** it and it keeps that item forever, which is
how you keep one barrel for iron whether or not there is any iron in it right now.

**A barrel travels full.** Break one and its contents are written onto the item it drops as — the item
says what it is holding, and placing it puts the barrel back exactly as it was, lock and all. Taking a
wall down is a *move* rather than an emptying: it becomes a stack of barrels in your inventory instead of
a floor covered in loose items, which is the whole reason to keep thousands of one thing in a block.

Two full barrels never stack together, because a stack would hold one set of contents between them and
hand it out once per barrel placed. Empty ones carry nothing and stack the way a block ought to.

**Nothing is ever dropped loose.** The contents leave on the barrel or they do not leave: a break that
does not earn the block — the wrong tool, creative, a plugin cancelling the drop — takes what is inside
with it, exactly as it does for any other machine with an inventory. A block that did not itself drop
but spilled its contents on the floor would read as being destroyed *and* looted at once.

A locked barrel wears a padlock stamped across the top of its front. That is the one thing about a barrel
that had to become block state — a texture can only follow block state, and "locked" otherwise lives in
the tile entity — so the barrel carries a `locked` property alongside its facing and the tile entity
pushes it out from its tick. Doing it from the tick rather than from the click also means a barrel whose
data and block state ever drift apart — placed from an item, most of all — corrects itself without anyone
touching it.

The badge sits at the top because that is the only strip of the face the display entities leave free, and
the item icon is sized to end exactly where the padlock does. The two textures are otherwise identical:
locking a barrel must not make it look like a different block, only like the same one with a padlock on
it.

The item and the count are two display entities floating just off the front face — no server-side entity,
sent only to players who have the chunk, and cannot be pushed, mined or picked up. The block's own front
texture is the frame they sit in.

A **Barrel Controller** speaks for every barrel it can reach through *touching* neighbours, up to
`max_barrels`. Nothing is wired and nothing is configured: barrels stacked against each other are one
block of storage, which is the arrangement players build anyway. One item pipe or one Storage Connector
against the controller then reaches the whole wall instead of one barrel.

Touching means all twenty-six blocks around one — faces, edges and corners. A wall is something you
build by eye, and by eye a barrel set kitty-corner to the next is part of it; requiring face contact made
a diagonal step silently end the wall, which is a rule nobody can see from the outside. It also lets a
wall turn a corner or step up a level without a filler barrel holding it together. The other side of
that: two runs of barrels passing corner to corner are now one wall, and whichever controller scans
first owns them both.

Its menu lists the wall a barrel per row, with the same clicks a terminal entry takes, and the same
search: the compass opens an anvil you type into and the list narrows as you go. The search is per
player rather than shared, so two people looking at the same wall are not typing into each other's list.

Beside the list are **drop-off slots**, as in the terminals: shift-click out of your inventory and it
lands there, and the next tick pushes it into the wall. Barrels already holding that item are filled
first — including one locked onto it and then emptied, which is precisely the barrel you meant — so
pushing cobblestone at a sorted wall does not scatter it into whichever barrel happened to be free.
Whatever the wall will not take stays in the slot rather than vanishing.

A barrel belongs to **exactly one** controller, first come first served, and the claim heals itself when
a controller is broken, unloaded or no longer within reach. That is not tidiness: two things presenting
the same barrel as separate storage would each promise what only one of them can deliver, and Nova's item
distributor adds to the destination *before* it takes from the source. A second controller built onto a
wall someone else already owns therefore reaches nothing, and says so in its menu.

The same invariant is what lets a connector treat a barrel and its controller as one: a connector put
against a claimed barrel resolves to the controller, so touching the wall and touching the block that
speaks for it produce the same storage identity and the network keeps one of them.

### What the barrels are *not* on

To Nova a barrel and a controller are ordinary **item network** end points, each with a single `BUFFER`
container, so item cables, hoppers and machines reach them with no integration on either side.

With one rule on top: **neither trades with anything it is merely touching.** Nova connects two end
points that *touch* directly, with no cable in between — which is exactly how a wall of barrels is built,
and how it has to be built, since that is how a controller finds them. Left alone the item network looks
at a full barrel and an empty chest beside it and does the obvious thing: moves a stack across, every
tick, forever, in whichever direction the numbers happen to point. Nobody placed either block asking for
that.

A barrel is **passive storage**. It moves items when a pipe, a connector or a player asks it to, and at
no other time. The cost is that a hopper or a machine set straight against a barrel no longer feeds it —
one segment of cable between them does. The distributor asks about a *pair*, not about a path, so
"adjacent, but with a cable" cannot be expressed here; the choice is only which way to be wrong, and a
barrel that quietly empties itself into the chest next door is the worse one.

To this addon's own storage network they are deliberately nothing at all. Neither carries a
`StorageHolder`, so a storage cable will not bridge to one and a controller will never count it as a
device. A barrel joins a virtual network exactly the way a chest does — by having a Storage Connector
placed against it.

That is a design decision, not an omission. A wall of barrels is meant to be storage a player can *see*;
letting it wire straight into a controller would make it a second kind of drive with none of a cell's
limits. Going through a connector keeps one rule: what the virtual network holds is whatever its
connectors can reach.

The connector reads a barrel directly rather than through the one-slot view the item network gets. That
view is right for a pipe — a pipe moves a stack per tick anyway — and wrong here: a barrel holds
thousands of one item, and a terminal listing 64 of them would be lying about the whole point of the
block.

## Dependencies

- **Nova** 0.24.0 — required
- **Simple-Upgrades** — required. The controller accepts Energy and Efficiency upgrades.
- **Logistics** — optional. Its item cables and filters work with the Storage Interface if installed;
  nothing breaks if it isn't.

## Installing

Take `SmartStorage-*.jar` from the [latest release](https://github.com/sgdc3/Nova-SmartStorage/releases)
and drop it into `plugins/` on a **Paper 26.2** server, beside `Nova` and `Simple_Upgrades`. Nova addons
are Paper plugins in their own right — they ship a `paper-plugin.yml` pointing at Nova's addon loader —
so there is nothing else to install and nothing to register.

Config files are written to `plugins/SmartStorage/configs/` on first run. Players will be asked to accept
Nova's resource pack; without it the blocks have no textures.

## Building

```bash
./gradlew addonJar
```

The jar lands in `build/libs/`. Drop it into `plugins/` on a Paper 26.2 server — Nova addons are Paper
plugins in their own right (they ship a `paper-plugin.yml` pointing at Nova's addon loader), so they do
**not** go in an `addons` subfolder.

To build straight into a test server:

```bash
./gradlew addonJar -PoutDir="C:/path/to/server/plugins"
```

## Tests

```bash
./gradlew test
```

They run as part of `build`, so a jar that assembles is a jar whose storage maths passed.

**What is covered.** The parts that decide how many of something there are: `CellData` and
`FluidCellData` with their limits, `ItemType` as a map key, `CellSummary`, the connection-flag encoding
that names the generated models, and `Routing` — which provider gets asked, in what order. That last one
is where both of the duplications ever found in this addon lived, so its rules are asserted one at a
time: high priority fills first and empties last, storage that already holds something gets it before an
empty provider is opened, two providers over one chest are one, and storage that will not give up its
contents promises nothing.

**What is not.** Anything that needs to be a block. Tile entities, menus, block states and Nova's own
networks are all reachable only from a running server, and faking enough of Nova to stand one up in a
unit test would be testing the fake. Those are still verified by starting the server and playing.

**The harness** is [MockBukkit](https://github.com/MockBukkit/MockBukkit), which stands in for Paper —
an `ItemStack` with no server behind it cannot say how big a stack of it is, compare itself to another
one, or clone itself, and all three are load-bearing here.

Two things about it are worth knowing before the build file surprises you. Compilation uses Origami's
*widened server*, which is a whole Paper implementation, and that cannot sit on a test classpath beside
MockBukkit: both register an `InternalAPIBridge` service and Paper refuses to start when it finds two. So
the tests get `paper-api` alone. And MockBukkit ships the game's own data tables, so it only agrees with
the Paper it was built for — its newest build targets 26.1.2 while this addon targets 26.2, and against
26.2 it dies looking up a registry entry that did not exist yet.

The tests therefore run one minor behind production. What they exercise is this addon's arithmetic, not
Paper's item behaviour, and the API they lean on — stack sizes, similarity, cloning — is the same in
both; `HarnessTest` asserts that much rather than assuming it. It is the file that would fail first if
the gap ever grew into something that matters.

## Test server

A throwaway Paper server can be set up under `.server/` (gitignored):

```bash
pwsh -File tools/setup-test-server.ps1
```

It downloads Paper, Nova and the two addons from Modrinth, and writes a superflat/creative
`server.properties`. It deliberately does **not** accept the Minecraft EULA — put `eula=true` in
`.server/eula.txt` yourself first ([EULA](https://aka.ms/MinecraftEULA)).

Note the creative default when testing anything about drops: creative breaks a block without dropping
it, which is Minecraft's rule and not this addon's.

Then, to rebuild the addon and start the server in one step:

```bash
pwsh -File tools/run-server.ps1
```

Nova instruments the server at load time, so it only starts with `-javaagent:plugins/Nova-<version>.jar`
on the JVM command line. `run-server.ps1` adds it for you; if you start Paper by hand, don't forget it.

The first startup takes several minutes: Nova downloads the vanilla assets and builds the resource pack.

### Getting the resource pack to the client

Custom blocks and items only look right if the client receives Nova's generated pack, and Nova only
sends it when it has a URL to send. After the first startup, edit `.server/plugins/Nova/configs/config.yml`:

```yaml
resource_pack:
  auto_upload:
    enabled: true
    service: self_host
    host: localhost   # LAN IP of this machine if you connect from another device
    port: 38519
    append_port: true
```

Restart, and the log prints `Resource pack … available at http://localhost:38519/…`.

`run-server.ps1` also lowers Paper's autosave from 6000 ticks to 200. The server is normally stopped by
killing the process, and at the stock five minute interval anything built shortly before a restart is
silently rolled back — a block placed a minute earlier returns with its *default* block state, which
looks precisely like it forgot which way it was facing. That artefact cost a long debugging detour once;
it is not worth repeating.

`run-server.ps1` forces a pack rebuild on every start, because two things bite otherwise: Nova persists
the pack's download URL only during a graceful shutdown, and it only mints a new one when its resources
hash changes — which does not cover everything an addon ships (language files, for one). Either way
clients silently stop receiving the pack. Pass `-KeepPack` to skip the rebuild once you know the pack
is current.

## Configuration

Config files are extracted to `plugins/SmartStorage/configs/` on first run.

| File | Notable settings |
|---|---|
| `config.yml` | `network.tick_delay`, `terminal.refresh_ticks` |
| `storage_controller.yml` | `max_energy`, `energy_per_device`, `energy_per_cell`, `max_devices`, upgrade curves |
| `storage_cell_*.yml` | `max_types`, `max_items` per tier |
| `fluid_cell_*.yml` | `max_amount` per tier, in fluid units — 1000 to the bucket |
| `storage_interface.yml` | `exposed_slots` — how many types the item network sees at once; `base_item_transfer` and its Speed Upgrade curve; `neighbour_rescan_ticks` |
| `fluid_interface.yml` | `base_transfer` in fluid units per network tick, and its Speed Upgrade curve |
| `drive_bay.yml` | `base_slots`, and the disk slots added per Storage Upgrade |
| `crafting_terminal.yml` | `max_bulk_crafts` |
| `storage_barrel.yml` | `base_stacks`, the capacity multiplier per Storage Upgrade, and the Void Upgrade switch |
| `barrel_controller.yml` | `max_barrels`, `rescan_ticks` |
| `wireless_terminal.yml` | `base_range`, `range_per_upgrade`, `max_range_upgrades`, `max_bulk_crafts` |

Setting `energy_per_device` and `energy_per_cell` to `0` makes storage networks run for free.

## Assets

Every texture and model is produced by a script under `tools/`. Their output is committed, so you only
need to run them after changing the inputs.

**Block textures and models** — a brushed dark casing in the style of Nova's Machines addon with a
Refined-Storage-flavoured front: an energy core for the controller, disk bays with activity LEDs for the
drive bay, a screen for the terminals. The casing is synthesised rather than copied, so these are
original work. Each face is a 16×16 character map at the top of the script, and the same script emits
the block models — so a texture and the model referencing it cannot drift apart.

Eleven of those faces are **animated**: the script writes several frames of one texture stacked
vertically plus the `.png.mcmeta` beside it, and Minecraft cycles them. Frame times run from 25 ticks
for the access point's wave up to 70 for the connector, because a status light that flickers reads as a
fault rather than as a heartbeat. A connector and the ports it grows share a frame time so they stay in
step — Minecraft phases animations on world time, not on when a sprite comes into view.

Every animated device also has a **dark twin**, `<name>_off`, worn while no controller is keeping it
running: the same face at a fifth of the brightness, one frame, no mcmeta, so an unpowered machine does
not blink. It is derived from the lit palette rather than drawn separately, or the two would part ways
at the first retouch. The choice is a `powered` block state property each device drives from its own
tick, so it costs a model per state — which is why both hubs have 128 models instead of 64, and why
their ports are two hidden items rather than one.

The interface is mapped concentrically instead, because its model stacks three shrinking boxes and each
one samples the same texture at 1:1 texel scale: pixels 6–9 are the nozzle mouth, 3–5 the housing face,
1–2 the flange, and the outermost ring is never sampled. Every box hides the centre the previous one
left visible, so the rings never overlap on screen. The connector's port is mapped the same way, one
ring in.

**Hub models** — for the connector and the interface alike: 64 arm permutations per power state, the
port, its dark twin and the item model.

```bash
pwsh -File tools/gen-hub-models.ps1
```

The arm geometry, texture and per-face UV rotations are copied from the cable generator on purpose: a
hub's arms have to meet a cable's without a seam, so the two must not be free to drift.

```bash
pwsh -File tools/gen-block-textures.ps1
```

**Item textures** are still crude placeholders with no design pass:

```bash
pwsh -File tools/gen-placeholder-textures.ps1
```

**Cable models** — the 64 connection permutations:

```bash
pwsh -File tools/gen-cable-models.ps1
```

Their per-face UV rotations are not decorative: the texture's stripe runs along a 16×3 band, so without
them it crosses the arm instead of running down it. The values differ per direction, not per axis,
because opposite arms are mirror images.

The cable *texture* is not a placeholder: it is derived from Logistics' cable texture by rotating its
hue, because the generated cable models reuse Logistics' UV layout and have to match it. Saturation and
lightness are left alone, so only the tier stripe changes colour and the dark casing stays as it was:

```bash
pwsh -File tools/gen-cable-texture.ps1
```

The default hue of 64° (amber) is the middle of the widest gap between Logistics' own tiers — 0°, 16°,
112°, 201° and 287° — so a storage cable is never mistaken for one of theirs. Pass `-Hue` to change it.

Note that Nova-Addons is LGPL-3.0, so a texture derived from it carries that licence — and that is a
present fact, not a future one: `cable.png` ships in the jar today. `src/main/resources/NOTICE` states
what it is derived from and `src/main/resources/licenses/` carries a verbatim copy of the licence, both
of which land in the jar. A clearly labelled mixed-licence distribution is fine; what is not fine is
shipping it silently.

**This project still has no licence of its own**, which is a decision for its author rather than
something a NOTICE file can settle. Until one is added, the terms under which anyone may use
SmartStorage are simply undefined. Replacing `cable.png` with original art would remove the only
upstream obligation — at the price of the seam it was drawn to avoid.

Menu backgrounds are generated too, by adapting Nova's own `vanilla/generic_9xN` textures so the panel,
player inventory and hotbar stay pixel-identical to every other Nova menu:

```bash
pwsh -File tools/gen-gui-textures.ps1
```

Each menu's slot layout is declared as a small character grid at the top of that script (`.` = flat
panel, `x` = slot, `i`/`c`/`r` = tinted input/craft/result slot). Change the grid there and the
structure string in the matching menu class together — they describe the same 9-wide layout.

## Architecture notes

`smartstorage:storage` is registered as a fourth Nova network type alongside energy, item and fluid.
That means Nova handles topology discovery, network splitting and merging, chunk load/unload and
asynchronous ticking — this addon only aggregates and routes.

The unit of aggregation is the **cluster**, not the network, and the difference is not academic. Only a
cable *bridges* a network; an end point terminates it. Every device here is an end point, so a straight
cable run with a connector or an interface partway along it is two networks, not one — and aggregating
per network would leave each half of that run seeing half the system. Nova groups networks that share a
node into one cluster and hands it to us as a `NetworkGroup`, so `StorageNetworkGroup` owns the status,
the ordered provider list and the energy draw, and `StorageNetwork` is a façade over it.

That clustering rule is load-bearing beyond aggregation, and it is worth naming: because it closes over
*every* network type on a shared node, two interfaces on one storage system always land in the same
cluster. Clusters are what `parallel_ticking` runs in parallel, so nothing that reaches one storage
system is ever ticked concurrently with anything else that does. Several places rely on that without a
lock; `NetworkView.take` states it.

- Storage data lives in the drive bays; `StorageNetwork` objects are transient and are rebuilt by Nova
  whenever the topology changes.
- All cell reads and writes are guarded by a single global lock (`StorageLock`), because network ticks
  and item-network calls run off the main thread while GUI clicks run on it.
- Drive bays keep decoded cell contents in memory and write them back once per operation rather than
  once per item, since re-encoding costs one NBT round trip per stored type. Not once per *tick*, which
  is what it used to be: InvUI clones a clicked stack before firing the pre-update event, so a cell
  taken out in the same tick something was pulled from it would have been handed over still listing
  items that had already gone elsewhere.

## Not included

Autocrafting (patterns, encoders, crafting CPUs) and cell partitioning. The network architecture supports
adding them later without a rewrite.

One thing worth writing down rather than discovering: Nova's fluid distributor sizes a transfer from
`providers.sumOf { it.amount }`, hands the whole of it to the consumers, and only then asks the providers
to produce it — **throwing** if they cannot. Nova's own containers always can, because each owns its
fluid and `amount` is a field. A Storage Interface's is an aggregate over every cell and tank on the
network, and every interface on one system reports the same aggregate. Two of them therefore promise it
twice, and the difference is handed out for free before the throw ever happens. No race is needed; that
was the first guess and it was wrong.

So an interface offers its *share*: the extractable amount divided by the number of interfaces on the
system, rounded down. One is unaffected; two each offer half, which adds up to exactly what is there
however the distributor groups them. Under-promising by a few units costs nothing, and over-promising by
one creates fluid.

What that leaves is a genuine race — the aggregate can shrink between the give and the take if a player
empties a cell in between — and it cannot happen on Paper today, for two structural reasons. Nova ticks
networks from `runBlocking` on the main thread, so no menu click lands mid-tick; and a cluster is the
transitive closure over networks sharing a node *across network types*, so every item and fluid network
reaching one storage system is in the same cluster and they tick in sequence, never in parallel. Neither
is a contract, and the Folia ticker sitting commented out in Nova's `NetworkTicker` would end the first.
So `NetworkView.take` checks the shortfall rather than assuming it away: if it ever fires, items are
being duplicated and the log says so.

## Licence

GNU Lesser General Public License, version 3. The full text is in [LICENSE](LICENSE).

That is inherited rather than chosen: the storage cable's texture is derived from Logistics' in
[xenondevs/Nova-Addons](https://github.com/xenondevs/Nova-Addons), which is LGPL-3.0, so the licence
travels with it. Everything else here is original work under the same terms. See
[src/main/resources/NOTICE](src/main/resources/NOTICE) for what is derived, what is not, and why the
models' shared box coordinates are parameters rather than expression.

LGPL-3.0 incorporates the terms of GPL-3.0 by reference; a copy of that text is not bundled here, only
linked from the licence itself.
