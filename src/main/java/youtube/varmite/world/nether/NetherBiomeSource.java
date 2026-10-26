package youtube.varmite.world.nether;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;
import youtube.varmite.OptiGen;
import youtube.varmite.world.OptiBiomeSource;
import youtube.varmite.world.nether.NetherTerrain.NetherBiome;

public final class NetherBiomeSource extends OptiBiomeSource<NetherBiome> {
	public static final MapCodec<NetherBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(RegistryOps.retrieveGetter(Registries.BIOME)).apply(i, i.stable(NetherBiomeSource::new))
	);

	private volatile @Nullable NetherTerrain terrain;

	public NetherBiomeSource(HolderGetter<Biome> biomes) {
		super(biomes, NetherBiome.values(), biome -> biome.key);
	}

	void bind(NetherTerrain terrain) {
		this.terrain = terrain;
	}

	@Override
	protected NetherBiome biomeAt(int blockX, int blockZ) {
		NetherTerrain current = terrain;
		if (current == null) {
			OptiGen.LOGGER.warn("Nether biomes queried before the seed was known, using seed 0");
			current = new NetherTerrain(0L);
			terrain = current;
		}
		return current.biome(blockX, blockZ);
	}

	@Override
	protected MapCodec<NetherBiomeSource> codec() {
		return CODEC;
	}
}
