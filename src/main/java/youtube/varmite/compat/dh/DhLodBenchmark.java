package youtube.varmite.compat.dh;

import com.google.common.util.concurrent.MoreExecutors;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import youtube.varmite.OptiGen;

/**
 * Drives {@link OptiGenLodGenerator} through Distant Horizons' real data source with DH's own API
 * validation switched on, then times it. Only used by the dev benchmark.
 */
public final class DhLodBenchmark {
	private DhLodBenchmark() {
	}

	public static void run(MinecraftServer server) {
		Map<ResourceKey<Level>, OptiGenLodGenerator> generators = DhCompat.activeGenerators();
		if (generators.isEmpty()) {
			OptiGen.LOGGER.warn("[benchmark] DH is loaded but no OptiGen LOD generator was bound (is this an OptiGen world?)");
			return;
		}
		ExecutorService direct = MoreExecutors.newDirectExecutorService();

		for (Map.Entry<ResourceKey<Level>, OptiGenLodGenerator> entry : generators.entrySet()) {
			OptiGenLodGenerator generator = entry.getValue();
			for (byte detail : new byte[]{0, 2, 4, 6, 8, 10, 12}) {
				int sections = 64;
				long start = System.nanoTime();
				int columns = 0;
				for (int i = 0; i < sections; i++) {
					// Sections spread around a far-away origin, at this detail level's section size.
					int sectionX = 40 + i % 8;
					int sectionZ = -40 + i / 8;
					long pos = DhSectionPos.encode((byte) (detail + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL), sectionX, sectionZ);
					try (FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos)) {
						dataSource.setRunApiSetterValidation(true);
						int chunkX = DhSectionPos.getMinCornerBlockX(pos) >> 4;
						int chunkZ = DhSectionPos.getMinCornerBlockZ(pos) >> 4;
						generator.generateLod(chunkX, chunkZ, sectionX, sectionZ, detail, dataSource,
							EDhApiDistantGeneratorMode.SURFACE, direct, ds -> { }).join();
						columns += dataSource.getWidthInDataColumns() * dataSource.getWidthInDataColumns();
					}
				}
				long nanos = System.nanoTime() - start;
				int blocksWide = 64 << detail;
				OptiGen.LOGGER.info("[benchmark] DH LOD {} detail {}: {} sections ({}x{} blocks each, {} columns) in {} ms -> {} ms/section, validated by DH",
					entry.getKey().identifier(), detail, sections, blocksWide, blocksWide, columns, nanos / 1_000_000, String.format("%.2f", nanos / 1e6 / sections));
			}
		}

		checkMatchesChunks(server.overworld(), generators.get(Level.OVERWORLD), direct);
	}

	/** LOD terrain must line up with the real chunks that later replace it. */
	private static void checkMatchesChunks(ServerLevel level, OptiGenLodGenerator generator, ExecutorService direct) {
		if (generator == null) {
			return;
		}
		int sectionX = 1000 * 16 / 64;
		int sectionZ = 1000 * 16 / 64;
		long pos = DhSectionPos.encode(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, sectionX, sectionZ);
		int minX = DhSectionPos.getMinCornerBlockX(pos);
		int minZ = DhSectionPos.getMinCornerBlockZ(pos);
		int checked = 0;
		int mismatched = 0;
		try (FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos)) {
			generator.generateLod(minX >> 4, minZ >> 4, sectionX, sectionZ, (byte) 0, dataSource, EDhApiDistantGeneratorMode.SURFACE, direct, ds -> { }).join();
			for (int x = 0; x < 64; x += 3) {
				for (int z = 0; z < 64; z += 3) {
					int lodFloor = oceanFloor(dataSource.getApiDataPointColumn(x, z)) + level.getMinY();
					int chunkFloor = level.getChunkSource().getGenerator().getBaseHeight(minX + x, minZ + z, Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState());
					checked++;
					if (lodFloor != chunkFloor) {
						mismatched++;
					}
				}
			}
		}
		OptiGen.LOGGER.info("[benchmark] DH LOD vs chunk generator terrain height: {}/{} columns match", checked - mismatched, checked);
	}

	/** Top of the highest terrain data point, ignoring water, sea ice and the fake tree canopy (relative Y). */
	private static int oceanFloor(List<DhApiTerrainDataPoint> column) {
		for (DhApiTerrainDataPoint point : column) {
			if (point.blockStateWrapper.isAir() || point.blockStateWrapper.isLiquid()) {
				continue;
			}
			BlockState state = (BlockState) point.blockStateWrapper.getWrappedMcObject();
			if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(Blocks.ICE)) {
				continue;
			}
			return point.topYBlockPos;
		}
		return 0;
	}
}
