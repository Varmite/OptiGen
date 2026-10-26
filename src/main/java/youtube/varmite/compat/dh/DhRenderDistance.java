package youtube.varmite.compat.dh;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import youtube.varmite.OptiGen;
import youtube.varmite.config.OptiGenConfig;

/**
 * Lifts Distant Horizons' 4096-chunk LOD render distance ceiling.
 * <p>
 * The ceiling is only the maximum of DH's config entry: DH's settings screen, its
 * {@code /dhconfig} command and its API all validate against that maximum, while the quadtree,
 * clip planes and fog all scale from the chosen value. Past ~65k blocks DH simply renders more
 * of its coarsest sections (256 blocks per LOD column), and OptiGen's LOD generator can produce
 * those at any distance. So raising the maximum is enough; the value itself stays the player's
 * choice in DH's own settings.
 * <p>
 * Multiplayer servers also reject LOD requests beyond {@code maxGenerationRequestDistance} and
 * {@code maxSyncOnLoadRequestDistance}, which share the same 4096 ceiling. Where those are still
 * at DH's stock value (meaning "as far as allowed"), they are raised with an API override, which
 * DH never writes to its config file. A smaller value an admin chose on purpose is left alone.
 * <p>
 * This reaches into DH internals rather than its public API (which has no way to change a
 * maximum), so it is isolated here and fails soft if a future DH version moves things.
 */
public final class DhRenderDistance {
	private DhRenderDistance() {
	}

	public static void apply() {
		int cap = Math.clamp(OptiGenConfig.get().dhMaxRenderDistance, OptiGenConfig.DH_STOCK_MAX_RENDER_DISTANCE, OptiGenConfig.DH_RENDER_DISTANCE_LIMIT);
		try {
			ConfigEntry<Integer> renderDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius;
			renderDistance.setMax(cap);
			if (renderDistance.getTrueValue() > cap) {
				// The cap was lowered below the current distance: follow it down rather than leave an invalid value.
				renderDistance.set(cap);
			}

			raiseServerLimit(Config.Server.maxGenerationRequestDistance, cap);
			raiseServerLimit(Config.Server.maxSyncOnLoadRequestDistance, cap);
			Config.Server.realTimeUpdateDistanceRadiusInChunks.setMax(cap);

			OptiGen.LOGGER.info("Distant Horizons render distance can now go up to {} chunks ({} blocks)", cap, cap * 16);
		} catch (Throwable t) {
			OptiGen.LOGGER.warn("Could not lift Distant Horizons' render distance limit (DH internals changed?); it stays at {} chunks",
				OptiGenConfig.DH_STOCK_MAX_RENDER_DISTANCE, t);
		}
	}

	private static void raiseServerLimit(ConfigEntry<Integer> entry, int cap) {
		entry.setMax(cap);
		if (entry.getTrueValue() >= OptiGenConfig.DH_STOCK_MAX_RENDER_DISTANCE) {
			entry.setApiValue(cap, OptiGen.MOD_ID);
		}
	}
}
