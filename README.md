# OptiGen

The fastest world generation I could build that still looks like Minecraft and can still be beaten.
Fabric, Minecraft 26.3, with native Distant Horizons 3.3 LOD generation.

## Using it

- **Singleplayer:** Create World → More → World Type → **OptiGen (Fast)**.
- **Dedicated server:** set `level-type=optigen\:optigen` in `server.properties` before the world is first created.

Only new worlds use OptiGen. Existing worlds keep their generator.

## How it gets its speed

Vanilla evaluates a 3D density-function graph for every block of every chunk. OptiGen treats each
dimension as a handful of 2D noise functions of (x, z) and builds the world from those directly.

| Stage | Vanilla | OptiGen |
|---|---|---|
| Biomes | Climate density functions sampled per 4×4×4 cell | 2D lookup, 16 samples per chunk, filled synchronously |
| Terrain | 3D density, aquifers, ore veins, surface rules | ~15 2D noise samples per column, painted directly |
| Chunk sections | Every block written individually | Fully solid/empty sections become a single-value palette with no block writes; only the 1–3 sections holding the surface are written per block |
| Caves and carvers | Yes | None |
| Decoration | Every biome feature | Filtered (see below), with per-chunk structure bookkeeping cached |
| Structures | Every structure set | Only the sets listed in the world preset |
| Threads | Terrain on cores − 1; decoration, light-source scans and spawning on **one** thread per dimension | Terrain, decoration, light-source scans and spawning on a pool with **one thread per core**, shared by every dimension |
| DH LODs | 3.3's rough generator ray-marches density functions or generates real chunks | Every LOD column, at every detail level, comes straight from the terrain functions with no chunks |

### Corners cut on purpose

- No caves, carvers, aquifers, overhangs or cave biomes. The stone/deepslate boundary is flat at y=0.
- Decoration drops dungeons, fossils, geodes, fluid springs, glow lichen, and the filler blobs (granite, diorite, andesite, tuff, dirt, gravel).
  Copper, redstone, lapis and emerald ores are gone too.
  It keeps trees, plants, coal, iron, gold and diamonds, lava lakes (for obsidian), icebergs, snow and ice.
- Overworld structures: strongholds, villages, ruined portals, desert pyramids, jungle temples, swamp huts, igloos, shipwrecks, buried treasure.
  Mineshafts, mansions, monuments, trial chambers, ancient cities, trail ruins and outposts are gone.
- Structures are not blended into the terrain (no beardifier).

### What a speedrun still gets

- **Overworld:** land at spawn is guaranteed, strongholds with end portals, villages, ruined portals, lava lakes, iron, diamonds.
- **Nether:** a floor-and-ceiling cavern with a lava sea at y=31 and all five nether biomes. Fortresses and bastions both generate.
- **The End:** vanilla's own island layout function, with the central island at 0,0. Obsidian spikes, the exit portal, gateways, outer islands, chorus and end cities all generate.

## Using every core

Vanilla 26.3 runs each dimension's chunk steps on a single consecutive `worldgen` thread. Only
terrain escapes it. OptiGen moves decoration, light-source initialisation and initial mob
spawning onto its own pool, which has one thread per core.

A region scheduler keeps that safe. Decoration claims the 3×3 chunks it may write into, so two
jobs that could touch the same chunk never run at the same time, while every other job runs at
once. Waiting jobs don't hold threads. Workers run one priority step below normal so the server
thread still gets to tick.

Two limits are left:

- Lighting is still vanilla's single light thread per dimension.
- Chunk promotion and saving still happen on the server thread.

When C2ME is installed, OptiGen leaves step scheduling to it.

## Configuration

Mod Menu → OptiGen → **Configure** opens a YACL screen. It shows up when [Mod Menu](https://modrinth.com/mod/modmenu)
and [YACL](https://modrinth.com/mod/yacl) are installed. Settings are saved to `config/optigen.json`,
which also works on dedicated servers and without either mod.

| Option | Default | Effect |
|---|---|---|
| Parallel World Generation | On | Generate on every worker thread at once (off automatically with C2ME) |
| Worker Threads | Auto (all cores) | Size of the OptiGen worker pool |
| Lower Worker Priority | On | Workers sit one priority step below normal so ticking stays smooth |
| Trim Ores | On | Skip copper, redstone, lapis, emerald |
| Skip Underground Decoration | On | Skip dungeons, fossils, geodes, springs, lichen, stone blobs |
| OptiGen LOD Generator | On | Build DH LODs straight from OptiGen terrain |
| Give DH Every Core | On | DH world gen on all cores at 100% (via DH's API, its config file is untouched) |
| LOD Tree Canopy | On | Fake canopy on forest LODs |
| Max LOD Render Distance | 16384 chunks | Highest render distance DH's own settings will accept (DH's stock limit is 4096); slider up to 65536 |

Thread and decoration settings take effect when a world is loaded. The canopy setting applies to
LODs generated from then on.

## Tuning

Everything that decides *what* generates is in
`src/main/resources/data/optigen/worldgen/world_preset/optigen.json`. Add or remove `structure_sets`
per dimension there. Feature filtering lives in `FeatureFilter.java`.

## Distant Horizons

DH is optional. When it is installed, OptiGen registers an `IDhApiWorldGenerator` for every OptiGen
dimension. It returns `API_DATA_SOURCES` for detail levels 0–12. DH 3.3's built-in surface generator
samples vanilla density functions, which OptiGen worlds don't have. This replaces it with an exact
generator that is also cheaper. LOD terrain is built from the same height functions as the real
chunks that later replace it. Forests get a cheap fake canopy in LODs, because LODs never run features.

OptiGen also raises DH's world-gen thread count to every core with a 100% run-time ratio. It does
this through DH's API, so your DH config file is untouched.

### Render distance above 4096 chunks

DH's 4096-chunk render distance ceiling is only the maximum of one config entry. OptiGen raises
that maximum to the *Max LOD Render Distance* option. You then pick the actual distance in DH's own
settings screen or with `/dhconfig` as usual.

Behind the ceiling, DH's quadtree, clip planes and fog all scale from the chosen distance. Beyond
~65k blocks, DH adds more of its coarsest LOD sections (256 blocks per column), which OptiGen's
generator produces at any distance.

On servers, DH also refuses LOD requests beyond its two request-distance limits. Where those are
still at DH's stock 4096, OptiGen raises them to the new cap as an API override, so DH's config
file is untouched. Lower limits an admin chose on purpose are left alone.

Very large distances cost RAM, VRAM and disk space, and take a while to fill in.

Any DH generator plan works (*Surface then chunks*, *Surface only*, *Chunks only*), as long as DH's
distant generation isn't disabled.

## Benchmark / smoke test

```
./gradlew runBenchmarkServer -PbenchRadius=32           # OptiGen
./gradlew runBenchmarkServer -PbenchRadius=32 -PnoDH   # without Distant Horizons
```

This requests a (2r+1)² square of full chunks with one loading ticket (r ≤ 32), the way a
pre-generator does, so the chunk system can run them in parallel. It logs chunks/s and process CPU
load (average and peak) for each dimension. Then it locates a stronghold, village, ruined portal,
fortress, bastion and end city, and checks the End island and spikes. With DH loaded, it runs every
LOD detail level through DH's real data source with DH's API validation switched on.

To time vanilla the same way, set `level-type=minecraft\:normal` in `run/benchmark/server.properties`
and delete `run/benchmark/world`. The server needs `run/benchmark/eula.txt` containing `eula=true`,
which you have to accept yourself.

## Tests

`./gradlew test` checks four things:

- Under heavy contention, the region scheduler never lets two jobs share a chunk and never deadlocks.
- Every terrain column is well formed, and the End's island-candidate cache matches a full scan.
- Terrain throughput scales across the worker pool, and the chunk-step mixin applies to this Minecraft version.
- The render-distance cap is lifted correctly against Distant Horizons' real config classes.
