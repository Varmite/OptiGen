package youtube.varmite.world;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One terrain column, described as bottom-up runs of a single block state.
 * <p>
 * Every OptiGen terrain produces columns in this form, and every consumer (chunk filling,
 * structure height queries, Distant Horizons LODs) reads it. Runs are what make the fast
 * paths possible: whole sections collapse to a single palette entry, and LOD columns are a
 * handful of data points instead of hundreds of blocks.
 */
public final class ColumnRuns {
	public static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private BlockState[] states = new BlockState[16];
	private int[] tops = new int[16];
	private int count;
	private int minY;
	private int cursor;

	/** Starts a new column whose first run begins at {@code minY}. */
	public void reset(int minY) {
		this.minY = minY;
		this.cursor = minY;
		this.count = 0;
	}

	/** Appends a run that fills from the current cursor up to (exclusive) {@code topExclusive}. */
	public void add(BlockState state, int topExclusive) {
		if (topExclusive <= cursor) {
			return;
		}
		if (count > 0 && states[count - 1] == state) {
			tops[count - 1] = topExclusive;
		} else {
			if (count == states.length) {
				int size = count * 2;
				states = java.util.Arrays.copyOf(states, size);
				tops = java.util.Arrays.copyOf(tops, size);
			}
			states[count] = state;
			tops[count] = topExclusive;
			count++;
		}
		cursor = topExclusive;
	}

	/** Replaces the topmost block of the column, splitting its run if needed. */
	public void replaceTop(BlockState state) {
		if (count == 0) {
			return;
		}
		int top = tops[count - 1];
		int bottom = count > 1 ? tops[count - 2] : minY;
		if (top - bottom == 1) {
			states[count - 1] = state;
			if (count > 1 && states[count - 2] == state) {
				tops[count - 2] = top;
				count--;
			}
		} else {
			tops[count - 1] = top - 1;
			cursor = top - 1;
			add(state, top);
		}
	}

	public int count() {
		return count;
	}

	public BlockState state(int run) {
		return states[run];
	}

	public int top(int run) {
		return tops[run];
	}

	public int bottom(int run) {
		return run == 0 ? minY : tops[run - 1];
	}

	public int minY() {
		return minY;
	}

	/** Exclusive top of the column (everything above is implicitly air). */
	public int topExclusive() {
		return cursor;
	}

	public BlockState stateAt(int y) {
		if (y < minY) {
			return AIR;
		}
		for (int i = 0; i < count; i++) {
			if (y < tops[i]) {
				return states[i];
			}
		}
		return AIR;
	}

	/** Index of the run containing {@code y}, or -1 when {@code y} is above the column. */
	public int runAt(int y) {
		for (int i = 0; i < count; i++) {
			if (y < tops[i]) {
				return i;
			}
		}
		return -1;
	}
}
