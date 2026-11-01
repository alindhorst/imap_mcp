package de.alexanderlindhorst.imapmcp.core.metrics;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;

/**
 * Read side of a Store pool for gauges, read at snapshot time. Commons Pool offers created/destroyed counters
 * for the whole pool only, not per key.
 */
public interface PoolGauges {

    int numActive(PoolKey key);

    int numIdle(PoolKey key);

    /** Stores created since the pool was built, all keys together. */
    long createdCount();

    /** Stores destroyed since the pool was built, all keys together. */
    long destroyedCount();
}
