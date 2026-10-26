package youtube.varmite.compat.dh;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import youtube.varmite.world.ColumnRuns;
import youtube.varmite.world.OptiChunkGenerator;
import youtube.varmite.world.end.EndBiomeSource;
import youtube.varmite.world.end.EndChunkGenerator;
import youtube.varmite.world.end.EndTerrain;
import youtube.varmite.world.nether.NetherBiomeSource;
import youtube.varmite.world.nether.NetherChunkGenerator;
import youtube.varmite.world.nether.NetherTerrain;
import youtube.varmite.world.overworld.OverworldBiomeSource;
import youtube.varmite.world.overworld.OverworldChunkGenerator;
import youtube.varmite.world.overworld.OverworldTerrain;

/**
 * Produces simplified columns for LODs straight from OptiGen's terrain functions. Contains no
 * Distant Horizons types so the conversion logic stays independent of the DH API.
 */
public interface LodColumnSource {
	/**
	 * Starts one LOD request covering {@code width x width} columns, each standing in for
	 * {@code step x step} blocks. Areas are used by a single thread and then discarded.
	 */
	Area begin(int minBlockX, int minBlockZ, int step, int width);

	interface Area {
		/** Fills {@code out} for the column at the given block position and returns its biome. */
		Holder<Biome> column(int x, int z, ColumnRuns out);
	}

	static LodColumnSource forGenerator(OptiChunkGenerator<?> generator, RandomState randomState) {
		return switch (generator) {
			case OverworldChunkGenerator overworld -> overworld(overworld.terrain(randomState), (OverworldBiomeSource) overworld.getBiomeSource());
			case NetherChunkGenerator nether -> nether(nether.terrain(randomState), (NetherBiomeSource) nether.getBiomeSource());
			case EndChunkGenerator end -> end(end.terrain(randomState), (EndBiomeSource) end.getBiomeSource());
			default -> throw new IllegalArgumentException("Unknown OptiGen generator " + generator);
		};
	}

	private static LodColumnSource overworld(OverworldTerrain terrain, OverworldBiomeSource biomes) {
		return (minBlockX, minBlockZ, step, width) -> {
			OverworldTerrain.Sample sample = new OverworldTerrain.Sample();
			return (x, z, out) -> {
				terrain.sample(x, z, sample);
				terrain.column(x, z, sample, terrain.isSteep(x, z, sample.height), true, out);
				return biomes.holder(sample.biome);
			};
		};
	}

	private static LodColumnSource nether(NetherTerrain terrain, NetherBiomeSource biomes) {
		return (minBlockX, minBlockZ, step, width) -> {
			int[] bounds = new int[2];
			return (x, z, out) -> {
				NetherTerrain.NetherBiome biome = terrain.biome(x, z);
				terrain.column(x, z, biome, true, bounds, out);
				return biomes.holder(biome);
			};
		};
	}

	private static LodColumnSource end(EndTerrain terrain, EndBiomeSource biomes) {
		return (minBlockX, minBlockZ, step, width) -> {
			int span = width * step;
			// Pre-collecting island seeds beats vanilla's 625-sample scan per cell until the area gets
			// huge; for the coarsest LODs scanning per column is cheaper than scanning the whole area.
			long lattice = span / 16L + 28L;
			EndTerrain.IslandCandidates candidates = lattice * lattice <= 250_000L
				? terrain.newCandidates().collect(minBlockX >> 3, minBlockZ >> 3, (minBlockX + span) >> 3, (minBlockZ + span) >> 3)
				: null;
			return (x, z, out) -> {
				float value = step >= 8
					? terrain.cellValue(x >> 3, z >> 3, candidates)
					: terrain.valueAt(x, z, candidates);
				terrain.column(x, z, value, out);
				return biomes.holder(EndTerrain.biome(x, z, value));
			};
		};
	}
}
