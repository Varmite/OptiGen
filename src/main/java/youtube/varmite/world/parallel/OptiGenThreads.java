package youtube.varmite.world.parallel;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import youtube.varmite.OptiGen;
import youtube.varmite.config.OptiGenConfig;

/**
 * The OptiGen worker pool, shared by every OptiGen dimension: by default one thread per CPU core.
 * <p>
 * Vanilla only runs terrain off-thread (on cores - 1 threads) and does everything else for a level
 * on one consecutive "worldgen" thread. OptiGen runs terrain, decoration, light-source scanning and
 * mob spawning here, so a chunk-hungry server keeps every core busy. The pool is rebuilt from the
 * config whenever a world loads.
 */
public final class OptiGenThreads {
	private static final AtomicInteger COUNTER = new AtomicInteger();
	private static volatile ForkJoinPool pool = create(Runtime.getRuntime().availableProcessors(), true);

	/** Always submits to the current pool, so schedulers survive a pool rebuild. */
	public static final Executor EXECUTOR = task -> pool.execute(task);

	private OptiGenThreads() {
	}

	public static int threads() {
		return pool.getParallelism();
	}

	/** Applies the configured thread count and priority; called when a server starts. */
	public static synchronized void configure(OptiGenConfig config) {
		int threads = config.resolvedWorkerThreads();
		ForkJoinPool previous = pool;
		pool = create(threads, config.lowerWorkerPriority);
		// Lets anything still queued from a previous world finish, then lets the threads exit.
		previous.shutdown();
		OptiGen.LOGGER.info("OptiGen worker pool: {} threads{}", threads, config.lowerWorkerPriority ? " (below-normal priority)" : "");
	}

	private static ForkJoinPool create(int threads, boolean lowPriority) {
		return new ForkJoinPool(
			threads,
			forkJoinPool -> {
				ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(forkJoinPool);
				thread.setName("OptiGen Worker #" + COUNTER.incrementAndGet());
				thread.setPriority(lowPriority ? Thread.NORM_PRIORITY - 1 : Thread.NORM_PRIORITY);
				thread.setDaemon(true);
				return thread;
			},
			(thread, error) -> OptiGen.LOGGER.error("Uncaught exception on {}", thread.getName(), error),
			true
		);
	}
}
