package youtube.varmite.world.noise;

/**
 * Seeded 2D simplex noise. Everything OptiGen generates is a function of a handful of these,
 * which is the entire trick: vanilla evaluates a deep 3D density-function graph per block,
 * we evaluate a few 2D lookups per column.
 */
public final class Noise2D {
	private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
	private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
	// Normalises unit-gradient simplex output to roughly [-1, 1].
	private static final double SCALE = 99.204334582718;

	private final short[] perm = new short[512];
	private final double[] gradX = new double[512];
	private final double[] gradY = new double[512];

	public Noise2D(long seed) {
		long state = seed;
		short[] base = new short[256];
		for (short i = 0; i < 256; i++) {
			base[i] = i;
		}
		for (int i = 255; i > 0; i--) {
			state = Hash.mix(state + 0x9E3779B97F4A7C15L);
			int j = (int) Long.remainderUnsigned(state, i + 1);
			short t = base[i];
			base[i] = base[j];
			base[j] = t;
		}
		for (int i = 0; i < 512; i++) {
			perm[i] = base[i & 255];
			state = Hash.mix(state + 0x9E3779B97F4A7C15L);
			double angle = (state >>> 11) * 0x1.0p-53 * Math.PI * 2.0;
			gradX[i] = Math.cos(angle);
			gradY[i] = Math.sin(angle);
		}
	}

	public double sample(double x, double y) {
		double s = (x + y) * F2;
		int i = fastFloor(x + s);
		int j = fastFloor(y + s);
		double t = (i + j) * G2;
		double x0 = x - (i - t);
		double y0 = y - (j - t);

		int i1;
		int j1;
		if (x0 > y0) {
			i1 = 1;
			j1 = 0;
		} else {
			i1 = 0;
			j1 = 1;
		}

		double x1 = x0 - i1 + G2;
		double y1 = y0 - j1 + G2;
		double x2 = x0 - 1.0 + 2.0 * G2;
		double y2 = y0 - 1.0 + 2.0 * G2;

		int ii = i & 255;
		int jj = j & 255;

		double n = 0.0;
		double t0 = 0.5 - x0 * x0 - y0 * y0;
		if (t0 > 0.0) {
			int g = perm[ii + perm[jj]];
			t0 *= t0;
			n += t0 * t0 * (gradX[g] * x0 + gradY[g] * y0);
		}
		double t1 = 0.5 - x1 * x1 - y1 * y1;
		if (t1 > 0.0) {
			int g = perm[ii + i1 + perm[jj + j1]];
			t1 *= t1;
			n += t1 * t1 * (gradX[g] * x1 + gradY[g] * y1);
		}
		double t2 = 0.5 - x2 * x2 - y2 * y2;
		if (t2 > 0.0) {
			int g = perm[ii + 1 + perm[jj + 1]];
			t2 *= t2;
			n += t2 * t2 * (gradX[g] * x2 + gradY[g] * y2);
		}
		return n * SCALE;
	}

	/** Fractal sum of octaves, normalised back to roughly [-1, 1]. */
	public double fbm(double x, double y, int octaves) {
		double sum = 0.0;
		double amp = 1.0;
		double norm = 0.0;
		for (int o = 0; o < octaves; o++) {
			// Offset each octave so they don't share a lattice origin.
			sum += sample(x + o * 19.19, y - o * 31.73) * amp;
			norm += amp;
			amp *= 0.5;
			x *= 2.0;
			y *= 2.0;
		}
		return sum / norm;
	}

	private static int fastFloor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}
}
