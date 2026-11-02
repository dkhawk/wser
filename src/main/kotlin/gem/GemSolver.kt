package com.sphericalchickens.gem

import com.sphericalchickens.Category
import com.sphericalchickens.DEFAULT_LOTTERY_SPOTS
import com.sphericalchickens.DEFAULT_NUM_SIMULATIONS
import com.sphericalchickens.YEAR
import com.sphericalchickens.createCategories
import com.sphericalchickens.getDocument
import com.sphericalchickens.runSimulations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.io.path.Path
import kotlin.system.measureTimeMillis

/**
 * Formats a [Double] probability (`0.0..1.0`) as a percentage string with [digits] decimal places.
 *
 * @param digits Number of digits after the decimal point.
 * @return Formatted percentage value (without the `%` sign).
 */
fun Double.toPercentString(digits: Int = 3): String {
    val percentage = this * 100.0
    return "%.${digits}f".format(percentage)
}

/**
 * Entry point for the optimized parallel Monte Carlo solver.
 *
 * Pass `--force-download` in [args] to bypass the local HTML cache and re-download
 * the latest entrant data from `wser.org`.
 */
fun main(args: Array<String>) {
    val forceDownload = "--force-download" in args
    val totalTimeMs = measureTimeMillis {
        runBlocking {
            val url = "https://www.wser.org/lottery$YEAR.html"
            val localCachePath = Path(System.getProperty("user.home"), "tmp", "lottery$YEAR.html")
            val numSimulations = DEFAULT_NUM_SIMULATIONS

            println("Fetching data from $url with local cache at $localCachePath...")

            val doc = getDocument(url, localCachePath, forceDownload = forceDownload)
            val categories = createCategories(doc)

            println("\n--- Part 1: Category Data ---")
            println(categories.joinToString("\n"))

            println("\n--- Part 2: Starting $numSimulations Parallel Simulations ---")
            val startTime = System.currentTimeMillis()

            val results = withContext(Dispatchers.Default) {
                runParallelSimulations(this, categories, numSimulations)
            }

            val endTime = System.currentTimeMillis()
            println("Total Simulation Time: ${endTime - startTime} ms")

            println("\n--- Optimized Monte Carlo Results ---")
            results.toList()
                .sortedBy { (tickets, _) -> tickets }
                .joinToString(separator = "\n") { (tickets, probability) ->
                    "$tickets Tickets: ${probability.toPercentString()}%"
                }
                .let(::println)
        }
    }

    println("Time: $totalTimeMs")
}

/**
 * Runs the parallel Monte Carlo simulation across all CPU cores.
 *
 * Delegates to [runSimulations] so that both entry points share the same verified,
 * zero-allocation parallel simulation engine.
 *
 * @param scope The [CoroutineScope] for launching worker coroutines.
 * @param categories The parsed WSER lottery categories.
 * @param numSimulations Number of Monte Carlo trials to run.
 * @param spots Number of unique lottery winners per trial.
 * @return Map of ticket count to estimated win probability (`0.0..1.0`).
 */
suspend fun runParallelSimulations(
    scope: CoroutineScope,
    categories: List<Category>,
    numSimulations: Int = DEFAULT_NUM_SIMULATIONS,
    spots: Int = DEFAULT_LOTTERY_SPOTS,
): Map<Int, Double> = runSimulations(
    scope = scope,
    categories = categories,
    numSimulations = numSimulations,
    spots = spots,
)