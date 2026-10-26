package youtube.varmite.world.overworld;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The vanilla biomes OptiGen places in the overworld, plus what it needs to know to paint them
 * without running vanilla's surface rule engine. Cave biomes are skipped: there are no caves.
 * <p>
 * {@code treeCover} and the leaf/log blocks are only used for Distant Horizons LODs, which are
 * generated without running features, so forests get a cheap fake canopy instead of bald hills.
 */
public enum OverworldBiome {
	PLAINS(Biomes.PLAINS, Surface.GRASS, Blocks.OAK_LEAVES, Blocks.OAK_LOG, 0.03f),
	SUNFLOWER_PLAINS(Biomes.SUNFLOWER_PLAINS, Surface.GRASS, Blocks.OAK_LEAVES, Blocks.OAK_LOG, 0.02f),
	SNOWY_PLAINS(Biomes.SNOWY_PLAINS, Surface.GRASS, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 0.02f, Flags.SNOWY),
	ICE_SPIKES(Biomes.ICE_SPIKES, Surface.SNOW, null, null, 0f, Flags.SNOWY),
	DESERT(Biomes.DESERT, Surface.DESERT, null, null, 0f),
	SWAMP(Biomes.SWAMP, Surface.GRASS, Blocks.OAK_LEAVES, Blocks.OAK_LOG, 0.35f),
	MANGROVE_SWAMP(Biomes.MANGROVE_SWAMP, Surface.MUD, Blocks.MANGROVE_LEAVES, Blocks.MANGROVE_LOG, 0.6f),
	FOREST(Biomes.FOREST, Surface.GRASS, Blocks.OAK_LEAVES, Blocks.OAK_LOG, 0.75f),
	FLOWER_FOREST(Biomes.FLOWER_FOREST, Surface.GRASS, Blocks.OAK_LEAVES, Blocks.BIRCH_LOG, 0.45f),
	BIRCH_FOREST(Biomes.BIRCH_FOREST, Surface.GRASS, Blocks.BIRCH_LEAVES, Blocks.BIRCH_LOG, 0.75f),
	OLD_GROWTH_BIRCH_FOREST(Biomes.OLD_GROWTH_BIRCH_FOREST, Surface.GRASS, Blocks.BIRCH_LEAVES, Blocks.BIRCH_LOG, 0.8f),
	DARK_FOREST(Biomes.DARK_FOREST, Surface.GRASS, Blocks.DARK_OAK_LEAVES, Blocks.DARK_OAK_LOG, 0.95f),
	PALE_GARDEN(Biomes.PALE_GARDEN, Surface.GRASS, Blocks.PALE_OAK_LEAVES, Blocks.PALE_OAK_LOG, 0.9f),
	TAIGA(Biomes.TAIGA, Surface.GRASS, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 0.6f),
	OLD_GROWTH_PINE_TAIGA(Biomes.OLD_GROWTH_PINE_TAIGA, Surface.PODZOL, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 0.75f),
	OLD_GROWTH_SPRUCE_TAIGA(Biomes.OLD_GROWTH_SPRUCE_TAIGA, Surface.PODZOL, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 0.8f),
	SNOWY_TAIGA(Biomes.SNOWY_TAIGA, Surface.GRASS, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 0.55f, Flags.SNOWY),
	SAVANNA(Biomes.SAVANNA, Surface.GRASS, Blocks.ACACIA_LEAVES, Blocks.ACACIA_LOG, 0.1f),
	SAVANNA_PLATEAU(Biomes.SAVANNA_PLATEAU, Surface.GRASS, Blocks.ACACIA_LEAVES, Blocks.ACACIA_LOG, 0.15f),
	WINDSWEPT_HILLS(Biomes.WINDSWEPT_HILLS, Surface.GRASS, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 0.08f),
	WINDSWEPT_GRAVELLY_HILLS(Biomes.WINDSWEPT_GRAVELLY_HILLS, Surface.GRAVEL, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 0.04f),
	WINDSWEPT_FOREST(Biomes.WINDSWEPT_FOREST, Surface.GRASS, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 0.45f),
	WINDSWEPT_SAVANNA(Biomes.WINDSWEPT_SAVANNA, Surface.GRASS, Blocks.ACACIA_LEAVES, Blocks.ACACIA_LOG, 0.06f),
	JUNGLE(Biomes.JUNGLE, Surface.GRASS, Blocks.JUNGLE_LEAVES, Blocks.JUNGLE_LOG, 0.95f),
	SPARSE_JUNGLE(Biomes.SPARSE_JUNGLE, Surface.GRASS, Blocks.JUNGLE_LEAVES, Blocks.JUNGLE_LOG, 0.4f),
	BAMBOO_JUNGLE(Biomes.BAMBOO_JUNGLE, Surface.GRASS, Blocks.JUNGLE_LEAVES, Blocks.JUNGLE_LOG, 0.8f),
	BADLANDS(Biomes.BADLANDS, Surface.BADLANDS, null, null, 0f),
	ERODED_BADLANDS(Biomes.ERODED_BADLANDS, Surface.BADLANDS, null, null, 0f),
	WOODED_BADLANDS(Biomes.WOODED_BADLANDS, Surface.BADLANDS, Blocks.OAK_LEAVES, Blocks.OAK_LOG, 0.2f),
	MEADOW(Biomes.MEADOW, Surface.GRASS, Blocks.BIRCH_LEAVES, Blocks.BIRCH_LOG, 0.02f),
	CHERRY_GROVE(Biomes.CHERRY_GROVE, Surface.GRASS, Blocks.CHERRY_LEAVES, Blocks.CHERRY_LOG, 0.35f),
	GROVE(Biomes.GROVE, Surface.SNOW, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 0.5f, Flags.SNOWY),
	SNOWY_SLOPES(Biomes.SNOWY_SLOPES, Surface.SNOW, null, null, 0f, Flags.SNOWY),
	FROZEN_PEAKS(Biomes.FROZEN_PEAKS, Surface.ICE_PEAK, null, null, 0f, Flags.SNOWY),
	JAGGED_PEAKS(Biomes.JAGGED_PEAKS, Surface.SNOW, null, null, 0f, Flags.SNOWY),
	STONY_PEAKS(Biomes.STONY_PEAKS, Surface.STONE, null, null, 0f),
	RIVER(Biomes.RIVER, Surface.RIVER_BED, null, null, 0f),
	FROZEN_RIVER(Biomes.FROZEN_RIVER, Surface.RIVER_BED, null, null, 0f, Flags.SNOWY | Flags.FROZEN),
	BEACH(Biomes.BEACH, Surface.BEACH, null, null, 0f),
	SNOWY_BEACH(Biomes.SNOWY_BEACH, Surface.BEACH, null, null, 0f, Flags.SNOWY),
	STONY_SHORE(Biomes.STONY_SHORE, Surface.STONE, null, null, 0f),
	WARM_OCEAN(Biomes.WARM_OCEAN, Surface.OCEAN_SAND, null, null, 0f),
	LUKEWARM_OCEAN(Biomes.LUKEWARM_OCEAN, Surface.OCEAN_SAND, null, null, 0f),
	DEEP_LUKEWARM_OCEAN(Biomes.DEEP_LUKEWARM_OCEAN, Surface.OCEAN_SAND, null, null, 0f),
	OCEAN(Biomes.OCEAN, Surface.OCEAN_SAND, null, null, 0f),
	DEEP_OCEAN(Biomes.DEEP_OCEAN, Surface.OCEAN_GRAVEL, null, null, 0f),
	COLD_OCEAN(Biomes.COLD_OCEAN, Surface.OCEAN_GRAVEL, null, null, 0f),
	DEEP_COLD_OCEAN(Biomes.DEEP_COLD_OCEAN, Surface.OCEAN_GRAVEL, null, null, 0f),
	FROZEN_OCEAN(Biomes.FROZEN_OCEAN, Surface.OCEAN_GRAVEL, null, null, 0f, Flags.SNOWY | Flags.FROZEN),
	DEEP_FROZEN_OCEAN(Biomes.DEEP_FROZEN_OCEAN, Surface.OCEAN_GRAVEL, null, null, 0f, Flags.SNOWY | Flags.FROZEN);

	public final ResourceKey<Biome> key;
	public final Surface surface;
	public final @Nullable BlockState leaves;
	public final @Nullable BlockState log;
	public final float treeCover;
	public final boolean snowy;
	public final boolean frozen;

	OverworldBiome(ResourceKey<Biome> key, Surface surface, @Nullable Block leaves, @Nullable Block log, float treeCover) {
		this(key, surface, leaves, log, treeCover, 0);
	}

	OverworldBiome(ResourceKey<Biome> key, Surface surface, @Nullable Block leaves, @Nullable Block log, float treeCover, int flags) {
		this.key = key;
		this.surface = surface;
		this.leaves = leaves == null ? null : leaves.defaultBlockState();
		this.log = log == null ? null : log.defaultBlockState();
		this.treeCover = treeCover;
		this.snowy = (flags & Flags.SNOWY) != 0;
		this.frozen = (flags & Flags.FROZEN) != 0;
	}

	private static final class Flags {
		static final int SNOWY = 1;
		static final int FROZEN = 2;
	}

	/** Surface painting styles, a tiny stand-in for vanilla's surface rules. */
	public enum Surface {
		GRASS,
		PODZOL,
		GRAVEL,
		DESERT,
		BEACH,
		BADLANDS,
		MUD,
		SNOW,
		ICE_PEAK,
		STONE,
		RIVER_BED,
		OCEAN_SAND,
		OCEAN_GRAVEL
	}
}
