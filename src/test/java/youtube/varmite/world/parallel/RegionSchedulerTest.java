package youtube.varmite.world.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class RegionSchedulerTest {
	@Test
	void jobsNeverShareChunksAndAllFinish() throws Exception {
		int threads = Runtime.getRuntime().availableProcessors();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		RegionScheduler scheduler = new RegionScheduler(pool);
		ConcurrentHashMap<Long, AtomicInteger> owners = new ConcurrentHashMap<>();
		AtomicInteger violations = new AtomicInteger();
		AtomicInteger running = new AtomicInteger();
		AtomicInteger peakRunning = new AtomicInteger();

		// A small area forces heavy contention; submissions come from many threads at once.
		List<CompletableFuture<Integer>> futures = IntStream.range(0, 40_000).parallel().mapToObj(i -> {
			Random random = new Random(i);
			int cx = random.nextInt(24);
			int cz = random.nextInt(24);
			int radius = random.nextInt(2);
			return scheduler.submit(new ChunkPos(cx, cz), radius, () -> {
				peakRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
				for (int x = cx - radius; x <= cx + radius; x++) {
					for (int z = cz - radius; z <= cz + radius; z++) {
						if (owners.computeIfAbsent(ChunkPos.pack(x, z), k -> new AtomicInteger()).incrementAndGet() != 1) {
							violations.incrementAndGet();
						}
					}
				}
				long until = System.nanoTime() + 20_000;
				while (System.nanoTime() < until) {
					Thread.onSpinWait();
				}
				for (int x = cx - radius; x <= cx + radius; x++) {
					for (int z = cz - radius; z <= cz + radius; z++) {
						owners.get(ChunkPos.pack(x, z)).decrementAndGet();
					}
				}
				running.decrementAndGet();
				return 1;
			});
		}).toList();

		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
		pool.shutdown();

		assertEquals(0, violations.get(), "two jobs held the same chunk at the same time");
		assertEquals(40_000, futures.stream().mapToInt(CompletableFuture::join).sum());
		System.out.println("[test] RegionScheduler: 40000 jobs, peak " + peakRunning.get() + " running at once on " + threads + " threads");
		assertTrue(peakRunning.get() > 1, "jobs never ran in parallel");
	}

	@Test
	void failingJobReleasesItsArea() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		RegionScheduler scheduler = new RegionScheduler(pool);
		CompletableFuture<Object> failed = scheduler.submit(new ChunkPos(0, 0), 1, () -> {
			throw new IllegalStateException("boom");
		});
		CompletableFuture<String> next = scheduler.submit(new ChunkPos(1, 1), 1, () -> "ran");
		assertTrue(failed.handle((v, t) -> t != null).get(5, TimeUnit.SECONDS));
		assertEquals("ran", next.get(5, TimeUnit.SECONDS));
		pool.shutdown();
	}
}
