package youtube.varmite.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Writes 256 {@link ColumnRuns} into a proto chunk as cheaply as the chunk format allows.
 * <p>
 * A section that a single block state covers completely (solid stone, solid deepslate, open
 * air) is replaced with a single-value palette: no per-block writes, no bit-packed storage,
 * and a smaller save file. Only the few sections that actually contain a surface are written
 * block by block, starting from whichever state dominates them so the fewest writes happen.
 */
public final class ChunkFiller {
	private static final Strategy<BlockState> BLOCK_STRATEGY = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
	private static final int MAX_CANDIDATES = 8;

	private ChunkFiller() {
	}

	/** {@code columns} is indexed {@code x + z * 16}. */
	public static void fill(ChunkAccess chunk, ColumnRuns[] columns) {
		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinY();

		int maxTop = Integer.MIN_VALUE;
		for (ColumnRuns column : columns) {
			maxTop = Math.max(maxTop, column.topExclusive());
		}

		BlockState[] candidates = new BlockState[MAX_CANDIDATES];
		int[] volumes = new int[MAX_CANDIDATES];

		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			int y0 = minY + (sectionIndex << 4);
			if (y0 >= maxTop) {
				break;
			}

			BlockState uniform = uniformState(columns, y0);
			if (uniform != null) {
				if (!uniform.isAir()) {
					sections[sectionIndex] = new LevelChunkSection(new PalettedContainer<>(uniform, BLOCK_STRATEGY), sections[sectionIndex].getBiomes());
				}
				continue;
			}

			BlockState base = dominantState(columns, y0, candidates, volumes);
			LevelChunkSection section = sections[sectionIndex];
			if (!base.isAir()) {
				section = new LevelChunkSection(new PalettedContainer<>(base, BLOCK_STRATEGY), section.getBiomes());
				sections[sectionIndex] = section;
			}
			writeExceptions(section, columns, y0, base);
		}

		updateWorldgenHeightmaps(chunk, columns);
	}

	/** The single state covering the whole section in every column, or null. */
	private static BlockState uniformState(ColumnRuns[] columns, int y0) {
		BlockState shared = null;
		int y1 = y0 + 16;
		for (ColumnRuns column : columns) {
			BlockState state;
			int run = column.runAt(y0);
			if (run < 0) {
				state = ColumnRuns.AIR;
			} else if (column.top(run) >= y1) {
				state = column.state(run);
			} else {
				return null;
			}
			if (shared == null) {
				shared = state;
			} else if (shared != state) {
				return null;
			}
		}
		return shared;
	}

	private static BlockState dominantState(ColumnRuns[] columns, int y0, BlockState[] candidates, int[] volumes) {
		int candidateCount = 1;
		candidates[0] = ColumnRuns.AIR;
		volumes[0] = 0;
		int y1 = y0 + 16;

		for (ColumnRuns column : columns) {
			int covered = 0;
			for (int run = Math.max(0, column.runAt(y0)); run < column.count() && column.bottom(run) < y1; run++) {
				int overlap = Math.min(column.top(run), y1) - Math.max(column.bottom(run), y0);
				if (overlap <= 0) {
					continue;
				}
				covered += overlap;
				BlockState state = column.state(run);
				int slot = 0;
				while (slot < candidateCount && candidates[slot] != state) {
					slot++;
				}
				if (slot == candidateCount) {
					if (candidateCount == MAX_CANDIDATES) {
						continue;
					}
					candidates[slot] = state;
					volumes[slot] = 0;
					candidateCount++;
				}
				volumes[slot] += overlap;
			}
			volumes[0] += 16 - covered;
		}

		int best = 0;
		for (int i = 1; i < candidateCount; i++) {
			if (volumes[i] > volumes[best]) {
				best = i;
			}
		}
		return candidates[best];
	}

	private static void writeExceptions(LevelChunkSection section, ColumnRuns[] columns, int y0, BlockState base) {
		int y1 = y0 + 16;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				ColumnRuns column = columns[x + (z << 4)];
				int y = y0;
				int run = column.runAt(y0);
				if (run >= 0) {
					for (; run < column.count() && y < y1; run++) {
						int top = Math.min(column.top(run), y1);
						BlockState state = column.state(run);
						if (state != base) {
							for (; y < top; y++) {
								section.setBlockState(x, y - y0, z, state, false);
							}
						}
						y = top;
					}
				}
				if (base != ColumnRuns.AIR) {
					for (; y < y1; y++) {
						section.setBlockState(x, y - y0, z, ColumnRuns.AIR, false);
					}
				}
			}
		}
	}

	private static void updateWorldgenHeightmaps(ChunkAccess chunk, ColumnRuns[] columns) {
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		var oceanFloorTest = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
		var worldSurfaceTest = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();

		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				ColumnRuns column = columns[x + (z << 4)];
				boolean surfaceDone = false;
				for (int run = column.count() - 1; run >= 0; run--) {
					BlockState state = column.state(run);
					int y = column.top(run) - 1;
					if (!surfaceDone && worldSurfaceTest.test(state)) {
						worldSurface.update(x, y, z, state);
						surfaceDone = true;
					}
					if (oceanFloorTest.test(state)) {
						oceanFloor.update(x, y, z, state);
						break;
					}
				}
			}
		}
	}
}
