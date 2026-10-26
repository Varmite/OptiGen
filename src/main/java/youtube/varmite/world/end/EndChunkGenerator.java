package youtube.varmite.world.end;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.codec.RegistryCodecs;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import youtube.varmite.world.ColumnRuns;
import youtube.varmite.world.FeatureFilter;
import youtube.varmite.world.OptiChunkGenerator;

public final class EndChunkGenerator extends OptiChunkGenerator<EndTerrain> {
	public static final MapCodec<EndChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				EndBiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> (EndBiomeSource) g.getBiomeSource()),
				RegistryCodecs.holderSet(Registries.STRUCTURE_SET).fieldOf("structure_sets").forGetter(OptiChunkGenerator::structureSets)
			)
			.apply(i, i.stable(EndChunkGenerator::new))
	);

	public EndChunkGenerator(EndBiomeSource biomeSource, HolderSet<StructureSet> structureSets) {
		super(biomeSource, structureSets, FeatureFilter.end());
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	protected EndTerrain createTerrain(long seed) {
		return new EndTerrain(seed);
	}

	@Override
	protected void bindTerrain(EndTerrain terrain) {
		((EndBiomeSource) biomeSource).bind(terrain);
	}

	@Override
	protected void buildColumns(EndTerrain terrain, int minBlockX, int minBlockZ, ColumnRuns[] columns) {
		// A chunk spans 2x2 cells; interpolation needs the 3x3 corners around them.
		int cellX = minBlockX >> 3;
		int cellZ = minBlockZ >> 3;
		EndTerrain.IslandCandidates candidates = terrain.newCandidates().collect(cellX, cellZ, cellX + 2, cellZ + 2);
		float[] cells = new float[9];
		for (int cz = 0; cz < 3; cz++) {
			for (int cx = 0; cx < 3; cx++) {
				cells[cx + cz * 3] = terrain.cellValue(cellX + cx, cellZ + cz, candidates);
			}
		}

		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int c = (dx >> 3) + (dz >> 3) * 3;
				float value = EndTerrain.interpolate(cells[c], cells[c + 1], cells[c + 3], cells[c + 4], dx, dz);
				terrain.column(minBlockX + dx, minBlockZ + dz, value, columns[dx + (dz << 4)]);
			}
		}
	}

	@Override
	protected void buildColumn(EndTerrain terrain, int x, int z, ColumnRuns out) {
		terrain.column(x, z, terrain.valueAt(x, z, null), out);
	}

	@Override
	public int getGenDepth() {
		return EndTerrain.GEN_HEIGHT;
	}

	@Override
	public int getSeaLevel() {
		return 0;
	}

	@Override
	public int getMinY() {
		return EndTerrain.MIN_Y;
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		EndTerrain terrain = terrain(randomState);
		result.add("OptiGen end: island=" + terrain.valueAt(feetPos.getX(), feetPos.getZ(), null)
			+ " biome=" + terrain.biome(feetPos.getX(), feetPos.getZ()).name().toLowerCase());
	}
}
