package youtube.varmite.world.parallel;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;

/**
 * Runs chunk jobs in parallel while guaranteeing that two jobs never touch the same chunk at once.
 * <p>
 * Each job claims every chunk within {@code radius} of its centre. A job whose area is free runs
 * immediately on the pool. Otherwise it parks, without holding a thread, on the first claimed chunk
 * that blocked it, and is retried only when that chunk is released. Claims are all-or-nothing under
 * one monitor, so there is no lock ordering and no deadlock. This is the exclusion vanilla gets by
 * running every step on one thread, without the single thread.
 */
public final class RegionScheduler {
	private final Executor executor;
	private final LongOpenHashSet claimed = new LongOpenHashSet();
	private final Long2ObjectOpenHashMap<ArrayDeque<Job<?>>> parked = new Long2ObjectOpenHashMap<>();

	public RegionScheduler(Executor executor) {
		this.executor = executor;
	}

	public <T> CompletableFuture<T> submit(ChunkPos center, int radius, Supplier<T> work) {
		Job<T> job = new Job<>(center.x(), center.z(), radius, work);
		boolean runNow;
		synchronized (this) {
			runNow = claimOrPark(job);
		}
		if (runNow) {
			dispatch(job);
		}
		return job.future;
	}

	private void dispatch(Job<?> job) {
		executor.execute(() -> job.run(this));
	}

	/** Claims the job's area, or parks it on the chunk that is in the way. Caller holds the monitor. */
	private boolean claimOrPark(Job<?> job) {
		for (int x = job.x - job.radius; x <= job.x + job.radius; x++) {
			for (int z = job.z - job.radius; z <= job.z + job.radius; z++) {
				long key = ChunkPos.pack(x, z);
				if (claimed.contains(key)) {
					parked.computeIfAbsent(key, k -> new ArrayDeque<>()).add(job);
					return false;
				}
			}
		}
		for (int x = job.x - job.radius; x <= job.x + job.radius; x++) {
			for (int z = job.z - job.radius; z <= job.z + job.radius; z++) {
				claimed.add(ChunkPos.pack(x, z));
			}
		}
		return true;
	}

	private void finish(Job<?> job) {
		List<Job<?>> ready = null;
		synchronized (this) {
			List<Job<?>> retry = null;
			for (int x = job.x - job.radius; x <= job.x + job.radius; x++) {
				for (int z = job.z - job.radius; z <= job.z + job.radius; z++) {
					long key = ChunkPos.pack(x, z);
					claimed.remove(key);
					ArrayDeque<Job<?>> queue = parked.remove(key);
					if (queue != null) {
						if (retry == null) {
							retry = new ArrayList<>();
						}
						retry.addAll(queue);
					}
				}
			}
			if (retry != null) {
				for (Job<?> candidate : retry) {
					if (claimOrPark(candidate)) {
						if (ready == null) {
							ready = new ArrayList<>();
						}
						ready.add(candidate);
					}
				}
			}
		}
		if (ready != null) {
			ready.forEach(this::dispatch);
		}
	}

	private static final class Job<T> {
		final int x;
		final int z;
		final int radius;
		final Supplier<T> work;
		final CompletableFuture<T> future = new CompletableFuture<>();

		Job(int x, int z, int radius, Supplier<T> work) {
			this.x = x;
			this.z = z;
			this.radius = radius;
			this.work = work;
		}

		void run(RegionScheduler scheduler) {
			T result = null;
			Throwable error = null;
			try {
				result = work.get();
			} catch (Throwable t) {
				error = t;
			}
			// Free the area before completing, so follow-up steps never wait on this job's claim.
			scheduler.finish(this);
			if (error == null) {
				future.complete(result);
			} else {
				future.completeExceptionally(error);
			}
		}
	}
}
