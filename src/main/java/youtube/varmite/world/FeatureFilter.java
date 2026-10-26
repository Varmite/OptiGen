package youtube.varmite.world;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import youtube.varmite.config.OptiGenConfig;

/**
 * Strips biome decoration down to what a beatable, vanilla-looking world needs.
 * <p>
 * Plugged in through {@link net.minecraft.world.level.chunk.ChunkGenerator}'s generation-settings
 * getter, so vanilla's decoration loop runs over fewer features. Always kept: trees, plants, coal,
 * iron, gold, diamonds, lava lakes (obsidian), icebergs, snow/ice. What gets dropped is chosen in
 * {@link OptiGenConfig}.
 */
public final class FeatureFilter implements Function<Holder<Biome>, BiomeGenerationSettings> {
	/** Decoration nobody sees without caves: geodes, filler blobs, magma and lichen. */
	private static final Set<String> OVERWORLD_UNDERGROUND = Set.of(
		"minecraft:amethyst_geode",
		"minecraft:ore_dirt",
		"minecraft:ore_gravel",
		"minecraft:ore_granite_upper",
		"minecraft:ore_granite_lower",
		"minecraft:ore_diorite_upper",
		"minecraft:ore_diorite_lower",
		"minecraft:ore_andesite_upper",
		"minecraft:ore_andesite_lower",
		"minecraft:ore_tuff",
		"minecraft:ore_infested",
		"minecraft:underwater_magma",
		"minecraft:glow_lichen"
	);
	/** Dungeons, fossils, cave decoration and fluid springs (which also cost fluid ticks forever). */
	private static final Set<GenerationStep.Decoration> OVERWORLD_UNDERGROUND_STEPS = Set.of(
		GenerationStep.Decoration.UNDERGROUND_STRUCTURES,
		GenerationStep.Decoration.UNDERGROUND_DECORATION,
		GenerationStep.Decoration.FLUID_SPRINGS
	);
	/** Ores nobody needs to beat the game. Kept: coal, iron, gold, diamond. */
	private static final Set<String> OVERWORLD_EXTRA_ORES = Set.of(
		"minecraft:ore_copper",
		"minecraft:ore_copper_large",
		"minecraft:ore_redstone",
		"minecraft:ore_redstone_lower",
		"minecraft:ore_lapis",
		"minecraft:ore_lapis_buried",
		"minecraft:ore_emerald",
		"minecraft:ore_gold_lower",
		"minecraft:ore_diamond_buried"
	);

	private static final Set<String> NETHER_UNDERGROUND = Set.of(
		"minecraft:spring_open",
		"minecraft:spring_closed",
		"minecraft:spring_closed_double",
		"minecraft:spring_delta",
		"minecraft:spring_lava",
		"minecraft:ore_gravel_nether",
		"minecraft:ore_blackstone",
		"minecraft:ore_soul_sand",
		"minecraft:ore_magma",
		"minecraft:basalt_blobs",
		"minecraft:blackstone_blobs"
	);

	private final Set<String> droppedFeatures;
	private final Set<GenerationStep.Decoration> droppedSteps;
	private final Map<Holder<Biome>, BiomeGenerationSettings> cache = new ConcurrentHashMap<>();

	private FeatureFilter(Set<String> droppedFeatures, Set<GenerationStep.Decoration> droppedSteps) {
		this.droppedFeatures = droppedFeatures;
		this.droppedSteps = droppedSteps;
	}

	/** Built from the config when a world loads; the choice then holds for that world's session. */
	public static FeatureFilter overworld() {
		OptiGenConfig config = OptiGenConfig.get();
		Set<String> dropped = new HashSet<>();
		Set<GenerationStep.Decoration> droppedSteps = new HashSet<>();
		if (config.skipUndergroundDecoration) {
			dropped.addAll(OVERWORLD_UNDERGROUND);
			droppedSteps.addAll(OVERWORLD_UNDERGROUND_STEPS);
		}
		if (config.trimOres) {
			dropped.addAll(OVERWORLD_EXTRA_ORES);
		}
		return new FeatureFilter(dropped, droppedSteps);
	}

	public static FeatureFilter nether() {
		return new FeatureFilter(OptiGenConfig.get().skipUndergroundDecoration ? NETHER_UNDERGROUND : Set.of(), Set.of());
	}

	/** The End is already cheap and its features (spikes, podium platform, gateways) are required. */
	public static FeatureFilter end() {
		return new FeatureFilter(Set.of(), Set.of());
	}

	@Override
	public BiomeGenerationSettings apply(Holder<Biome> biome) {
		return cache.computeIfAbsent(biome, this::filter);
	}

	private BiomeGenerationSettings filter(Holder<Biome> biome) {
		List<HolderSet<PlacedFeature>> steps = biome.value().getGenerationSettings().features();
		BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
		GenerationStep.Decoration[] decorationSteps = GenerationStep.Decoration.values();

		for (int step = 0; step < steps.size(); step++) {
			if (step < decorationSteps.length && droppedSteps.contains(decorationSteps[step])) {
				continue;
			}
			for (Holder<PlacedFeature> feature : steps.get(step)) {
				boolean dropped = feature.unwrapKey()
					.map(key -> droppedFeatures.contains(key.identifier().toString()))
					.orElse(false);
				if (!dropped) {
					builder.addFeature(step, feature);
				}
			}
		}
		// Carvers are never added: OptiGen has no caves.
		return builder.build();
	}
}
