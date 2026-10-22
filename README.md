# Void Recycler

A tick-less NeoForge 26.1 tech block. Items put into it are destroyed instantly and converted into
**Resonance Points**; the points can be spent to **fabricate** any item that has a price, by hand or by pipes.
Zero TPS impact by design: the block entity has no ticker, everything is event driven and O(1).

* Minecraft 26.1.2 / NeoForge 26.1.2.107 / Java 25 / ModDevGradle 2.0.148
* Mod id: `void_recycler` - package `dev.voidrecycler` - version 1.1.0

## Build & run

```
./gradlew build          # jar in build/libs
./gradlew runClient      # dev client (loads JEI too when jei_enabled=true)
./gradlew runServer
./gradlew test           # pure-logic unit tests (PointMath)
```
(`settings.gradle` uses the foojay resolver, so a Java 25 toolchain is downloaded automatically if missing.)

**JEI**: `gradle.properties` has `jei_enabled=true`. Set it to `false` to build without any JEI dependency
(`dev/voidrecycler/integration/jei/**` is then excluded from compilation).

## How it works

| Part | Behaviour |
|---|---|
| **Input slot** (GUI) | Anything placed there is destroyed (except the mod's own capsule, upgrades and block); its value x efficiency is added to the balance. Shift-click from the inventory recycles only items that have a recycling value (so tools are never voided by accident). |
| **Pipes / hoppers** | `Capabilities.Item.BLOCK` (`ResourceHandler<ItemResource>`). `insert` recycles, `extract` fabricates the *target* item on demand. Pull-only: the block never pushes and never ticks. Transactions are rolled back correctly if a pipe cannot place the items elsewhere (and a rolled-back transfer makes no sound). |
| **Target slot** (GUI, ghost) | Click it with an item on the cursor to select the fabrication target (the cursor item is not consumed). Click empty-handed to clear. Also: pick from the catalog, or drag an item from JEI onto it. |
| **Output slot** (GUI) | Shows the target while the balance can pay for it, with the **batch amount** as stack size. Click = the whole batch, right-click = half, repeated clicks accumulate on the cursor, shift-click = up to a full stack into the inventory. |
| **Batch button** | `x1 / x8 / x32 / x64`. Click = next, right-click = previous, mouse wheel over the button or the Output Slot also cycles. Per open GUI (not stored). |
| **Redstone** | A powered block refuses pipe insertion *and* extraction (kill-switch). The GUI keeps working. The check is cached per game tick. |
| **Comparator** | With a target: how many items can be afforded (0-15). Without target: number of decimal digits of the balance (0-15). Updated only when the value changes. |
| **Breaking** | Balance and upgrades are stored on the dropped item (`stored_points`, `stored_amplifiers`, `stored_throughput` data components) and restored on placement. |

### Catalog (side panel)
The grid button in the controls row opens a panel with **every fabricable item** (6 x 7 per page): click an item to select it as
the target, buttons/mouse wheel change the page, sort by name / price up / price down, filter "only affordable".
Items the balance cannot pay for are dimmed. The panel state is client-only (it stays open between GUI sessions until restart).

### Upgrades (two slots, up to 4 of each)
| Item | Effect |
|---|---|
| **Resonance Amplifier** | `+amplifier_bonus` (default +10%) recycling efficiency each, capped at 100%. |
| **Throughput Module** | Pipe extract cap = `max_extract_per_call x (1 + modules)`. |

Upgrades are real items you can take out again; breaking the block keeps them on the dropped block item.

### Resonance Capsule (portable balance)
Stack size 1. Put it on the cursor inside the GUI: the **down arrow** button deposits all its points into the block,
the **up arrow** button stores the whole balance in the capsule. The capsule shows its points in the tooltip and uses a
different model while it holds points.

### Owner & access
The placing player becomes the owner (the first player to open an unclaimed block claims it). The lock button cycles
**everyone -> owner's scoreboard team -> owner only**. It protects the GUI and breaking; creative players bypass it.
It does **not** stop pipes/hoppers (they have no identity) - use your claim/protection mod for the surroundings.
`/voidrecycler owner <pos> clear` releases an abandoned block.

### Resonance Link (shared pool)
The sixth button joins the block to its **owner's shared pool**: every linked block of one owner reads and writes the same
balance, so you can recycle at one place and fabricate at another. Linking moves the block's own points into the pool,
unlinking leaves the pool as it is and the block starts empty. Unclaimed blocks are claimed by whoever links them.
The pool is world saved data (one `long` per owner UUID), no ticking. Breaking a linked block drops it with 0 points
(the pool stays). Comparators of the *other* linked blocks only refresh when those blocks change themselves.

### Statistics
Hover the points card: lifetime recycled points and items, fabricated items and points spent, and the three most recycled
items (the last 64 distinct item types are tracked). Counters belong to the placed block and reset when it is broken.
Pipe transfers only count when the transaction really commits.

### Effects
A quiet sound and a few particles on recycle / fabricate, throttled to one burst per 6 ticks per block, only when
something really happened. The sounds are `void_recycler:recycle` / `void_recycler:fabricate` (aliases of vanilla
sounds in `sounds.json`, replaceable by a resource pack). Disable with `effects = false`.

## Data maps (modpack API)

Both maps are synced to clients, support `#tags`, `"remove": [...]` and `"replace": true`.

* `data/void_recycler/data_maps/item/recycling_values.json` - points per ONE item, before efficiency
* `data/void_recycler/data_maps/item/fabrication_prices.json` - points per ONE fabricated item

```json
{ "values": { "minecraft:iron_ingot": { "points": 100 }, "#minecraft:logs": 4 } }
{ "values": { "minecraft:diamond": { "cost": 5000 } } }
```
(a bare number is accepted as shorthand). Pack makers override/extend them from their own datapack using the same file path.

### Item tags
* `#void_recycler:never_recycle` - the block rejects these items (hand, hoppers, pipes), whatever the data maps say. Empty by default.
* `#void_recycler:never_fabricate` - no price, hidden from the catalog and JEI. Defaults to command blocks, structure blocks,
  barriers, bedrock, spawners and similar admin/unobtainable items.

Both are ordinary item tags: extend or `replace` them from any datapack.

## Config

`serverconfig/void_recycler-server.toml`

| Key | Default | Meaning |
|---|---|---|
| `recycling_efficiency` | `0.5` | Base multiplier for every recycling value. Fractions are carried exactly (no rounding loss). |
| `amplifier_bonus` | `0.10` | Efficiency added by each Resonance Amplifier (0 - 0.5). |
| `void_unknown_items` | `true` | `false` = items without a recycling value are rejected instead of destroyed for 0. |
| `max_extract_per_call` | `64` | Base cap of items a pipe can fabricate per extract call (multiplied by Throughput Modules). |
| `effects` | `true` | Sounds and particles. |

`config/void_recycler-client.toml`

| Key | Default | Meaning |
|---|---|---|
| `show_economy_tooltips` | `false` | Adds "recycles for / fabricates for" lines to item tooltips (the GUI shows the same on hover). |
| `gui_animations` | `true` | Animated balance counter, delta popup, pulsing output well, flowing arrow. |

Both are editable in-game (Mods -> Void Recycler -> Config); labels are translated (en/ru).

## Commands (permission level: game master)

* `/voidrecycler validate` - checks both data maps against the current efficiency: direct loops (recycle value >= cost) are errors,
  loops that only appear with 4 Amplifiers and >= 50% returns are warnings.
* `/voidrecycler points <pos> get|set <n>|add <n>` - read / change a block's balance.
* `/voidrecycler owner <pos> clear` - release the owner.
* `/voidrecycler generate [markup]` - prices every craftable item that has **no entry yet**. Each crafted item is worth the sum
  of its ingredients (cheapest alternative per tag slot, cheapest recipe, divided by the output count), starting from the recycling
  values you already defined. Fabrication price = value x `markup` (default 2.0, never below the maximum efficiency or you get
  loops - the command warns). Writes `<world>/void_recycler_generated/data/void_recycler/data_maps/item/{recycling_values,fabrication_prices}.json`;
  copy that `data` folder into a datapack, `/reload`, then run `/voidrecycler validate`. Existing entries are never touched,
  special recipes, recipes with component results and crafting remainders (the bucket in a cake) are ignored, only crafting recipes are scanned.

## JEI

Two recipe categories ("Void Recycling": what an item recycles for; "Void Fabrication": what it costs) built from the synced data
maps, the Void Recycler as their catalyst, drag an item from the JEI list onto the Target Slot to select it, and the JEI overlay
moves away from the open catalog panel. EMI: not yet (no 26.1 build of the EMI API was available when this was written).

## Jade

Optional tooltip (balance or shared pool, efficiency, owner and access) when you look at the block. The plugin source is in
`src/optional/jade/java` (outside `src/main`) and is only compiled when `gradle.properties` has a `jade_dependency=` (a full Gradle notation, for example
`maven.modrinth:jade:<version id of the 26.1 NeoForge file>`). Empty by default, because the exact coordinates depend on the Jade release you pick.

## Tests

`./gradlew test` runs the pure-logic tests in `src/test` (saturating maths, number formats, efficiency and pipe-cap formulas, the price generator).
In-world behaviour (GUI, pipes, redstone) is checked with the manual test plan in `TESTING.md`.

## Art

Placeholder textures are generated; `ART_SPEC.md` describes everything an artist needs to replace them.
