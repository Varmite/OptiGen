package youtube.varmite.world.parallel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.junit.jupiter.api.Test;

/** Loading the target class applies the mixin; a wrong injection target would throw here instead of in game. */
class MixinApplyTest {
	@Test
	void chunkStatusTasksMixinApplies() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		Method[] methods = ChunkStatusTasks.class.getDeclaredMethods();
		long injected = Arrays.stream(methods).filter(m -> m.getName().contains("optigen$")).count();
		System.out.println("[test] ChunkStatusTasks carries " + injected + " OptiGen injections");
		assertTrue(injected >= 3, "OptiGen chunk step injections were not applied");
	}
}
