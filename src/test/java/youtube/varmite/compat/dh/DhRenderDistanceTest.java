package youtube.varmite.compat.dh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.enums.EConfigValidity;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import youtube.varmite.config.OptiGenConfig;

/** Runs against the real Distant Horizons 3.3.4 config classes on the test classpath. */
class DhRenderDistanceTest {
	@BeforeAll
	static void bootstrap(@TempDir Path gameDir) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// DH binds this during its own init; its config system only needs the game directory.
		SingletonInjector.INSTANCE.bind(IMinecraftSharedWrapper.class, (IMinecraftSharedWrapper) Proxy.newProxyInstance(
			DhRenderDistanceTest.class.getClassLoader(),
			new Class<?>[]{IMinecraftSharedWrapper.class},
			(proxy, method, args) -> switch (method.getName()) {
				case "getInstallationDirectory" -> gameDir.toFile();
				case "getDelayedSetupComplete" -> true;
				case "isDedicatedServer", "isServerThreadHealthy", "supportsSurfaceGeneration" -> false;
				case "getPlayerCount" -> 0;
				default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
			}));
	}

	@Test
	void raisesDhRenderDistanceCeiling() {
		var renderDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius;
		assertEquals(EConfigValidity.NUMBER_TOO_HIGH, renderDistance.getValidity(8192), "DH should reject 8192 chunks before OptiGen lifts the cap");

		OptiGenConfig.get().dhMaxRenderDistance = 16384;
		DhRenderDistance.apply();

		assertEquals(16384, renderDistance.getMax());
		// This is the check DH's settings screen, /dhconfig and API setValue all run.
		assertEquals(EConfigValidity.VALID, renderDistance.getValidity(8192));
		assertEquals(EConfigValidity.VALID, renderDistance.getValidity(16384));
		assertEquals(EConfigValidity.NUMBER_TOO_HIGH, renderDistance.getValidity(16385));

		// Stock server request limits (4096 = "as far as allowed") follow the new cap without touching DH's file value.
		assertEquals(16384, Config.Server.maxGenerationRequestDistance.get());
		assertEquals(16384, Config.Server.maxSyncOnLoadRequestDistance.get());
		assertEquals(4096, Config.Server.maxGenerationRequestDistance.getTrueValue());
	}
}
