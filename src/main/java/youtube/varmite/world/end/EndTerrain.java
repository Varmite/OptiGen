package youtube.varmite.world.end;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import youtube.varmite.world.ColumnRuns;
import youtube.varmite.world.noise.Noise2D;

/**
 * The End, using vanilla's own 2D island layout function (the central island plus outer islands
 * seeded on a 16-block lattice) but turned straight into top/bottom heights instead of feeding a
 * 3D density field. The central island at 0,0 is what the dragon fight, spikes and exit portal need.
 */
public final class EndTerrain {
	public static final int MIN_Y = 0;
	public static final int GEN_HEIGHT = 128;
	private static final int ISLAND_Y = 56;

	private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();

	public enum EndBiome {
		THE_END(Biomes.THE_END),
		END_HIGHLANDS(Biomes.END_HIGHLANDS),
		END_MIDLANDS(Biomes.END_MIDLANDS),
		SMALL_END_ISLANDS(Biomes.SMALL_END_ISLANDS),
		END_BARRENS(Biomes.END_BARRENS);

		public final ResourceKey<Biome> key;

		EndBiome(ResourceKey<Biome> key) {
			this.key = key;
		}
	}

	private final Noise2D islands;
	private final Noise2D detail;

	public EndTerrain(long seed) {
		this.islands = new Noise2D(seed ^ 0xE4DL);
		this.detail = new Noise2D(seed ^ 0xE4EL);
	}

	/** Vanilla's biome rule: the main island area, then the island value at the chunk centre. */
	public EndBiome biome(int blockX, int blockZ) {
		long chunkX = blockX >> 4;
		long chunkZ = blockZ >> 4;
		if (chunkX * chunkX + chunkZ * chunkZ <= 4096L) {
			return EndBiome.THE_END;
		}
		// All 16 biome lookups of a chunk hit the same chunk centre: remember the last one per thread.
		long key = (chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
		LastBiome last = LAST_BIOME.get();
		if (last.terrain == this && last.key == key) {
			return last.biome;
		}
		EndBiome biome = biomeFor(cellValue((int) chunkX * 2 + 1, (int) chunkZ * 2 + 1, null));
		last.terrain = this;
		last.key = key;
		last.biome = biome;
		return biome;
	}

	private static final ThreadLocal<LastBiome> LAST_BIOME = ThreadLocal.withInitial(LastBiome::new);

	private static final class LastBiome {
		EndTerrain terrain;
		long key;
		EndBiome biome;
	}

	/** Biome for a column whose island value is already known; avoids a second island scan. */
	public static EndBiome biome(int blockX, int blockZ, float value) {
		long chunkX = blockX >> 4;
		long chunkZ = blockZ >> 4;
		return chunkX * chunkX + chunkZ * chunkZ <= 4096L ? EndBiome.THE_END : biomeFor(value);
	}

	private static EndBiome biomeFor(float value) {
		if (value > 40.0F) {
			return EndBiome.END_HIGHLANDS;
		} else if (value >= 0.0F) {
			return EndBiome.END_MIDLANDS;
		} else {
			return value < -20.0F ? EndBiome.SMALL_END_ISLANDS : EndBiome.END_BARRENS;
		}
	}

	/** Lattice points (16 blocks apart) that spawn an outer island, for a region. Saves ~600 noise samples per cell. */
	public final class IslandCandidates {
		private final IntArrayList points = new IntArrayList();

		/** Collects candidates affecting cells in the given (inclusive) cell range. */
		public IslandCandidates collect(int minCellX, int minCellZ, int maxCellX, int maxCellZ) {
			points.clear();
			// One extra lattice point of padding: vanilla's truncating division can shift i/j by one.
			int minI = Math.floorDiv(minCellX, 2) - 13;
			int maxI = Math.floorDiv(maxCellX, 2) + 13;
			int minJ = Math.floorDiv(minCellZ, 2) - 13;
			int maxJ = Math.floorDiv(maxCellZ, 2) + 13;
			for (int o = minI; o <= maxI; o++) {
				for (int p = minJ; p <= maxJ; p++) {
					if (isIslandSeed(o, p)) {
						points.add(o);
						points.add(p);
					}
				}
			}
			return this;
		}
	}

	public IslandCandidates newCandidates() {
		return new IslandCandidates();
	}

	private boolean isIslandSeed(long o, long p) {
		return o * o + p * p > 4096L && islands.sample(o, p) < -0.9;
	}

	/**
	 * Vanilla {@code EndIslandDensityFunction.getHeightValue}: 100 at the centre falling off with
	 * distance, max'ed with every nearby outer island. Range [-100, 80], positive means land.
	 */
	public float cellValue(int cellX, int cellZ, IslandCandidates candidates) {
		int i = cellX / 2;
		int j = cellZ / 2;
		int k = cellX % 2;
		int l = cellZ % 2;
		float value = 100.0F - (float) Math.sqrt((float) cellX * cellX + (float) cellZ * cellZ) * 8.0F;
		value = Math.clamp(value, -100.0F, 80.0F);

		if ((long) (Math.abs(i) + 12) * (Math.abs(i) + 12) + (long) (Math.abs(j) + 12) * (Math.abs(j) + 12) <= 4096L) {
			// Every lattice point in range is inside the void ring around the main island.
			return value;
		}

		if (candidates != null) {
			IntArrayList points = candidates.points;
			for (int n = 0; n < points.size(); n += 2) {
				int o = points.getInt(n);
				int p = points.getInt(n + 1);
				int m = o - i;
				int q = p - j;
				if (m >= -12 && m <= 12 && q >= -12 && q <= 12) {
					value = Math.max(value, islandContribution(o, p, k - m * 2, l - q * 2));
				}
			}
			return value;
		}

		for (int m = -12; m <= 12; m++) {
			for (int n = -12; n <= 12; n++) {
				long o = i + m;
				long p = j + n;
				if (isIslandSeed(o, p)) {
					value = Math.max(value, islandContribution(o, p, k - m * 2, l - n * 2));
				}
			}
		}
		return value;
	}

	private static float islandContribution(long o, long p, float dx, float dz) {
		float size = (Math.abs((float) o) * 3439.0F + Math.abs((float) p) * 147.0F) % 13.0F + 9.0F;
		float value = 100.0F - (float) Math.sqrt(dx * dx + dz * dz) * size;
		return Math.clamp(value, -100.0F, 80.0F);
	}

	/** Builds a column from an island value already interpolated to the block position. */
	public void column(int x, int z, float value, ColumnRuns out) {
		out.reset(MIN_Y);
		if (value <= 0.0F) {
			return;
		}
		double bump = detail.sample(x / 24.0, z / 24.0) * 1.5;
		int top = (int) (ISLAND_Y + value * 0.13 + bump);
		int bottom = (int) (ISLAND_Y - value * 0.55);
		if (top <= bottom) {
			return;
		}
		out.add(ColumnRuns.AIR, Math.max(MIN_Y, bottom));
		out.add(END_STONE, top + 1);
	}

	/** Bilinear interpolation between the four surrounding 8-block cells, like vanilla's noise cells. */
	public static float interpolate(float v00, float v10, float v01, float v11, int x, int z) {
		float fx = (x & 7) / 8.0F;
		float fz = (z & 7) / 8.0F;
		float a = v00 + (v10 - v00) * fx;
		float b = v01 + (v11 - v01) * fx;
		return a + (b - a) * fz;
	}

	/** Island value at a single block, for one-off queries (structures, LODs at fine detail). */
	public float valueAt(int x, int z, IslandCandidates candidates) {
		int cx = x >> 3;
		int cz = z >> 3;
		return interpolate(
			cellValue(cx, cz, candidates),
			cellValue(cx + 1, cz, candidates),
			cellValue(cx, cz + 1, candidates),
			cellValue(cx + 1, cz + 1, candidates),
			x, z
		);
	}
}
