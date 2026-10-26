package youtube.varmite.world.noise;

/** Stateless position hashing used for dithering and scatter decisions. */
public final class Hash {
	private Hash() {
	}

	/** SplitMix64 finaliser. */
	public static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	public static long of(long seed, int x, int z) {
		return mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL));
	}

	public static long of(long seed, int x, int y, int z) {
		return mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0x165667B19E3779F9L) ^ (z * 0xC2B2AE3D27D4EB4FL));
	}

	/** Uniform value in [0, 1). */
	public static double unit(long hash) {
		return (hash >>> 11) * 0x1.0p-53;
	}

	public static double clamp(double v, double min, double max) {
		return v < min ? min : (v > max ? max : v);
	}

	public static double smoothstep(double edge0, double edge1, double v) {
		double t = clamp((v - edge0) / (edge1 - edge0), 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}

	public static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}
}
