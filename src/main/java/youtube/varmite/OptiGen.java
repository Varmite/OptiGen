package youtube.varmite;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import youtube.varmite.config.OptiGenConfig;
import youtube.varmite.dev.WorldGenBenchmark;
import youtube.varmite.world.end.EndBiomeSource;
import youtube.varmite.world.end.EndChunkGenerator;
import youtube.varmite.world.nether.NetherBiomeSource;
import youtube.varmite.world.nether.NetherChunkGenerator;
import youtube.varmite.world.overworld.OverworldBiomeSource;
import youtube.varmite.world.overworld.OverworldChunkGenerator;
import youtube.varmite.world.parallel.OptiGenThreads;
import youtube.varmite.world.parallel.ParallelSteps;

public class OptiGen implements ModInitializer {
	public static final String MOD_ID = "optigen";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		OptiGenConfig.load();
		// Thread and scheduling settings are fixed per world session, before any chunk is requested.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			OptiGenThreads.configure(OptiGenConfig.get());
			ParallelSteps.configure(OptiGenConfig.get());
		});

		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("overworld"), OverworldChunkGenerator.CODEC);
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("nether"), NetherChunkGenerator.CODEC);
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("end"), EndChunkGenerator.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, id("overworld"), OverworldBiomeSource.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, id("nether"), NetherBiomeSource.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, id("end"), EndBiomeSource.CODEC);

		if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			try {
				youtube.varmite.compat.dh.DhCompat.init();
			} catch (Throwable t) {
				// An incompatible DH version must never take the world generator down with it.
				LOGGER.error("Failed to hook into Distant Horizons; LODs will use DH's own generator", t);
			}
		}

		WorldGenBenchmark.registerIfRequested();
	}

	/** Re-applies settings that can change without reloading a world; called after the config screen saves. */
	public static void applyLiveSettings() {
		if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			youtube.varmite.compat.dh.DhRenderDistance.apply();
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
