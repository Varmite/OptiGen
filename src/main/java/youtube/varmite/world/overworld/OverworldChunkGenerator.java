package youtube.varmite.world.overworld;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.codec.RegistryCodecs;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import youtube.varmite.world.ColumnRuns;
import youtube.varmite.world.FeatureFilter;
import youtube.varmite.world.OptiChunkGenerator;

public final class OverworldChunkGenerator extends OptiChunkGenerator<OverworldTerrain> {
	public static final MapCodec<OverworldChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				OverworldBiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> (OverworldBiomeSource) g.getBiomeSource()),
				RegistryCodecs.holderSet(Registries.STRUCTURE_SET).fieldOf("structure_sets").forGetter(OptiChunkGenerator::structureSets)
			)
			.apply(i, i.stable(OverworldChunkGenerator::new))
	);

	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

	private volatile ChunkPos origin;

	public OverworldChunkGenerator(OverworldBiomeSource biomeSource, HolderSet<StructureSet> structureSets) {
		super(biomeSource, structureSets, FeatureFilter.overworld());
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	protected OverworldTerrain createTerrain(long seed) {
		return new OverworldTerrain(seed);
	}

	@Override
	protected void bindTerrain(OverworldTerrain terrain) {
		((OverworldBiomeSource) biomeSource).bind(terrain);
	}

	/** Heights for the chunk plus a one-block border, so slopes never need extra samples. */
	private static final class Scratch {
		final int[] heights = new int[18 * 18];
		final OverworldTerrain.Sample[] samples = new OverworldTerrain.Sample[256];

		Scratch() {
			for (int i = 0; i < samples.length; i++) {
				samples[i] = new OverworldTerrain.Sample();
			}
		}
	}

	@Override
	protected void buildColumns(OverworldTerrain terrain, int minBlockX, int minBlockZ, ColumnRuns[] columns) {
		Scratch scratch = SCRATCH.get();
		int[] heights = scratch.heights;
		for (int dz = -1; dz <= 16; dz++) {
			for (int dx = -1; dx <= 16; dx++) {
				int x = minBlockX + dx;
				int z = minBlockZ + dz;
				int index = (dx + 1) + (dz + 1) * 18;
				if (dx >= 0 && dx < 16 && dz >= 0 && dz < 16) {
					OverworldTerrain.Sample sample = scratch.samples[dx + (dz << 4)];
					terrain.sample(x, z, sample);
					heights[index] = sample.height;
				} else {
					heights[index] = terrain.height(x, z);
				}
			}
		}

		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int index = (dx + 1) + (dz + 1) * 18;
				int h = heights[index];
				boolean steep = h >= 72 && OverworldTerrain.isSteep(heights[index - 1], heights[index + 1], heights[index - 18], heights[index + 18]);
				int i = dx + (dz << 4);
				terrain.column(minBlockX + dx, minBlockZ + dz, scratch.samples[i], steep, false, columns[i]);
			}
		}
	}

	@Override
	protected void buildColumn(OverworldTerrain terrain, int x, int z, ColumnRuns out) {
		OverworldTerrain.Sample sample = new OverworldTerrain.Sample();
		terrain.sample(x, z, sample);
		terrain.column(x, z, sample, terrain.isSteep(x, z, sample.height), false, out);
	}

	/** Structures call this constantly; the answer only needs the heightmap, never the full column. */
	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		int h = terrain(randomState).height(x, z);
		if (h < OverworldTerrain.WATER_TOP && type.isOpaque().test(WATER)) {
			return OverworldTerrain.WATER_TOP + 1;
		}
		return h + 1;
	}

	/** World spawn: the nearest dry land to 0,0 (the continent noise is already biased towards land there). */
	@Override
	public ChunkPos getOrigin(RandomState randomState) {
		ChunkPos cached = origin;
		if (cached != null) {
			return cached;
		}
		OverworldTerrain terrain = terrain(randomState);
		cached = ChunkPos.ZERO;
		search:
		for (int radius = 0; radius <= 32; radius++) {
			for (int cx = -radius; cx <= radius; cx++) {
				for (int cz = -radius; cz <= radius; cz++) {
					if (Math.max(Math.abs(cx), Math.abs(cz)) != radius) {
						continue;
					}
					int h = terrain.height(cx * 64 + 8, cz * 64 + 8);
					if (h > OverworldTerrain.SEA_LEVEL && h < 110) {
						cached = new ChunkPos(cx * 4, cz * 4);
						break search;
					}
				}
			}
		}
		origin = cached;
		return cached;
	}

	@Override
	public int getSpawnHeight(LevelHeightAccessor heightAccessor) {
		ChunkPos spawn = origin == null ? ChunkPos.ZERO : origin;
		OverworldTerrain terrain = terrainIfReady();
		if (terrain == null) {
			return OverworldTerrain.SEA_LEVEL + 1;
		}
		return Math.max(terrain.height(spawn.getMinBlockX() + 8, spawn.getMinBlockZ() + 8), OverworldTerrain.WATER_TOP) + 1;
	}

	@Override
	public int getGenDepth() {
		return OverworldTerrain.HEIGHT;
	}

	@Override
	public int getSeaLevel() {
		return OverworldTerrain.SEA_LEVEL;
	}

	@Override
	public int getMinY() {
		return OverworldTerrain.MIN_Y;
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		OverworldTerrain terrain = terrain(randomState);
		OverworldTerrain.Sample sample = new OverworldTerrain.Sample();
		terrain.sample(feetPos.getX(), feetPos.getZ(), sample);
		result.add("OptiGen overworld: surface=" + sample.height + " biome=" + sample.biome.name().toLowerCase());
	}
}
