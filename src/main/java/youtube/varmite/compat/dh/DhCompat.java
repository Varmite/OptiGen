package youtube.varmite.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import youtube.varmite.OptiGen;
import youtube.varmite.config.OptiGenConfig;
import youtube.varmite.world.OptiChunkGenerator;

/**
 * Binds {@link OptiGenLodGenerator} to every server level that uses an OptiGen chunk generator.
 * Only loaded when Distant Horizons is installed.
 */
public final class DhCompat {
	private static final Map<ResourceKey<Level>, OptiGenLodGenerator> ACTIVE = new ConcurrentHashMap<>();
	private static final AtomicBoolean CORES_APPLIED = new AtomicBoolean();

	private DhCompat() {
	}

	/** Generators bound to currently loaded levels; used by the dev benchmark. */
	public static Map<ResourceKey<Level>, OptiGenLodGenerator> activeGenerators() {
		return ACTIVE;
	}

	/**
	 * OptiGen LODs are pure CPU work with no chunk loading behind them, so DH's usual reasons for
	 * holding threads back don't apply: give DH every core, running 100% of the time. These are
	 * API overrides, so DH's own config file is left untouched.
	 */
	private static void useAllCores() {
		if (CORES_APPLIED.getAndSet(true)) {
			return;
		}
		var threading = DhApi.Delayed.configs.multiThreading();
		int cores = Runtime.getRuntime().availableProcessors();
		boolean threads = threading.threadCount().setValue(cores, OptiGen.MOD_ID);
		boolean ratio = threading.threadRuntimeRatio().setValue(1.0, OptiGen.MOD_ID);
		OptiGen.LOGGER.info("Distant Horizons world generation set to {} threads at 100% run time (accepted: threads={}, ratio={})", cores, threads, ratio);
	}

	public static void init() {
		// DH's config entries are only set up during DH's own init, which runs after this one.
		DhApi.events.bind(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
			@Override
			public void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
				DhRenderDistance.apply();
			}
		});
		DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
			@Override
			public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
				bind(event.value.levelWrapper);
			}
		});
		OptiGen.LOGGER.info("Distant Horizons {} detected (API {}.{}): OptiGen will generate LODs directly from its terrain functions",
			DhApi.getModVersion(), DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion());
	}

	private static void bind(IDhApiLevelWrapper levelWrapper) {
		// Client-side levels (multiplayer) receive LODs from the server; only server levels generate.
		if (!(levelWrapper.getWrappedMcObject() instanceof ServerLevel level)) {
			return;
		}
		if (!(level.getChunkSource().getGenerator() instanceof OptiChunkGenerator<?> generator)) {
			return;
		}
		OptiGenConfig config = OptiGenConfig.get();
		if (config.dhUseAllCores) {
			useAllCores();
		}
		if (!config.dhLodGenerator) {
			OptiGen.LOGGER.info("OptiGen LOD generator disabled in config; DH uses its own generator for {}", level.dimension().identifier());
			return;
		}

		LodColumnSource source = LodColumnSource.forGenerator(generator, level.getChunkSource().randomState());
		OptiGenLodGenerator lodGenerator = new OptiGenLodGenerator(levelWrapper, source);
		DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, lodGenerator);
		if (result.success) {
			ACTIVE.put(level.dimension(), lodGenerator);
			OptiGen.LOGGER.info("OptiGen LOD generator active for {}", level.dimension().identifier());
		} else {
			OptiGen.LOGGER.warn("Distant Horizons rejected the OptiGen LOD generator for {}: {}", level.dimension().identifier(), result.message);
		}
	}
}
