package youtube.varmite.world.nether;

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

public final class NetherChunkGenerator extends OptiChunkGenerator<NetherTerrain> {
	public static final MapCodec<NetherChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				NetherBiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> (NetherBiomeSource) g.getBiomeSource()),
				RegistryCodecs.holderSet(Registries.STRUCTURE_SET).fieldOf("structure_sets").forGetter(OptiChunkGenerator::structureSets)
			)
			.apply(i, i.stable(NetherChunkGenerator::new))
	);

	private static final ThreadLocal<int[]> BOUNDS = ThreadLocal.withInitial(() -> new int[2]);

	public NetherChunkGenerator(NetherBiomeSource biomeSource, HolderSet<StructureSet> structureSets) {
		super(biomeSource, structureSets, FeatureFilter.nether());
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	protected NetherTerrain createTerrain(long seed) {
		return new NetherTerrain(seed);
	}

	@Override
	protected void bindTerrain(NetherTerrain terrain) {
		((NetherBiomeSource) biomeSource).bind(terrain);
	}

	@Override
	protected void buildColumns(NetherTerrain terrain, int minBlockX, int minBlockZ, ColumnRuns[] columns) {
		int[] bounds = BOUNDS.get();
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int x = minBlockX + dx;
				int z = minBlockZ + dz;
				terrain.column(x, z, terrain.biome(x, z), false, bounds, columns[dx + (dz << 4)]);
			}
		}
	}

	@Override
	protected void buildColumn(NetherTerrain terrain, int x, int z, ColumnRuns out) {
		terrain.column(x, z, terrain.biome(x, z), false, new int[2], out);
	}

	@Override
	public int getGenDepth() {
		return NetherTerrain.GEN_HEIGHT;
	}

	@Override
	public int getSeaLevel() {
		return NetherTerrain.SEA_LEVEL;
	}

	@Override
	public int getMinY() {
		return NetherTerrain.MIN_Y;
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		NetherTerrain terrain = terrain(randomState);
		int[] bounds = new int[2];
		terrain.bounds(feetPos.getX(), feetPos.getZ(), bounds);
		result.add("OptiGen nether: floor=" + bounds[0] + " ceiling=" + bounds[1] + " biome=" + terrain.biome(feetPos.getX(), feetPos.getZ()).name().toLowerCase());
	}
}
