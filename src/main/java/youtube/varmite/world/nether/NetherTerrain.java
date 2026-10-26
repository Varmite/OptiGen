package youtube.varmite.world.nether;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import youtube.varmite.world.ColumnRuns;
import youtube.varmite.world.noise.Hash;
import youtube.varmite.world.noise.Noise2D;

import static youtube.varmite.world.noise.Hash.smoothstep;

/**
 * The Nether as two heightmaps: a floor with a lava sea at y=31 and an uneven ceiling, joined by
 * occasional pillars. Fortresses and bastions are placed by vanilla on top of this, which is all
 * the Nether has to provide for the game to be beatable (blaze rods, a way to the stronghold).
 */
public final class NetherTerrain {
	public static final int MIN_Y = 0;
	public static final int GEN_HEIGHT = 128;
	public static final int SEA_LEVEL = 32;
	public static final int LAVA_TOP = SEA_LEVEL - 1;

	private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
	private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
	private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
	private static final BlockState CRIMSON_NYLIUM = Blocks.CRIMSON_NYLIUM.defaultBlockState();
	private static final BlockState WARPED_NYLIUM = Blocks.WARPED_NYLIUM.defaultBlockState();
	private static final BlockState SOUL_SAND = Blocks.SOUL_SAND.defaultBlockState();
	private static final BlockState SOUL_SOIL = Blocks.SOUL_SOIL.defaultBlockState();
	private static final BlockState BASALT = Blocks.BASALT.defaultBlockState();
	private static final BlockState BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState();
	private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();

	public enum NetherBiome {
		NETHER_WASTES(Biomes.NETHER_WASTES),
		SOUL_SAND_VALLEY(Biomes.SOUL_SAND_VALLEY),
		CRIMSON_FOREST(Biomes.CRIMSON_FOREST),
		WARPED_FOREST(Biomes.WARPED_FOREST),
		BASALT_DELTAS(Biomes.BASALT_DELTAS);

		public final ResourceKey<Biome> key;

		NetherBiome(ResourceKey<Biome> key) {
			this.key = key;
		}
	}

	private final long seed;
	private final Noise2D floor;
	private final Noise2D floorDetail;
	private final Noise2D ceiling;
	private final Noise2D stalactites;
	private final Noise2D pillars;
	private final Noise2D temperature;
	private final Noise2D humidity;
	private final Noise2D patches;

	public NetherTerrain(long seed) {
		this.seed = seed;
		this.floor = new Noise2D(seed ^ 0x101L);
		this.floorDetail = new Noise2D(seed ^ 0x102L);
		this.ceiling = new Noise2D(seed ^ 0x103L);
		this.stalactites = new Noise2D(seed ^ 0x104L);
		this.pillars = new Noise2D(seed ^ 0x105L);
		this.temperature = new Noise2D(seed ^ 0x106L);
		this.humidity = new Noise2D(seed ^ 0x107L);
		this.patches = new Noise2D(seed ^ 0x108L);
	}

	public NetherBiome biome(int x, int z) {
		double t = temperature.fbm(x / 420.0, z / 420.0, 2);
		double w = humidity.fbm(x / 420.0, z / 420.0, 2);
		// Nearest of vanilla's nether climate points; the wastes get a bonus to stay the most common.
		NetherBiome best = NetherBiome.NETHER_WASTES;
		double bestDist = t * t + w * w - 0.04;
		double d;
		if ((d = t * t + (w + 0.5) * (w + 0.5)) < bestDist) {
			best = NetherBiome.SOUL_SAND_VALLEY;
			bestDist = d;
		}
		if ((d = (t - 0.4) * (t - 0.4) + w * w) < bestDist) {
			best = NetherBiome.CRIMSON_FOREST;
			bestDist = d;
		}
		if ((d = t * t + (w - 0.5) * (w - 0.5)) < bestDist) {
			best = NetherBiome.WARPED_FOREST;
			bestDist = d;
		}
		if ((t + 0.5) * (t + 0.5) + w * w < bestDist) {
			best = NetherBiome.BASALT_DELTAS;
		}
		return best;
	}

	/** Floor top (inclusive) in {@code [0]}, ceiling bottom (exclusive of the cavern) in {@code [1]}. */
	public void bounds(int x, int z, int[] out) {
		double f = 34.0 + floor.fbm(x / 190.0, z / 190.0, 3) * 18.0;
		double bump = 1.0 - Math.abs(floorDetail.sample(x / 55.0, z / 55.0));
		f += bump * bump * bump * 9.0;

		double c = 100.0 + ceiling.fbm(x / 150.0, z / 150.0, 3) * 13.0;
		double drip = Math.max(0.0, stalactites.sample(x / 17.0, z / 17.0));
		c -= drip * drip * 16.0;

		double p = pillars.sample(x / 95.0, z / 95.0);
		double join = smoothstep(0.6, 0.8, p);
		if (join > 0.0) {
			double mid = (f + c) * 0.5;
			f += (mid - f) * join;
			c -= (c - mid) * join;
		}

		int floorTop = (int) f;
		int ceilingBottom = (int) c;
		if (ceilingBottom - floorTop < 5) {
			floorTop = ceilingBottom;
		}
		out[0] = floorTop;
		out[1] = ceilingBottom;
	}

	public void column(int x, int z, NetherBiome biome, boolean lod, int[] scratch, ColumnRuns out) {
		bounds(x, z, scratch);
		int floorTop = scratch[0];
		int ceilingBottom = scratch[1];
		boolean solid = floorTop >= ceilingBottom;
		out.reset(MIN_Y);

		if (!lod) {
			out.add(BEDROCK, MIN_Y + 1);
			for (int y = MIN_Y + 1; y < MIN_Y + 5; y++) {
				out.add(Hash.unit(Hash.of(seed, x, y, z)) < (MIN_Y + 5 - y) / 5.0 ? BEDROCK : NETHERRACK, y + 1);
			}
		}

		if (solid) {
			out.add(NETHERRACK, GEN_HEIGHT - 5);
		} else {
			paintFloor(x, z, floorTop, biome, out);
			if (floorTop < LAVA_TOP) {
				out.add(LAVA, LAVA_TOP + 1);
			}
			out.add(ColumnRuns.AIR, ceilingBottom);
			out.add(NETHERRACK, GEN_HEIGHT - 5);
		}

		if (lod) {
			out.add(BEDROCK, GEN_HEIGHT);
		} else {
			for (int y = GEN_HEIGHT - 5; y < GEN_HEIGHT - 1; y++) {
				out.add(Hash.unit(Hash.of(seed ^ 0xCE11L, x, y, z)) < (y - (GEN_HEIGHT - 6)) / 5.0 ? BEDROCK : NETHERRACK, y + 1);
			}
			out.add(BEDROCK, GEN_HEIGHT);
		}
	}

	private void paintFloor(int x, int z, int floorTop, NetherBiome biome, ColumnRuns out) {
		int top = floorTop + 1;
		double patch = patches.sample(x / 12.0, z / 12.0);
		switch (biome) {
			case CRIMSON_FOREST -> {
				out.add(NETHERRACK, top - 1);
				out.add(floorTop < LAVA_TOP ? NETHERRACK : CRIMSON_NYLIUM, top);
			}
			case WARPED_FOREST -> {
				out.add(NETHERRACK, top - 1);
				out.add(floorTop < LAVA_TOP ? NETHERRACK : WARPED_NYLIUM, top);
			}
			case SOUL_SAND_VALLEY -> {
				out.add(NETHERRACK, top - 4);
				out.add(SOUL_SOIL, top - 1);
				out.add(patch > 0.0 ? SOUL_SAND : SOUL_SOIL, top);
			}
			case BASALT_DELTAS -> {
				out.add(NETHERRACK, top - 4);
				out.add(patch > 0.2 ? BLACKSTONE : BASALT, top);
			}
			default -> {
				if (floorTop >= LAVA_TOP - 1 && floorTop <= LAVA_TOP + 1 && patch > 0.4) {
					// Gravel and soul sand beaches along the lava shore, like vanilla's wastes.
					out.add(NETHERRACK, top - 2);
					out.add(patch > 0.7 ? SOUL_SAND : GRAVEL, top);
				} else {
					out.add(NETHERRACK, top);
				}
			}
		}
	}
}
