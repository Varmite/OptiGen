package youtube.varmite.world.overworld;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;
import youtube.varmite.OptiGen;
import youtube.varmite.world.OptiBiomeSource;

public final class OverworldBiomeSource extends OptiBiomeSource<OverworldBiome> {
	public static final MapCodec<OverworldBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(RegistryOps.retrieveGetter(Registries.BIOME)).apply(i, i.stable(OverworldBiomeSource::new))
	);

	private volatile @Nullable OverworldTerrain terrain;

	public OverworldBiomeSource(HolderGetter<Biome> biomes) {
		super(biomes, OverworldBiome.values(), biome -> biome.key);
	}

	void bind(OverworldTerrain terrain) {
		this.terrain = terrain;
	}

	@Override
	protected OverworldBiome biomeAt(int blockX, int blockZ) {
		OverworldTerrain current = terrain;
		if (current == null) {
			OptiGen.LOGGER.warn("Overworld biomes queried before the seed was known, using seed 0");
			current = new OverworldTerrain(0L);
			terrain = current;
		}
		return current.biome(blockX, blockZ);
	}

	@Override
	protected MapCodec<OverworldBiomeSource> codec() {
		return CODEC;
	}
}
