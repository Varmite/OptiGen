package youtube.varmite.world;

import com.google.common.base.Suppliers;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import youtube.varmite.world.parallel.OptiGenThreads;
import youtube.varmite.world.parallel.RegionScheduler;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.jspecify.annotations.Nullable;

/**
 * Shared plumbing for the three OptiGen dimensions.
 * <p>
 * The terrain step builds every column as {@link ColumnRuns} and hands them to {@link ChunkFiller}.
 * Biomes are a 2D lookup filled synchronously (16 samples per chunk). Structures are restricted to
 * the sets listed in the world preset, and decoration goes through a {@link FeatureFilter}.
 */
public abstract class OptiChunkGenerator<T> extends ChunkGenerator {
	private static final ThreadLocal<ColumnRuns[]> COLUMNS = ThreadLocal.withInitial(() -> {
		ColumnRuns[] columns = new ColumnRuns[256];
		for (int i = 0; i < columns.length; i++) {
			columns[i] = new ColumnRuns();
		}
		return columns;
	});

	protected final HolderSet<StructureSet> structureSets;
	private final FeatureFilter featureFilter;
	private final Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep;
	private final RegionScheduler scheduler = new RegionScheduler(OptiGenThreads.EXECUTOR);
	private volatile @Nullable List<List<Structure>> structuresByStep;
	private volatile @Nullable T terrain;

	protected OptiChunkGenerator(BiomeSource biomeSource, HolderSet<StructureSet> structureSets, FeatureFilter featureFilter) {
		super(biomeSource, featureFilter);
		this.structureSets = structureSets;
		this.featureFilter = featureFilter;
		this.featuresPerStep = Suppliers.memoize(
			() -> FeatureSorter.buildFeaturesPerStep(List.copyOf(biomeSource.possibleBiomes()), b -> featureFilter.apply(b).features(), true)
		);
	}

	/** Region-exclusive job scheduler for this level's parallel chunk steps. */
	public RegionScheduler scheduler() {
		return scheduler;
	}

	public HolderSet<StructureSet> structureSets() {
		return structureSets;
	}

	// ------------------------------------------------------------------
	// Seeding: chunk generators are never told the seed directly, so it is captured from the
	// first place it shows up (createState runs when the level is constructed, before any chunk).
	// ------------------------------------------------------------------

	protected abstract T createTerrain(long seed);

	/** Hands the seeded terrain to the biome source, which has no other way of seeing the seed. */
	protected abstract void bindTerrain(T terrain);

	protected final T terrain(long seed) {
		T current = terrain;
		if (current == null) {
			synchronized (this) {
				current = terrain;
				if (current == null) {
					current = createTerrain(seed);
					bindTerrain(current);
					terrain = current;
				}
			}
		}
		return current;
	}

	/** The terrain for the level owning {@code randomState}; also used by the Distant Horizons integration. */
	public final T terrain(RandomState randomState) {
		return terrain(randomState.seed());
	}

	/** The seeded terrain, or null before the level has been created. */
	public final @Nullable T terrainIfReady() {
		return terrain;
	}

	@Override
	public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> lookup, RandomState randomState, long seed) {
		terrain(seed);
		ChunkPos origin = this.getOrigin(randomState);
		if (lookup instanceof HolderLookup.RegistryLookup<StructureSet> registry) {
			Set<StructureSet> allowed = structureSets.stream().map(Holder::value).collect(Collectors.toSet());
			// createForNormal keeps the real seed for stronghold rings (createForFlat would pin them to seed 0).
			return ChunkGeneratorStructureState.createForNormal(randomState, seed, origin, biomeSource, registry.filterElements(allowed::contains));
		}
		return ChunkGeneratorStructureState.createForFlat(randomState, seed, origin, biomeSource, structureSets.stream());
	}

	// ------------------------------------------------------------------
	// Generation steps
	// ------------------------------------------------------------------

	@Override
	public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
		terrain(randomState);
		ChunkPos pos = chunk.getPos();
		chunk.fillBiomesFromNoise(biomeSource.createResolverForChunk(
			null,
			QuartPos.fromBlock(pos.getMinBlockX()),
			QuartPos.fromBlock(chunk.getMinY()),
			QuartPos.fromBlock(pos.getMinBlockZ()),
			4,
			QuartPos.fromBlock(chunk.getHeight()),
			4
		));
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(
		ChunkAccess chunk,
		Blender blender,
		RandomState randomState,
		StructureManager structureManager,
		BiomeManager biomeManager,
		@Nullable WorldGenRegion carverBiomeRegion,
		Set<Holder<Biome>> possibleBiomes
	) {
		T seeded = terrain(randomState);
		// Terrain never touches neighbours, so it needs no region claim: straight onto the pool.
		return CompletableFuture.supplyAsync(() -> {
			ColumnRuns[] columns = COLUMNS.get();
			ChunkPos pos = chunk.getPos();
			buildColumns(seeded, pos.getMinBlockX(), pos.getMinBlockZ(), columns);
			ChunkFiller.fill(chunk, columns);
			return chunk;
		}, OptiGenThreads.EXECUTOR);
	}

	/**
	 * Vanilla's decoration loop with three changes: the per-step structure lists are built once
	 * instead of for every chunk, structure placement is skipped when the chunk references no
	 * structure, and each structure start is placed under its own lock. Pieces shared by several
	 * chunks (temples, shipwrecks) cache a ground height on first placement, and with chunks
	 * decorating in parallel that first placement must not race.
	 */
	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		ChunkPos centerPos = chunk.getPos();
		SectionPos sectionPos = SectionPos.of(centerPos, level.getMinSectionY());
		BlockPos origin = sectionPos.origin();
		List<List<Structure>> structureSteps = structuresByStep(level);
		List<FeatureSorter.StepFeatureData> featureList = featuresPerStep.get();
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
		long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());

		Set<Holder<Biome>> possibleBiomes = new ObjectArraySet<>();
		for (int x = centerPos.x() - 1; x <= centerPos.x() + 1; x++) {
			for (int z = centerPos.z() - 1; z <= centerPos.z() + 1; z++) {
				for (LevelChunkSection section : level.getChunk(x, z).getSections()) {
					section.getBiomes().getAll(possibleBiomes::add);
				}
			}
		}
		possibleBiomes.retainAll(biomeSource.possibleBiomes());

		boolean placeStructures = structureManager.shouldGenerateStructures() && !chunk.getAllReferences().isEmpty();
		BoundingBox writableArea = writableArea(chunk);
		FeaturePlacer placer = new FeaturePlacer(level, this);
		int featureStepCount = featureList.size();
		int generationSteps = Math.max(GenerationStep.Decoration.values().length, featureStepCount);

		try {
			for (int stepIndex = 0; stepIndex < generationSteps; stepIndex++) {
				if (placeStructures && stepIndex < structureSteps.size()) {
					int index = 0;
					for (Structure structure : structureSteps.get(stepIndex)) {
						random.setFeatureSeed(decorationSeed, index, stepIndex);
						structureManager.startsForStructure(sectionPos.x(), sectionPos.z(), structure).forEach(start -> {
							synchronized (start) {
								start.placeInChunk(level, structureManager, this, random, writableArea, centerPos);
							}
						});
						index++;
					}
				}

				if (stepIndex < featureStepCount) {
					IntSet possibleFeaturesThisStep = new IntArraySet();
					FeatureSorter.StepFeatureData stepFeatureData = featureList.get(stepIndex);
					for (Holder<Biome> biome : possibleBiomes) {
						List<HolderSet<PlacedFeature>> featuresInBiome = featureFilter.apply(biome).features();
						if (stepIndex < featuresInBiome.size()) {
							for (Holder<PlacedFeature> feature : featuresInBiome.get(stepIndex)) {
								possibleFeaturesThisStep.add(stepFeatureData.indexMapping().applyAsInt(feature.value()));
							}
						}
					}

					int[] indexArray = possibleFeaturesThisStep.toIntArray();
					Arrays.sort(indexArray);
					for (int globalIndex : indexArray) {
						random.setFeatureSeed(decorationSeed, globalIndex, stepIndex);
						placer.placeWithBiomeCheck(stepFeatureData.features().get(globalIndex), random, origin);
					}
				}
			}
		} catch (Exception e) {
			CrashReport report = CrashReport.forThrowable(e, "Biome decoration");
			report.addCategory("Generation").setDetail("CenterX", centerPos.x()).setDetail("CenterZ", centerPos.z()).setDetail("Decoration Seed", decorationSeed);
			throw new ReportedException(report);
		}
	}

	private List<List<Structure>> structuresByStep(WorldGenLevel level) {
		List<List<Structure>> cached = structuresByStep;
		if (cached == null) {
			Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			List<List<Structure>> steps = new ArrayList<>();
			for (int i = 0; i < GenerationStep.Decoration.values().length; i++) {
				steps.add(new ArrayList<>());
			}
			// Registry order, the same order vanilla's per-chunk groupingBy produces.
			registry.stream().forEach(structure -> steps.get(structure.step().ordinal()).add(structure));
			cached = steps;
			structuresByStep = cached;
		}
		return cached;
	}

	private static BoundingBox writableArea(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		LevelHeightAccessor height = chunk.getHeightAccessorForGeneration();
		return new BoundingBox(pos.getMinBlockX(), height.getMinY() + 1, pos.getMinBlockZ(), pos.getMinBlockX() + 15, height.getMaxY(), pos.getMinBlockZ() + 15);
	}

	/** Fills {@code columns[x + z * 16]} for the chunk starting at the given block coordinates. */
	protected abstract void buildColumns(T terrain, int minBlockX, int minBlockZ, ColumnRuns[] columns);

	/** Builds a single full-detail column; used for structure placement queries. */
	protected abstract void buildColumn(T terrain, int x, int z, ColumnRuns out);

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		ColumnRuns column = new ColumnRuns();
		buildColumn(terrain(randomState), x, z, column);
		var test = type.isOpaque();
		for (int run = column.count() - 1; run >= 0; run--) {
			if (test.test(column.state(run))) {
				return column.top(run);
			}
		}
		return heightAccessor.getMinY();
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		ColumnRuns column = new ColumnRuns();
		buildColumn(terrain(randomState), x, z, column);
		int minY = heightAccessor.getMinY();
		BlockState[] states = new BlockState[heightAccessor.getHeight()];
		for (int i = 0; i < states.length; i++) {
			states[i] = column.stateAt(minY + i);
		}
		return new NoiseColumn(minY, states);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		ChunkPos center = region.getCenter();
		BlockPos sourcePos = center.getWorldPosition().atY(region.getMaxY());
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		random.setDecorationSeed(region.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
		NaturalSpawner.spawnMobsForChunkGeneration(region, sourcePos, center, random);
	}
}
