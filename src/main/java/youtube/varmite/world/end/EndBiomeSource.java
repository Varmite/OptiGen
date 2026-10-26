package youtube.varmite.world.end;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;
import youtube.varmite.OptiGen;
import youtube.varmite.world.OptiBiomeSource;
import youtube.varmite.world.end.EndTerrain.EndBiome;

public final class EndBiomeSource extends OptiBiomeSource<EndBiome> {
	public static final MapCodec<EndBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(RegistryOps.retrieveGetter(Registries.BIOME)).apply(i, i.stable(EndBiomeSource::new))
	);

	private volatile @Nullable EndTerrain terrain;

	public EndBiomeSource(HolderGetter<Biome> biomes) {
		super(biomes, EndBiome.values(), biome -> biome.key);
	}

	void bind(EndTerrain terrain) {
		this.terrain = terrain;
	}

	@Override
	protected EndBiome biomeAt(int blockX, int blockZ) {
		EndTerrain current = terrain;
		if (current == null) {
			OptiGen.LOGGER.warn("End biomes queried before the seed was known, using seed 0");
			current = new EndTerrain(0L);
			terrain = current;
		}
		return current.biome(blockX, blockZ);
	}

	@Override
	protected MapCodec<EndBiomeSource> codec() {
		return CODEC;
	}
}
