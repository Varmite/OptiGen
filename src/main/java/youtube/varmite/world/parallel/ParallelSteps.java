package youtube.varmite.world.parallel;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import youtube.varmite.OptiGen;
import youtube.varmite.config.OptiGenConfig;
import youtube.varmite.world.OptiChunkGenerator;

/**
 * Parallel replacements for the chunk steps vanilla runs on its single per-level worldgen thread.
 * <p>
 * Exclusion rules, derived from vanilla's chunk pyramid:
 * <ul>
 *   <li>Decoration writes into the 3x3 chunks around its centre, so it claims that 3x3 area.</li>
 *   <li>Light-source init scans its own chunk, which a neighbour's decoration may still be writing
 *       into (vanilla has no neighbour requirement there), so it claims its own chunk.</li>
 *   <li>Mob spawning reads neighbouring blocks that a chunk two away may still be decorating, so it
 *       claims 3x3 as well.</li>
 * </ul>
 * Every later step (light propagation, full) already waits for all of a chunk's writers to finish.
 */
public final class ParallelSteps {
	/** C2ME already parallelises these steps its own way; never stack two schedulers. */
	private static final boolean C2ME = FabricLoader.getInstance().isModLoaded("c2me");
	private static volatile boolean active = !C2ME;

	/** Whether OptiGen levels currently route their chunk steps through the worker pool. */
	public static boolean active() {
		return active;
	}

	/**
	 * Fixes the mode for the world being loaded. Switching while chunks are mid-generation could let
	 * a vanilla-scheduled step overlap a pooled one, so this only ever runs before a server starts.
	 */
	public static void configure(OptiGenConfig config) {
		active = config.parallelGeneration && !C2ME;
		if (C2ME) {
			OptiGen.LOGGER.info("C2ME detected: OptiGen leaves chunk step scheduling to C2ME");
		} else if (active) {
			OptiGen.LOGGER.info("OptiGen parallel world generation enabled on {} threads", OptiGenThreads.threads());
		} else {
			OptiGen.LOGGER.info("OptiGen parallel world generation disabled in config");
		}
	}

	private ParallelSteps() {
	}

	public static CompletableFuture<ChunkAccess> features(
		OptiChunkGenerator<?> generator, WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk
	) {
		ServerLevel level = context.level();
		WorldGenRegion region = new WorldGenRegion(level, chunks, step, chunk);
		StructureManager structureManager = level.structureManager().forWorldGenRegion(region);
		return generator.scheduler().submit(chunk.getPos(), 1, () -> {
			generator.applyBiomeDecoration(region, chunk, structureManager);
			Blender.generateBorderTicks(region, chunk);
			return chunk;
		});
	}

	public static CompletableFuture<ChunkAccess> initializeLight(OptiChunkGenerator<?> generator, WorldGenContext context, ChunkAccess chunk) {
		ThreadedLevelLightEngine lightEngine = context.lightEngine();
		boolean lighted = chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT) && chunk.isLightCorrect();
		return generator.scheduler().submit(chunk.getPos(), 0, () -> {
			chunk.initializeLightSources();
			((ProtoChunk) chunk).setLightEngine(lightEngine);
			return chunk;
		}).thenCompose(c -> lightEngine.initializeLight(c, lighted));
	}

	public static CompletableFuture<ChunkAccess> spawn(
		OptiChunkGenerator<?> generator, WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk
	) {
		if (chunk.isUpgrading()) {
			return CompletableFuture.completedFuture(chunk);
		}
		WorldGenRegion region = new WorldGenRegion(context.level(), chunks, step, chunk);
		return generator.scheduler().submit(chunk.getPos(), 1, () -> {
			generator.spawnOriginalMobs(region);
			return chunk;
		});
	}
}
