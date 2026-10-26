package youtube.varmite.dev;

import com.mojang.datafixers.util.Pair;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.TreeMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import youtube.varmite.OptiGen;

/**
 * Dedicated-server benchmark and smoke test, enabled with {@code -Doptigen.benchmark=<radius>}.
 * Works for any world type, so OptiGen and vanilla can be timed the same way. It generates a
 * square of full chunks per dimension, checks the things a speedrun needs, then stops the server.
 */
public final class WorldGenBenchmark {
	private static final int FAR_CHUNK = 1000;

	private WorldGenBenchmark() {
	}

	/** Loads chunks without simulating them, so ticking doesn't pollute the measurement. */
	private static TicketType BENCHMARK_TICKET;

	public static void registerIfRequested() {
		String radius = System.getProperty("optigen.benchmark");
		if (radius == null || FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			return;
		}
		BENCHMARK_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, OptiGen.id("benchmark"), new TicketType(0L, TicketType.FLAG_LOADING));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				// A loading ticket's radius is capped by the chunk level range (33 levels to a full chunk).
				run(server, Math.min(Integer.parseInt(radius), 32));
			} catch (Throwable t) {
				OptiGen.LOGGER.error("[benchmark] FAILED", t);
			} finally {
				server.halt(false);
			}
		});
	}

	private static void run(MinecraftServer server, int radius) {
		for (ServerLevel level : server.getAllLevels()) {
			OptiGen.LOGGER.info("[benchmark] {} uses {}", level.dimension().identifier(), level.getChunkSource().getGenerator().getClass().getSimpleName());
		}

		// Warm up the JIT on a throwaway area so the first measured dimension isn't penalised.
		generate(server.overworld(), -FAR_CHUNK, -FAR_CHUNK, 4);

		for (ResourceKey<Level> dimension : new ResourceKey[]{Level.OVERWORLD, Level.NETHER, Level.END}) {
			ServerLevel level = server.getLevel(dimension);
			int chunks = (radius * 2 + 1) * (radius * 2 + 1);
			CpuSampler cpu = CpuSampler.start();
			long nanos = generate(level, FAR_CHUNK, FAR_CHUNK, radius);
			cpu.stop();
			OptiGen.LOGGER.info("[benchmark] {}: {} full chunks in {} ms -> {} chunks/s ({} ms/chunk), CPU avg {}% peak {}% of {} cores",
				dimension.identifier(), chunks, nanos / 1_000_000, String.format("%.1f", chunks / (nanos / 1e9)), String.format("%.3f", nanos / 1e6 / chunks),
				cpu.averagePercent(), cpu.peakPercent(), Runtime.getRuntime().availableProcessors());
		}

		checkOverworld(server.overworld());
		checkNether(server.getLevel(Level.NETHER));
		checkEnd(server.getLevel(Level.END));

		if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			youtube.varmite.compat.dh.DhLodBenchmark.run(server);
		}
	}

	/**
	 * Requests the whole square at once with one loading ticket, the way a pre-generator or a
	 * moving player does, so the chunk system can actually run many chunks in parallel.
	 */
	private static long generate(ServerLevel level, int centerX, int centerZ, int radius) {
		ChunkPos center = new ChunkPos(centerX, centerZ);
		long start = System.nanoTime();
		CompletableFuture<?> loaded = level.getChunkSource().addTicketAndLoadWithRadius(BENCHMARK_TICKET, center, radius);
		level.getServer().managedBlock(loaded::isDone);
		long nanos = System.nanoTime() - start;
		loaded.join();
		level.getChunkSource().removeTicketWithRadius(BENCHMARK_TICKET, center, radius);
		return nanos;
	}

	/** Process CPU load sampled every 100 ms, as a share of all cores. */
	private static final class CpuSampler {
		private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		private final Thread thread;
		private volatile boolean running = true;
		private double sum;
		private int samples;
		private double peak;

		private CpuSampler() {
			thread = new Thread(() -> {
				os.getProcessCpuLoad();
				while (running) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						return;
					}
					double load = os.getProcessCpuLoad();
					if (load >= 0) {
						sum += load;
						samples++;
						peak = Math.max(peak, load);
					}
				}
			}, "OptiGen CPU sampler");
			thread.setDaemon(true);
			thread.start();
		}

		static CpuSampler start() {
			return new CpuSampler();
		}

		void stop() {
			running = false;
			try {
				thread.join();
			} catch (InterruptedException ignored) {
			}
		}

		String averagePercent() {
			return String.format("%.0f", samples == 0 ? 0 : 100 * sum / samples);
		}

		String peakPercent() {
			return String.format("%.0f", 100 * peak);
		}
	}

	private static void checkOverworld(ServerLevel level) {
		BlockPos spawn = level.getRespawnData().pos();
		level.getChunk(spawn);
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
		BlockState ground = level.getBlockState(new BlockPos(spawn.getX(), surface - 1, spawn.getZ()));
		OptiGen.LOGGER.info("[benchmark] overworld spawn {} stands on {} at y={} in {}", spawn.toShortString(), ground, surface - 1,
			level.getBiome(spawn).unwrapKey().map(k -> k.identifier().toString()).orElse("?"));

		locate(level, BuiltinStructures.STRONGHOLD, spawn, 100);
		locate(level, BuiltinStructures.VILLAGE_PLAINS, spawn, 100);
		locate(level, BuiltinStructures.RUINED_PORTAL_STANDARD, spawn, 100);
		biomeCensus(level, 64);
	}

	private static void checkNether(ServerLevel level) {
		locate(level, BuiltinStructures.FORTRESS, BlockPos.ZERO, 100);
		locate(level, BuiltinStructures.BASTION_REMNANT, BlockPos.ZERO, 100);
		biomeCensus(level, 64);
	}

	private static void checkEnd(ServerLevel level) {
		generate(level, 0, 0, 8);
		int center = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
		OptiGen.LOGGER.info("[benchmark] end: main island surface at 0,0 is y={} (exit portal sits here)", center - 1);
		int spikesOk = 0;
		var spikes = EndSpikeFeature.getSpikesForLevel(level);
		for (EndSpikeFeature.EndSpike spike : spikes) {
			BlockState top = level.getBlockState(new BlockPos(spike.getCenterX(), spike.getHeight() - 1, spike.getCenterZ()));
			if (top.isSolid()) {
				spikesOk++;
			}
		}
		OptiGen.LOGGER.info("[benchmark] end: {}/{} obsidian spikes present", spikesOk, spikes.size());
		locate(level, BuiltinStructures.END_CITY, new BlockPos(1500, 64, 0), 100);
	}

	private static void locate(ServerLevel level, ResourceKey<Structure> key, BlockPos from, int radius) {
		long start = System.nanoTime();
		Holder<Structure> structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
		Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
			.findNearestMapStructure(level, HolderSet.direct(structure), from, radius, false);
		long ms = (System.nanoTime() - start) / 1_000_000;
		if (found == null) {
			OptiGen.LOGGER.warn("[benchmark] {}: no {} found within {} ({} ms)", level.dimension().identifier(), key.identifier(), radius, ms);
		} else {
			OptiGen.LOGGER.info("[benchmark] {}: nearest {} at {} ({} blocks away, {} ms)", level.dimension().identifier(), key.identifier(),
				found.getFirst().toShortString(), (int) Math.sqrt(found.getFirst().distSqr(from.atY(found.getFirst().getY()))), ms);
		}
	}

	/** Biome share over a large grid, to eyeball variety and the ocean/land ratio. */
	private static void biomeCensus(ServerLevel level, int steps) {
		Map<String, Integer> counts = new TreeMap<>();
		int spacing = 160;
		int quartY = QuartPos.fromBlock(64);
		for (int i = -steps; i < steps; i++) {
			for (int j = -steps; j < steps; j++) {
				Holder<Biome> biome = level.getUncachedNoiseBiome(QuartPos.fromBlock(i * spacing), quartY, QuartPos.fromBlock(j * spacing));
				counts.merge(biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("?"), 1, Integer::sum);
			}
		}
		int total = steps * steps * 4;
		StringBuilder line = new StringBuilder();
		counts.entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.forEach(e -> line.append(e.getKey()).append(' ').append(String.format("%.1f%%", 100.0 * e.getValue() / total)).append(", "));
		OptiGen.LOGGER.info("[benchmark] {} biomes over {}x{} blocks: {}", level.dimension().identifier(), steps * 2 * spacing, steps * 2 * spacing, line);
	}
}
