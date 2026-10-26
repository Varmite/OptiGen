package youtube.varmite.world.overworld;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import youtube.varmite.config.OptiGenConfig;
import youtube.varmite.world.ColumnRuns;
import youtube.varmite.world.noise.Hash;
import youtube.varmite.world.noise.Noise2D;

import static youtube.varmite.world.noise.Hash.clamp;
import static youtube.varmite.world.noise.Hash.lerp;
import static youtube.varmite.world.noise.Hash.smoothstep;

/**
 * The whole overworld as a pure function of (x, z).
 * <p>
 * Corners cut on purpose: no 3D density, no caves or carvers, no aquifers, no overhangs, no
 * surface-rule engine, no cave biomes, a hard stone/deepslate boundary at y=0. What remains is a
 * vanilla-shaped heightmap (continents, coasts, rivers, hills, ridged mountains) painted with the
 * vanilla biomes and their usual surface blocks, which is all a beatable, recognisable world needs.
 */
public final class OverworldTerrain {
	public static final int MIN_Y = -64;
	public static final int HEIGHT = 384;
	public static final int SEA_LEVEL = 63;
	/** Highest water block; vanilla fills up to sea level - 1. */
	public static final int WATER_TOP = SEA_LEVEL - 1;

	private static final double COAST = -0.1;
	private static final double RIVER_WIDTH = 0.022;
	private static final double RIVER_VALLEY = 0.09;

	private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
	private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
	private static final BlockState STONE = Blocks.STONE.defaultBlockState();
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState ICE = Blocks.ICE.defaultBlockState();
	private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
	private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
	private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
	private static final BlockState PODZOL = Blocks.PODZOL.defaultBlockState();
	private static final BlockState SAND = Blocks.SAND.defaultBlockState();
	private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
	private static final BlockState RED_SAND = Blocks.RED_SAND.defaultBlockState();
	private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
	private static final BlockState MUD = Blocks.MUD.defaultBlockState();
	private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
	private static final BlockState PACKED_ICE = Blocks.PACKED_ICE.defaultBlockState();
	private static final BlockState CLAY = Blocks.CLAY.defaultBlockState();
	private static final BlockState[] TERRACOTTA_PALETTE = {
		Blocks.TERRACOTTA.defaultBlockState(),
		Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE).defaultBlockState(),
		Blocks.DYED_TERRACOTTA.pick(DyeColor.YELLOW).defaultBlockState(),
		Blocks.DYED_TERRACOTTA.pick(DyeColor.BROWN).defaultBlockState(),
		Blocks.DYED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState(),
		Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE).defaultBlockState(),
		Blocks.DYED_TERRACOTTA.pick(DyeColor.LIGHT_GRAY).defaultBlockState(),
	};

	private final long seed;
	private final Noise2D continent;
	private final Noise2D erosion;
	private final Noise2D ridge;
	private final Noise2D ridgeDetail;
	private final Noise2D hills;
	private final Noise2D river;
	private final Noise2D warp;
	private final Noise2D temperature;
	private final Noise2D humidity;
	private final Noise2D variant;
	private final BlockState[] badlandsBands = new BlockState[192];

	public OverworldTerrain(long seed) {
		this.seed = seed;
		this.continent = new Noise2D(seed ^ 0x1L);
		this.erosion = new Noise2D(seed ^ 0x2L);
		this.ridge = new Noise2D(seed ^ 0x3L);
		this.ridgeDetail = new Noise2D(seed ^ 0x4L);
		this.hills = new Noise2D(seed ^ 0x5L);
		this.river = new Noise2D(seed ^ 0x6L);
		this.warp = new Noise2D(seed ^ 0x7L);
		this.temperature = new Noise2D(seed ^ 0x8L);
		this.humidity = new Noise2D(seed ^ 0x9L);
		this.variant = new Noise2D(seed ^ 0xAL);

		// Vanilla-style badlands strata: mostly orange/plain terracotta with thin coloured bands.
		long h = seed ^ 0xBADL;
		for (int i = 0; i < badlandsBands.length; i++) {
			h = Hash.mix(h + i);
			double r = Hash.unit(h);
			badlandsBands[i] = r < 0.45 ? TERRACOTTA_PALETTE[1] : r < 0.75 ? TERRACOTTA_PALETTE[0] : TERRACOTTA_PALETTE[2 + (int) ((r - 0.75) * 20) % 5];
		}
	}

	public long seed() {
		return seed;
	}

	/** Everything known about one column. Reused to avoid allocation in hot loops. */
	public static final class Sample {
		public int height;
		public OverworldBiome biome = OverworldBiome.PLAINS;
		double continentalness;
		double mountains;
		double river;
		boolean riverBed;
	}

	// ------------------------------------------------------------------
	// Height
	// ------------------------------------------------------------------

	/** Y of the topmost terrain block (water not included). */
	public int height(int x, int z) {
		return heightInternal(x, z, null);
	}

	private int heightInternal(int x, int z, Sample out) {
		double wx = x + warp.sample(x / 300.0, z / 300.0) * 40.0;
		double wz = z + warp.sample(z / 300.0 + 71.3, x / 300.0 - 13.7) * 40.0;

		double c = continent.fbm(wx / 2300.0, wz / 2300.0, 3) * 1.15 + spawnBias(x, z);
		double m = erosion.fbm(x / 1100.0, z / 1100.0, 2);
		double hl = hills.fbm(wx / 170.0, wz / 170.0, 4);
		double rv = Math.abs(river.fbm(wx / 900.0, wz / 900.0, 2));

		double h;
		if (c < COAST) {
			double depth = clamp((COAST - c) / 0.4, 0.0, 1.0);
			h = 61.0 - depth * 27.0 + hl * (2.0 + 3.0 * depth);
		} else {
			double inland = clamp((c - COAST) / 0.8, 0.0, 1.0);
			double coastBlend = smoothstep(0.0, 0.1, c - COAST);
			double base = 62.0 + coastBlend * 3.0 + inland * 16.0;
			double hillAmp = 3.0 + 11.0 * smoothstep(-0.4, 0.3, m);
			h = base + hl * hillAmp * coastBlend;

			double mount = smoothstep(0.05, 0.55, m) * smoothstep(0.05, 0.3, inland);
			if (mount > 0.0) {
				double r1 = 1.0 - Math.abs(ridge.sample(wx / 560.0, wz / 560.0));
				double r2 = 1.0 - Math.abs(ridgeDetail.sample(wx / 190.0, wz / 190.0));
				h += mount * (r1 * r1 * 0.72 + r2 * r2 * 0.28) * 175.0;
			}

			if (rv < RIVER_VALLEY) {
				double bed = rv < RIVER_WIDTH ? lerp(55.0, 61.0, rv / RIVER_WIDTH) : 61.5;
				double t = smoothstep(RIVER_WIDTH, RIVER_VALLEY, rv);
				h = lerp(Math.min(bed, h), h, t);
			}
		}

		int height = (int) Math.floor(clamp(h, MIN_Y + 8, MIN_Y + HEIGHT - 16));
		if (out != null) {
			out.height = height;
			out.continentalness = c;
			out.mountains = m;
			out.river = rv;
			out.riverBed = c >= COAST && rv < RIVER_WIDTH && height < WATER_TOP + 1;
		}
		return height;
	}

	/** Pushes the area around 0,0 onto land so the world spawn is never in the middle of an ocean. */
	private static double spawnBias(int x, int z) {
		double d = Math.sqrt((double) x * x + (double) z * z);
		return d > 1000.0 ? 0.0 : 0.4 * (1.0 - smoothstep(300.0, 1000.0, d));
	}

	// ------------------------------------------------------------------
	// Biomes
	// ------------------------------------------------------------------

	public void sample(int x, int z, Sample out) {
		heightInternal(x, z, out);
		out.biome = pickBiome(x, z, out);
	}

	public OverworldBiome biome(int x, int z) {
		Sample s = new Sample();
		sample(x, z, s);
		return s.biome;
	}

	private OverworldBiome pickBiome(int x, int z, Sample s) {
		double t = temperature.fbm(x / 1500.0, z / 1500.0, 2);
		double w = humidity.fbm(x / 1300.0, z / 1300.0, 2);
		double v = variant.sample(x / 380.0, z / 380.0);
		int h = s.height;
		double c = s.continentalness;

		int temp = t < -0.42 ? 0 : t < -0.15 ? 1 : t < 0.15 ? 2 : t < 0.42 ? 3 : 4;
		int hum = w < -0.35 ? 0 : w < -0.1 ? 1 : w < 0.1 ? 2 : w < 0.3 ? 3 : 4;

		if (c < COAST && h < WATER_TOP) {
			boolean deep = c < COAST - 0.25;
			return switch (temp) {
				case 0 -> deep ? OverworldBiome.DEEP_FROZEN_OCEAN : OverworldBiome.FROZEN_OCEAN;
				case 1 -> deep ? OverworldBiome.DEEP_COLD_OCEAN : OverworldBiome.COLD_OCEAN;
				case 2 -> deep ? OverworldBiome.DEEP_OCEAN : OverworldBiome.OCEAN;
				case 3 -> deep ? OverworldBiome.DEEP_LUKEWARM_OCEAN : OverworldBiome.LUKEWARM_OCEAN;
				default -> deep ? OverworldBiome.DEEP_LUKEWARM_OCEAN : OverworldBiome.WARM_OCEAN;
			};
		}

		if (s.riverBed) {
			return temp == 0 ? OverworldBiome.FROZEN_RIVER : OverworldBiome.RIVER;
		}

		if (c < COAST + 0.06 && h <= 66) {
			if (s.mountains > 0.35) {
				return OverworldBiome.STONY_SHORE;
			}
			return temp == 0 ? OverworldBiome.SNOWY_BEACH : (temp == 4 ? OverworldBiome.DESERT : OverworldBiome.BEACH);
		}

		if (h > 185) {
			if (temp <= 1) {
				return v > 0.0 ? OverworldBiome.JAGGED_PEAKS : OverworldBiome.FROZEN_PEAKS;
			}
			return temp == 2 && v > 0.3 ? OverworldBiome.JAGGED_PEAKS : OverworldBiome.STONY_PEAKS;
		}

		if (h > 140) {
			if (temp <= 1) {
				return hum >= 3 ? OverworldBiome.GROVE : OverworldBiome.SNOWY_SLOPES;
			}
			if (temp == 4) {
				return OverworldBiome.WOODED_BADLANDS;
			}
			if (temp == 3) {
				return OverworldBiome.WINDSWEPT_SAVANNA;
			}
			return v > 0.45 ? OverworldBiome.CHERRY_GROVE : OverworldBiome.MEADOW;
		}

		if (h > 105 && s.mountains > 0.2) {
			if (temp == 4) {
				return hum >= 2 ? OverworldBiome.WOODED_BADLANDS : OverworldBiome.ERODED_BADLANDS;
			}
			if (temp == 3) {
				return OverworldBiome.SAVANNA_PLATEAU;
			}
			if (temp == 0) {
				return OverworldBiome.SNOWY_TAIGA;
			}
			return hum >= 3 ? OverworldBiome.WINDSWEPT_FOREST : (v > 0.4 ? OverworldBiome.WINDSWEPT_GRAVELLY_HILLS : OverworldBiome.WINDSWEPT_HILLS);
		}

		if ((temp == 2 || temp == 3) && hum >= 3 && h <= 67) {
			return temp == 3 ? OverworldBiome.MANGROVE_SWAMP : OverworldBiome.SWAMP;
		}

		boolean alt = v > 0.5;
		return switch (temp) {
			case 0 -> switch (hum) {
				case 0, 1, 2 -> alt ? OverworldBiome.ICE_SPIKES : OverworldBiome.SNOWY_PLAINS;
				case 3 -> OverworldBiome.SNOWY_TAIGA;
				default -> OverworldBiome.TAIGA;
			};
			case 1 -> switch (hum) {
				case 0, 1 -> OverworldBiome.PLAINS;
				case 2 -> OverworldBiome.FOREST;
				case 3 -> alt ? OverworldBiome.OLD_GROWTH_PINE_TAIGA : OverworldBiome.TAIGA;
				default -> OverworldBiome.OLD_GROWTH_SPRUCE_TAIGA;
			};
			case 2 -> switch (hum) {
				case 0 -> alt ? OverworldBiome.SUNFLOWER_PLAINS : OverworldBiome.FLOWER_FOREST;
				case 1 -> alt ? OverworldBiome.SUNFLOWER_PLAINS : OverworldBiome.PLAINS;
				case 2 -> OverworldBiome.FOREST;
				case 3 -> alt ? OverworldBiome.OLD_GROWTH_BIRCH_FOREST : OverworldBiome.BIRCH_FOREST;
				default -> alt ? OverworldBiome.PALE_GARDEN : OverworldBiome.DARK_FOREST;
			};
			case 3 -> switch (hum) {
				case 0, 1 -> OverworldBiome.SAVANNA;
				case 2 -> alt ? OverworldBiome.SPARSE_JUNGLE : OverworldBiome.FOREST;
				case 3 -> alt ? OverworldBiome.BAMBOO_JUNGLE : OverworldBiome.SPARSE_JUNGLE;
				default -> alt ? OverworldBiome.BAMBOO_JUNGLE : OverworldBiome.JUNGLE;
			};
			default -> s.mountains > 0.15
				? (hum >= 3 ? OverworldBiome.WOODED_BADLANDS : (alt ? OverworldBiome.ERODED_BADLANDS : OverworldBiome.BADLANDS))
				: OverworldBiome.DESERT;
		};
	}

	// ------------------------------------------------------------------
	// Columns
	// ------------------------------------------------------------------

	/** Central-difference slope test; neighbours are only sampled for terrain that can be steep. */
	public boolean isSteep(int x, int z, int height) {
		if (height < 72) {
			return false;
		}
		return isSteep(height(x - 1, z), height(x + 1, z), height(x, z - 1), height(x, z + 1));
	}

	public static boolean isSteep(int west, int east, int north, int south) {
		return Math.abs(east - west) >= 5 || Math.abs(south - north) >= 5;
	}

	/**
	 * Builds a column. With {@code lod} set, underground detail is skipped and a fake tree canopy,
	 * snow and ice are added, because Distant Horizons LODs never run features.
	 */
	public void column(int x, int z, Sample s, boolean steep, boolean lod, ColumnRuns out) {
		int h = s.height;
		OverworldBiome biome = s.biome;
		boolean underwater = h < WATER_TOP;
		out.reset(MIN_Y);

		if (lod) {
			out.add(h >= 0 ? STONE : DEEPSLATE, h - 6);
		} else {
			out.add(BEDROCK, MIN_Y + 1);
			for (int y = MIN_Y + 1; y < MIN_Y + 5; y++) {
				// Vanilla's bedrock gradient: certain at the floor, fading out over 5 blocks.
				double chance = (MIN_Y + 5 - y) / 5.0;
				out.add(Hash.unit(Hash.of(seed, x, y, z)) < chance ? BEDROCK : DEEPSLATE, y + 1);
			}
			out.add(DEEPSLATE, Math.min(0, h - 6));
			out.add(STONE, h - 6);
		}

		paintSurface(x, z, h, biome, steep, underwater, out);

		if (underwater) {
			if (biome.frozen) {
				out.add(WATER, WATER_TOP);
				out.add(lod ? ICE : WATER, WATER_TOP + 1);
			} else {
				out.add(WATER, WATER_TOP + 1);
			}
		} else if (lod) {
			if (biome.snowy && !steep) {
				out.replaceTop(SNOW_BLOCK);
			} else if (biome.leaves != null && biome.treeCover > 0f && OptiGenConfig.get().lodTreeCanopy) {
				long hash = Hash.of(seed ^ 0x7EEL, x, z);
				if (Hash.unit(hash) < biome.treeCover) {
					int trunk = 3 + (int) ((hash >>> 8) & 1);
					int crown = 3 + (int) ((hash >>> 9) & 3);
					out.add(biome.log, h + 1 + trunk);
					out.add(biome.leaves, h + 1 + trunk + crown);
				}
			}
		}
	}

	/** Fills the six blocks below and including the surface. Everything below is already stone. */
	private void paintSurface(int x, int z, int h, OverworldBiome biome, boolean steep, boolean underwater, ColumnRuns out) {
		int top = h + 1;
		long hash = Hash.of(seed ^ 0x5F0CEL, x, z);
		int soil = 3 + (int) (hash & 1);

		if (steep && biome.surface != OverworldBiome.Surface.BADLANDS && !underwater) {
			// Cliffs show bare stone, exactly like vanilla's steep surface rule.
			if ((biome.snowy || h > 200) && biome.surface != OverworldBiome.Surface.STONE) {
				out.add(stoneFor(h - 6), top - 1);
				out.add(SNOW_BLOCK, top);
			} else {
				out.add(stoneFor(h - 6), top);
			}
			return;
		}

		switch (biome.surface) {
			case GRASS -> {
				out.add(stoneFor(h - 6), top - soil);
				out.add(DIRT, top - 1);
				out.add(underwater ? DIRT : GRASS, top);
			}
			case PODZOL -> {
				out.add(stoneFor(h - 6), top - soil);
				out.add(DIRT, top - 1);
				out.add(underwater ? DIRT : ((hash >>> 4) % 5 == 0 ? COARSE_DIRT : PODZOL), top);
			}
			case GRAVEL -> {
				out.add(stoneFor(h - 6), top - soil);
				out.add(DIRT, top - 1);
				out.add(((hash >>> 4) & 3) == 0 ? GRASS : GRAVEL, top);
			}
			case DESERT -> {
				out.add(SANDSTONE, top - 4);
				out.add(SAND, top);
			}
			case BEACH -> {
				out.add(SANDSTONE, top - 4);
				out.add(SAND, top);
			}
			case BADLANDS -> {
				if (h <= 74 && !steep) {
					out.add(bandAt(top - 3), top - 2);
					out.add(RED_SAND, top);
				} else {
					// Terracotta strata all the way up, the signature badlands look.
					for (int y = h - 5; y <= h; y++) {
						out.add(bandAt(y), y + 1);
					}
				}
			}
			case MUD -> {
				out.add(stoneFor(h - 6), top - soil);
				out.add(MUD, top);
			}
			case SNOW -> {
				out.add(stoneFor(h - 6), top - 2);
				out.add(SNOW_BLOCK, top);
			}
			case ICE_PEAK -> {
				out.add(stoneFor(h - 6), top - 1);
				out.add(((hash >>> 4) & 1) == 0 ? PACKED_ICE : SNOW_BLOCK, top);
			}
			case STONE -> out.add(stoneFor(h), top);
			case RIVER_BED -> {
				out.add(stoneFor(h - 6), top - soil);
				out.add(((hash >>> 4) % 6 == 0) ? CLAY : SAND, top);
			}
			case OCEAN_SAND -> {
				out.add(stoneFor(h - 6), top - soil);
				out.add(h < 45 ? GRAVEL : SAND, top);
			}
			case OCEAN_GRAVEL -> {
				out.add(stoneFor(h - 6), top - soil);
				out.add(GRAVEL, top);
			}
		}
	}

	private static BlockState stoneFor(int y) {
		return y < 0 ? DEEPSLATE : STONE;
	}

	private BlockState bandAt(int y) {
		return badlandsBands[Math.floorMod(y, badlandsBands.length)];
	}
}
