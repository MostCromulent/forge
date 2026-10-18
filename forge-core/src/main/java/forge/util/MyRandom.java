/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.util;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 * MyRandom class.<br>
 * Preferably all Random numbers should be retrieved using this wrapper class
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class MyRandom {
    /** The draw count of the game this thread plays. A game's AI helper threads start from its thread, so they inherit it. */
    private static final InheritableThreadLocal<AtomicLong> draws = new InheritableThreadLocal<>();
    private static Random random = new CountingRandom(new SecureRandom());
    private static final ThreadLocal<Random> threadRandom = new ThreadLocal<>();

    /** Every Random method (nextInt, nextBoolean, shuffle, ...) funnels through next(bits). */
    private static final class CountingRandom extends Random {
        private final Random delegate;
        CountingRandom(Random delegate) { super(0); this.delegate = delegate; }
        @Override protected int next(int bits) {
            final AtomicLong count = draws.get();
            if (count != null) {
                count.incrementAndGet();
            }
            return delegate.nextInt() >>> (32 - bits);
        }
        @Override public void setSeed(long seed) {
            if (delegate != null) delegate.setSeed(seed);   // null during super(0)
        }
    }
    /** Counts this thread's random draws, and those of threads it starts later, in the given count. Undo reads it to detect that randomness was consumed. */
    public static void countDrawsIn(final AtomicLong count) { draws.set(count); }

    public static boolean percentTrue(final int percent) {
        return percent > MyRandom.getRandom().nextInt(100);
    }

    /**
     * Gets the random.
     * 
     * @return the random
     */
    public static Random getRandom() {
        final Random own = threadRandom.get();
        return own != null ? own : MyRandom.random;
    }

    /**
     * Sets a random provider for the current thread only, or clears it with null, and returns the one it replaces.
     * A simulation uses it, so games on other threads keep the shared provider.
     */
    public static Random setThreadRandom(Random random) {
        final Random replaced = threadRandom.get();
        threadRandom.set(random == null || random instanceof CountingRandom ? random : new CountingRandom(random));
        return replaced;
    }

    /**
     * Sets the random provider. Used for deterministic simulation.
     * @param random the random
     */
    public static void setRandom(Random random) {
        MyRandom.random = random instanceof CountingRandom ? random : new CountingRandom(random);
    }

    public static int[] splitIntoRandomGroups(final int value, final int numGroups) {
        int[] groups = new int[numGroups];

        for (int i = 0; i < value; i++) {
            groups[getRandom().nextInt(numGroups)]++;
        }

        return groups;
    }
}
