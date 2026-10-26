package youtube.varmite.world;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * A biome source driven by OptiGen's 2D terrain instead of vanilla's climate density functions.
 * Biomes are column-constant (no cave biomes), so a chunk needs only its 4x4 quart columns.
 */
public abstract class OptiBiomeSource<E extends Enum<E>> extends BiomeSource {
	protected final HolderGetter<Biome> biomes;
	private final Holder<Biome>[] holders;

	@SuppressWarnings("unchecked")
	protected OptiBiomeSource(HolderGetter<Biome> biomes, E[] values, Function<E, ResourceKey<Biome>> keyOf) {
		this.biomes = biomes;
		this.holders = new Holder[values.length];
		for (E value : values) {
			holders[value.ordinal()] = biomes.getOrThrow(keyOf.apply(value));
		}
	}

	/** Biome of the column containing the given block position. */
	protected abstract E biomeAt(int blockX, int blockZ);

	public final Holder<Biome> holder(E biome) {
		return holders[biome.ordinal()];
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Arrays.stream(holders);
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		return (quartX, quartY, quartZ) -> holder(biomeAt(QuartPos.toBlock(quartX) + 2, QuartPos.toBlock(quartZ) + 2));
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ, int quartSizeX, int quartSizeY, int quartSizeZ) {
		@SuppressWarnings("unchecked")
		Holder<Biome>[] grid = new Holder[quartSizeX * quartSizeZ];
		for (int z = 0; z < quartSizeZ; z++) {
			for (int x = 0; x < quartSizeX; x++) {
				grid[x + z * quartSizeX] = holder(biomeAt(QuartPos.toBlock(minQuartX + x) + 2, QuartPos.toBlock(minQuartZ + z) + 2));
			}
		}
		return (quartX, quartY, quartZ) -> {
			int x = quartX - minQuartX;
			int z = quartZ - minQuartZ;
			if (x >= 0 && x < quartSizeX && z >= 0 && z < quartSizeZ) {
				return grid[x + z * quartSizeX];
			}
			return holder(biomeAt(QuartPos.toBlock(quartX) + 2, QuartPos.toBlock(quartZ) + 2));
		};
	}
}
