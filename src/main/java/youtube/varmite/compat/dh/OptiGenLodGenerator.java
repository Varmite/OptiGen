package youtube.varmite.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import youtube.varmite.world.ColumnRuns;

/**
 * Distant Horizons 3.3 world generator for OptiGen dimensions.
 * <p>
 * DH 3.3's new rough surface generator estimates terrain by ray-marching vanilla's density
 * functions, which OptiGen doesn't have. Instead, this generator returns finished
 * {@link EDhApiWorldGeneratorReturnType#API_DATA_SOURCES API data sources}: every LOD column, at
 * every detail level, is computed directly from the same 2D terrain functions the chunk generator
 * uses. No chunk is generated, loaded or lit for distant terrain, so the terrain matches exactly
 * when real chunks replace it.
 */
public final class OptiGenLodGenerator implements IDhApiWorldGenerator {
	/** DH's largest LOD detail level: one column standing in for 4096x4096 blocks. */
	private static final byte MAX_DETAIL_LEVEL = 12;

	private final IDhApiLevelWrapper level;
	private final LodColumnSource source;
	private final int levelMinY;
	private final int levelHeight;
	private final int skyLight;
	private final Map<BlockState, IDhApiBlockStateWrapper> blockWrappers = new ConcurrentHashMap<>();
	private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();
	private final ThreadLocal<ColumnRuns> runs = ThreadLocal.withInitial(ColumnRuns::new);
	private final ThreadLocal<ArrayList<DhApiTerrainDataPoint>> points = ThreadLocal.withInitial(ArrayList::new);

	public OptiGenLodGenerator(IDhApiLevelWrapper level, LodColumnSource source) {
		this.level = level;
		this.source = source;
		this.levelMinY = level.getMinHeight();
		this.levelHeight = level.getMaxHeight() - level.getMinHeight();
		this.skyLight = level.hasSkyLight() ? 15 : 0;
	}

	@Override
	public byte getSmallestDataDetailLevel() {
		return 0;
	}

	@Override
	public byte getLargestDataDetailLevel() {
		// Any detail level is as cheap as any other, so DH never has to downsample from chunks.
		return MAX_DETAIL_LEVEL;
	}

	@Override
	public EDhApiWorldGeneratorReturnType getReturnType() {
		return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
	}

	@Override
	public boolean runApiValidation() {
		return false;
	}

	@Override
	public CompletableFuture<Void> generateLod(
		int chunkPosMinX, int chunkPosMinZ,
		int lodPosX, int lodPosZ, byte detailLevel,
		IDhApiFullDataSource pooledFullDataSource,
		EDhApiDistantGeneratorMode generatorMode,
		ExecutorService worldGeneratorThreadPool,
		Consumer<IDhApiFullDataSource> resultConsumer
	) {
		return CompletableFuture.runAsync(() -> {
			fill(chunkPosMinX << 4, chunkPosMinZ << 4, detailLevel, pooledFullDataSource);
			resultConsumer.accept(pooledFullDataSource);
		}, worldGeneratorThreadPool);
	}

	private void fill(int minBlockX, int minBlockZ, byte detailLevel, IDhApiFullDataSource dataSource) {
		int width = dataSource.getWidthInDataColumns();
		int step = 1 << detailLevel;
		// Sample the middle of the area each column represents rather than its corner.
		int offset = step >> 1;
		ColumnRuns column = runs.get();
		ArrayList<DhApiTerrainDataPoint> columnPoints = points.get();
		LodColumnSource.Area area = source.begin(minBlockX, minBlockZ, step, width);

		for (int z = 0; z < width; z++) {
			for (int x = 0; x < width; x++) {
				Holder<Biome> biome = area.column(minBlockX + x * step + offset, minBlockZ + z * step + offset, column);
				toDataPoints(column, biomeWrapper(biome), columnPoints);
				// FEATURES satisfies every DH generator plan, so the column is never queued again.
				dataSource.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.FEATURES, columnPoints);
			}
		}
	}

	/** Converts runs to DH data points, top-down, with rough sky light (DH relights LODs itself). */
	private void toDataPoints(ColumnRuns column, IDhApiBiomeWrapper biome, ArrayList<DhApiTerrainDataPoint> out) {
		out.clear();
		int top = levelHeight;
		int columnTop = column.topExclusive() - levelMinY;
		if (columnTop < top) {
			out.add(DhApiTerrainDataPoint.create((byte) 0, 0, skyLight, Math.max(columnTop, 0), top, airWrapper(), biome));
			top = Math.max(columnTop, 0);
		}

		int sky = skyLight;
		for (int run = column.count() - 1; run >= 0 && top > 0; run--) {
			int bottom = Math.max(column.bottom(run) - levelMinY, 0);
			if (bottom >= top) {
				continue;
			}
			BlockState state = column.state(run);
			out.add(DhApiTerrainDataPoint.create((byte) 0, state.getLightEmission(), sky, bottom, top, blockWrapper(state), biome));
			if (!state.isAir()) {
				if (!state.getFluidState().isEmpty()) {
					sky = Math.max(0, sky - (top - bottom));
				} else if (state.canOcclude()) {
					sky = 0;
				}
			}
			top = bottom;
		}
	}

	private IDhApiBlockStateWrapper airWrapper() {
		return blockWrapper(ColumnRuns.AIR);
	}

	private IDhApiBlockStateWrapper blockWrapper(BlockState state) {
		return blockWrappers.computeIfAbsent(state, s -> s.isAir()
			? DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper()
			: DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{s}, level));
	}

	private IDhApiBiomeWrapper biomeWrapper(Holder<Biome> biome) {
		return biomeWrappers.computeIfAbsent(biome, b -> DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[]{b}, level));
	}

	@Override
	public void preGeneratorTaskStart() {
	}

	@Override
	public void close() {
		blockWrappers.clear();
		biomeWrappers.clear();
	}
}
