package com.exea.entity.core;

/**
 * Counters of the work EXea does, for {@code /exea stats} (main thread only, never reset; the command
 * shows the change since its last call).
 */
public final class EXeaStats {

    /** Bone transform changes sent (one entity-metadata packet per viewer each). */
    public static long boneUpdates;
    /** Carrier moves (one packet per viewer each, for all bones of a model together). */
    public static long carrierMoves;
    /** Full pose computations (all bones of one entity). */
    public static long poses;
    /** Entity ticks that skipped posing because no player was near. */
    public static long hiddenTicks;
    /** Entity ticks in total. */
    public static long entityTicks;
    /** Times a full bone set was respawned by the self-healing renderer. */
    public static long respawns;

    private EXeaStats() {
    }
}
