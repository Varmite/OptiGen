package youtube.varmite.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import youtube.varmite.OptiGen;

/**
 * OptiGen settings, stored as {@code config/optigen.json}.
 * <p>
 * Plain Gson rather than a config library, so dedicated servers and players without YACL still
 * have a working config; the YACL screen in the client source set only edits this object.
 */
public final class OptiGenConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(OptiGen.MOD_ID + ".json");
	private static volatile OptiGenConfig instance = new OptiGenConfig();

	// Performance (applied when a world is loaded)
	/** Run decoration, light-source scans and spawning on every core instead of one thread per dimension. */
	public boolean parallelGeneration = true;
	/** Worker threads; 0 means one per CPU core. */
	public int workerThreads = 0;
	/** Run workers one priority step below normal so the server thread keeps ticking smoothly. */
	public boolean lowerWorkerPriority = true;

	// World generation (applied to chunks generated after the world is loaded)
	/** Skip copper, redstone, lapis and emerald ore; coal, iron, gold and diamonds stay. */
	public boolean trimOres = true;
	/** Skip dungeons, fossils, geodes, springs, glow lichen and granite/diorite/andesite/tuff blobs. */
	public boolean skipUndergroundDecoration = true;

	// Distant Horizons
	/** Generate LODs straight from OptiGen's terrain functions instead of DH's own generator. */
	public boolean dhLodGenerator = true;
	/** Give DH's world generator every core at 100% run time (DH's config file is not modified). */
	public boolean dhUseAllCores = true;
	/** Paint a cheap tree canopy onto forest LODs, since LODs never run features. */
	public boolean lodTreeCanopy = true;
	/** Highest LOD render distance (in chunks) DH lets you pick; DH's own ceiling is 4096. */
	public int dhMaxRenderDistance = 16384;

	public static final int DH_STOCK_MAX_RENDER_DISTANCE = 4096;
	public static final int DH_RENDER_DISTANCE_LIMIT = 65536;

	public static OptiGenConfig get() {
		return instance;
	}

	/** Worker thread count after resolving "auto". */
	public int resolvedWorkerThreads() {
		int cores = Runtime.getRuntime().availableProcessors();
		return workerThreads <= 0 ? cores : Math.min(workerThreads, cores);
	}

	public static void load() {
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE)) {
				OptiGenConfig loaded = GSON.fromJson(reader, OptiGenConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | JsonParseException e) {
				OptiGen.LOGGER.error("Could not read {}, using defaults", FILE, e);
			}
		}
		// Writes back any options added since the file was created.
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			OptiGen.LOGGER.error("Could not write {}", FILE, e);
		}
	}
}
