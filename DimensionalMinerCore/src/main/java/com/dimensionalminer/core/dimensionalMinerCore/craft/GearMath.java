package com.dimensionalminer.core.dimensionalMinerCore.craft;

/**
 * Gear stat formula from the GDD, in fixed point (milli-units, basis points) so results are
 * exact and identical on every run:
 * <pre>
 *   Core  = (Base × Quality + Advancement × Growth) × (1 + RefineBonus[Refine])
 *   Final = clamp((Core + Flat) × (1 + Percent), Min, Cap)
 * </pre>
 * Refine only scales Base/Advancement; flat and percent bonuses (engrave, socket, set) are summed
 * per group before they apply. Golden test: Base 100, Growth 2, Adv 50, Refine 20%, Flat 15,
 * Percent 10% → 280.5 (see {@link #selfTest()}).
 */
public final class GearMath {

    public static final int MAX_ADVANCEMENT = 100;
    public static final int MAX_REFINE = 10;
    /** 1.0 in basis points. */
    public static final int BP = 10_000;

    private GearMath() {}

    /** Converts a stat value to milli-units (3 decimals). */
    public static long milli(double v) {
        return Math.round(v * 1000.0);
    }

    public static double fromMilli(long m) {
        return m / 1000.0;
    }

    /** Core stat in milli-units. qualityBp and refineBonusBp are basis points (10000 = ×1.0 / +0%). */
    public static long coreMilli(long baseMilli, long growthMilli, int advancement, int qualityBp, int refineBonusBp) {
        int adv = Math.clamp(advancement, 0, MAX_ADVANCEMENT);
        long scaledBase = mulBp(baseMilli, qualityBp);
        long raw = Math.addExact(scaledBase, Math.multiplyExact(growthMilli, (long) adv));
        return mulBp(raw, BP + refineBonusBp);
    }

    /** Final stat in milli-units. percentBp is the summed percent group (1000 = +10%). */
    public static long finalMilli(long coreMilli, long flatMilli, int percentBp, long minMilli, long capMilli) {
        long v = mulBp(Math.addExact(coreMilli, flatMilli), BP + percentBp);
        return Math.clamp(v, minMilli, capMilli);
    }

    /** value × bp / 10000, rounded half away from zero. */
    static long mulBp(long value, long bp) {
        long product = Math.multiplyExact(value, bp);
        long q = product / BP;
        long r = Math.abs(product % BP);
        return r * 2 >= BP ? q + Long.signum(product) : q;
    }

    /** Golden values from the GDD (T01). Throws if the formula drifts. */
    public static void selfTest() {
        long core = coreMilli(milli(100), milli(2), 50, BP, 2000);
        long fin = finalMilli(core, milli(15), 1000, 0, Long.MAX_VALUE);
        if (core != 240_000 || fin != 280_500) {
            throw new IllegalStateException("GearMath golden test failed: core=" + core + " final=" + fin);
        }
        // Extremes must not overflow.
        coreMilli(milli(1_000_000), milli(10_000), MAX_ADVANCEMENT, 20_000, 100_000);
    }
}
