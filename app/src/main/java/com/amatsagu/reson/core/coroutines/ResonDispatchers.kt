package com.amatsagu.reson.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Strict internal worker pools and thread limits to prevent coroutine thread bloat.
 * Replaces unbounded Dispatchers.IO (which defaults to up to 64 threads) with strict,
 * purpose-built bounded pools.
 */
object ResonDispatchers {
    /**
     * Bounded IO dispatcher for database operations (SQLite serialized access, max 2 concurrent threads).
     * Prevents thread contention on the single SQLite database lock.
     */
    val Database: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    /**
     * Bounded IO dispatcher for network / yt-dlp tasks (max 3 concurrent threads).
     * Prevents network thread storms and rate-limit violations.
     */
    val Network: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(3)

    /**
     * Dedicated single-thread worker for state coordination, debounce timers, and network monitoring.
     */
    val SingleWorker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * CPU-bound computation and search ranking dispatcher (max 2 threads).
     */
    val Computation: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /**
     * Main thread dispatcher for immediate UI state emission.
     */
    val Main: CoroutineDispatcher = Dispatchers.Main.immediate
}
