package com.amatsagu.reson.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object ResonDispatchers {
    // Bounded to 2 threads to avoid lock contention on SQLite database file
    val Database: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
    val Network: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(3)
    val SingleWorker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    val Computation: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)
    val Main: CoroutineDispatcher = Dispatchers.Main.immediate
}
