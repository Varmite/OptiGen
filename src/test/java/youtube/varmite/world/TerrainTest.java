package youtube.varmite.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import youtube.varmite.world.end.EndTerrain;
import youtube.varmite.world.nether.NetherTerrain;
import youtube.varmite.world.overworld.OverworldTerrain;
import youtube.varmite.world.parallel.OptiGenThreads;

class TerrainTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static void assertWellFormed(ColumnRuns runs, int minY, int maxY) {
		assertTrue(runs.count() == 0 || runs.bottom(0) == minY, "column must start at the bottom of the world");
		for (int i = 0; i < runs.count(); i++) {
			assertTrue(runs.top(i) > runs.bottom(i), "empty run");
			assertTrue(i == 0 || runs.bottom(i) == runs.top(i - 1), "gap between runs");
		}
		assertTrue(runs.topExclusive() <= maxY, "column taller than the world: " + runs.topExclusive());
	}

	@Test
	void overworldColumnsAreWellFormed() {
		OverworldTerrain terrain = new OverworldTerrain(12345L);
		OverworldTerrain.Sample sample = new OverworldTerrain.Sample();
		ColumnRuns runs = new ColumnRuns();
		int land = 0;
		int total = 0;
		for (int x = -20_000; x <= 20_000; x += 397) {
			for (int z = -20_000; z <= 20_000; z += 401) {
				terrain.sample(x, z, sample);
				for (boolean lod : new boolean[]{false, true}) {
					terrain.column(x, z, sample, terrain.isSteep(x, z, sample.height), lod, runs);
					assertWellFormed(runs, OverworldTerrain.MIN_Y, OverworldTerrain.MIN_Y + OverworldTerrain.HEIGHT);
				}
				total++;
				if (sample.height >= OverworldTerrain.SEA_LEVEL) {
					land++;
				}
			}
		}
		double landShare = (double) land / total;
		System.out.printf("[test] overworld land share %.0f%%%n", landShare * 100);
		assertTrue(landShare > 0.4 && landShare < 0.9, "land/ocean balance off: " + landShare);
		assertTrue(terrain.height(8, 8) >= OverworldTerrain.SEA_LEVEL || terrain.height(200, 200) >= OverworldTerrain.SEA_LEVEL, "no land near spawn");
	}

	@Test
	void netherAndEndColumnsAreWellFormed() {
		NetherTerrain nether = new NetherTerrain(1L);
		EndTerrain end = new EndTerrain(1L);
		ColumnRuns runs = new ColumnRuns();
		int[] bounds = new int[2];
		for (int x = -5000; x <= 5000; x += 37) {
			for (int z = -5000; z <= 5000; z += 41) {
				nether.column(x, z, nether.biome(x, z), false, bounds, runs);
				assertWellFormed(runs, NetherTerrain.MIN_Y, NetherTerrain.GEN_HEIGHT);
				end.column(x, z, end.valueAt(x, z, null), runs);
				assertWellFormed(runs, EndTerrain.MIN_Y, EndTerrain.GEN_HEIGHT);
			}
		}
		assertTrue(end.valueAt(0, 0, null) > 0, "no central End island");
	}

	@Test
	void endCandidateCacheMatchesFullScan() {
		EndTerrain end = new EndTerrain(99L);
		EndTerrain.IslandCandidates candidates = end.newCandidates().collect(1000, -800, 1040, -760);
		for (int cx = 1000; cx <= 1040; cx++) {
			for (int cz = -800; cz <= -760; cz++) {
				assertEquals(end.cellValue(cx, cz, null), end.cellValue(cx, cz, candidates), 0.0001f, "cell " + cx + "," + cz);
			}
		}
	}

	/** Terrain is embarrassingly parallel: throughput should scale with the worker pool. */
	@Test
	void terrainScalesAcrossAllCores() {
		OverworldTerrain terrain = new OverworldTerrain(777L);
		int chunks = 4096;
		buildChunks(terrain, 0, 256);

		long start = System.nanoTime();
		buildChunks(terrain, 0, chunks);
		double single = chunks / ((System.nanoTime() - start) / 1e9);

		int threads = OptiGenThreads.threads();
		int perThread = chunks * 4 / threads;
		start = System.nanoTime();
		List<CompletableFuture<Void>> jobs = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			int offset = 100_000 + t * perThread;
			jobs.add(CompletableFuture.runAsync(() -> buildChunks(terrain, offset, perThread), OptiGenThreads.EXECUTOR));
		}
		CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).join();
		double parallel = perThread * threads / ((System.nanoTime() - start) / 1e9);

		System.out.printf("[test] overworld terrain: %.0f chunks/s on 1 thread, %.0f chunks/s on %d threads (%.1fx)%n",
			single, parallel, threads, parallel / single);
		assertTrue(parallel > single * Math.min(threads, 4) * 0.5, "terrain did not scale across cores");
	}

	private static void buildChunks(OverworldTerrain terrain, int firstChunk, int count) {
		OverworldTerrain.Sample sample = new OverworldTerrain.Sample();
		ColumnRuns runs = new ColumnRuns();
		for (int c = firstChunk; c < firstChunk + count; c++) {
			int baseX = (c % 512) * 16;
			int baseZ = (c / 512) * 16;
			for (int dz = 0; dz < 16; dz++) {
				for (int dx = 0; dx < 16; dx++) {
					terrain.sample(baseX + dx, baseZ + dz, sample);
					terrain.column(baseX + dx, baseZ + dz, sample, false, false, runs);
				}
			}
		}
	}
}
