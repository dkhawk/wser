package com.sphericalchickens

import com.sphericalchickens.gem2.calculateExactLotteryAndWaitlistProbabilities
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.measureTimedValue

/**
 * The target Western States Endurance Run (WSER) lottery year.
 */
const val YEAR = 2027

/**
 * Default number of lottery winners drawn each year in the WSER lottery.
 */
const val DEFAULT_LOTTERY_SPOTS = 260

/**
 * First 10 waitlist spots (`1..10`).
 */
const val WAITLIST_SIZE_10 = 10

/**
 * First 25 waitlist spots (`1..25`).
 */
const val WAITLIST_SIZE_25 = 25

/**
 * Standard lower-bound waitlist size (50 runners, `1..50`).
 */
const val WAITLIST_SIZE_50 = 50

/**
 * Standard upper-bound waitlist size (75 runners, `1..75`).
 */
const val WAITLIST_SIZE_75 = 75

/**
 * Default number of Monte Carlo trials to run for probability estimation.
 */
const val DEFAULT_NUM_SIMULATIONS = 500_000

/**
 * Default waitlist buckets grouped by tens up to 75 runners (`1-10`, `11-20`, ..., `71-75`).
 */
val DEFAULT_WAITLIST_BUCKETS: List<WaitlistBucket> = listOf(
    WaitlistBucket("1-10", 1, 10),
    WaitlistBucket("11-20", 11, 20),
    WaitlistBucket("21-30", 21, 30),
    WaitlistBucket("31-40", 31, 40),
    WaitlistBucket("41-50", 41, 50),
    WaitlistBucket("51-60", 51, 60),
    WaitlistBucket("61-70", 61, 70),
    WaitlistBucket("71-75", 71, 75),
)

/**
 * Represents a single ticket tier (category) in the WSER lottery.
 *
 * Because WSER awards $2^{n-1}$ tickets for $n$ consecutive years in the lottery,
 * entrants are grouped by their ticket count.
 *
 * @property tickets The number of tickets held by each individual entrant in this category.
 * @property entrants The number of distinct athletes in this category.
 * @property total The total number of tickets in the hat from this category ([tickets] * [entrants]).
 */
data class Category(
    val tickets: Int,
    val entrants: Int,
    val total: Int,
)

/**
 * Represents a 1-indexed rank interval on the WSER waitlist (e.g., `1..10`, `11..20`).
 *
 * @property label Human-readable label for display (e.g., `"1-10"`).
 * @property startRank 1-indexed inclusive start rank on the waitlist.
 * @property endRank 1-indexed inclusive end rank on the waitlist.
 */
data class WaitlistBucket(
    val label: String,
    val startRank: Int,
    val endRank: Int,
) {
    /** Number of waitlist spots contained in this bucket. */
    val size: Int get() = endRank - startRank + 1
}

/**
 * Complete probability distribution across the main lottery, 10-runner waitlist buckets,
 * and cumulative waitlist thresholds (`1..10`, `1..25`, `1..50`, `1..75`) for each ticket category.
 *
 * @property mainLottery Map of (`tickets -> probability of winning one of the main lottery spots`).
 * @property waitlistByBucket Map of (`WaitlistBucket -> (tickets -> probability of landing in that bucket)`).
 * @property waitlistTop10 Map of (`tickets -> probability of landing in waitlist positions 1..10`).
 * @property waitlistTop25 Map of (`tickets -> probability of landing in waitlist positions 1..25`).
 * @property waitlistTop50 Map of (`tickets -> probability of landing in waitlist positions 1..50`).
 * @property waitlistTop75 Map of (`tickets -> probability of landing in waitlist positions 1..75`).
 */
data class LotteryOutcomeProbabilities(
    val mainLottery: Map<Int, Double>,
    val waitlistByBucket: Map<WaitlistBucket, Map<Int, Double>>,
    val waitlistTop10: Map<Int, Double>,
    val waitlistTop25: Map<Int, Double>,
    val waitlistTop50: Map<Int, Double>,
    val waitlistTop75: Map<Int, Double>,
)

/**
 * Entry point for the WSER Lottery & Waitlist probability calculator.
 *
 * Measures and reports execution time for each distinct stage:
 * 1. **Data Loading & HTML Parsing** ([getDocument] and [createCategories])
 * 2. **Parallel Monte Carlo Simulation** ([runLotteryAndWaitlistSimulations])
 * 3. **Analytical Formula Solution** ([calculateExactLotteryAndWaitlistProbabilities])
 *
 * Pass `--force-download` in [args] to bypass the local HTML cache and re-download
 * the latest entrant data from `wser.org`.
 *
 * @param args Command-line arguments; supports `--force-download`.
 */
fun main(args: Array<String>) {
    val forceDownload = "--force-download" in args
    val (_, totalDuration) = measureTimedValue {
        runBlocking {
            val url = "https://www.wser.org/lottery$YEAR.html"
            val cachePath = Path(System.getProperty("user.home"), "tmp", "lottery$YEAR.html")

            // --- Phase 1: Load and parse WSER lottery data ---
            val (categories, loadDuration) = measureTimedValue {
                val doc = getDocument(url, cachePath, forceDownload = forceDownload)
                createCategories(doc)
            }

            println("=== Part 1: Category Data ($YEAR WSER Lottery) ===")
            println(categories.joinToString("\n"))
            println("Data Load & Parse Time: $loadDuration\n")

            // --- Phase 2: Parallel Monte Carlo Simulation (Main Lottery + Waitlist) ---
            val (simulatedOutcomes, simDuration) = measureTimedValue {
                withContext(Dispatchers.Default) {
                    runLotteryAndWaitlistSimulations(
                        scope = this,
                        categories = categories,
                        numSimulations = DEFAULT_NUM_SIMULATIONS,
                        spots = DEFAULT_LOTTERY_SPOTS,
                        waitlistBuckets = DEFAULT_WAITLIST_BUCKETS,
                    )
                }
            }

            println("=== Part 2A: Monte Carlo Cumulative Waitlist Table ($DEFAULT_NUM_SIMULATIONS runs) ===")
            printCumulativeWaitlistTable(categories, simulatedOutcomes)
            println()

            println("=== Part 2B: Monte Carlo 10-Runner Waitlist Buckets ($DEFAULT_NUM_SIMULATIONS runs) ===")
            printOutcomeTable(categories, simulatedOutcomes)
            println("Simulation Time: $simDuration\n")

            // --- Phase 3: Analytical Formula Solution (Main Lottery + Waitlist) ---
            val (formulaOutcomes, formulaDuration) = measureTimedValue {
                calculateExactLotteryAndWaitlistProbabilities(
                    categories = categories,
                    spots = DEFAULT_LOTTERY_SPOTS,
                    waitlistBuckets = DEFAULT_WAITLIST_BUCKETS,
                )
            }

            println("=== Part 3: Formula (Analytical) Solution ===")
            printOutcomeTable(categories, formulaOutcomes)
            println("Formula Calculation Time: $formulaDuration\n")

            // --- Summary Comparison ---
            println("=== Summary Comparison (Main Lottery & Waitlist Totals) ===")
            println(
                "%-7s | %-18s | %-18s | %-18s".format(
                    "Tickets",
                    "Main (MC / Form)",
                    "WL 1-50 (MC / Form)",
                    "WL 1-75 (MC / Form)",
                )
            )
            println("-".repeat(70))
            for (category in categories.sortedBy { it.tickets }) {
                val t = category.tickets
                val mainMc = (simulatedOutcomes.mainLottery.getValue(t) * 100.0).format(3)
                val mainFm = (formulaOutcomes.mainLottery.getValue(t) * 100.0).format(3)
                val wl50Mc = (simulatedOutcomes.waitlistTop50.getValue(t) * 100.0).format(3)
                val wl50Fm = (formulaOutcomes.waitlistTop50.getValue(t) * 100.0).format(3)
                val wl75Mc = (simulatedOutcomes.waitlistTop75.getValue(t) * 100.0).format(3)
                val wl75Fm = (formulaOutcomes.waitlistTop75.getValue(t) * 100.0).format(3)
                println(
                    "%7d | %7s%% / %7s%% | %7s%% / %7s%% | %7s%% / %7s%%".format(
                        t,
                        mainMc,
                        mainFm,
                        wl50Mc,
                        wl50Fm,
                        wl75Mc,
                        wl75Fm,
                    )
                )
            }
            println("-".repeat(70))
            println("Data Load Time:          $loadDuration")
            println("Monte Carlo Time:        $simDuration")
            println("Formula Solution Time:   $formulaDuration")
        }
    }

    println("Total Execution Time:    $totalDuration")
}

/**
 * Prints a focused table for the Main Lottery (`1..260`), Waitlist First 10 (`1..10`),
 * Waitlist Up to 25th (`1..25`), and Waitlist Up to 50th (`1..50`).
 */
private fun printCumulativeWaitlistTable(
    categories: List<Category>,
    outcomes: LotteryOutcomeProbabilities,
) {
    val header = "%7s | %8s | %12s | %16s | %16s | %16s".format(
        "Tickets",
        "Entrants",
        "Lottery",
        "WL First 10",
        "WL Up to 25th",
        "WL Up to 50th",
    )
    println(header)
    println("-".repeat(header.length))

    for (category in categories.sortedBy { it.tickets }) {
        val t = category.tickets
        val lotteryPct = (outcomes.mainLottery.getValue(t) * 100.0).format(3) + "%"
        val wl10Pct = (outcomes.waitlistTop10.getValue(t) * 100.0).format(3) + "%"
        val wl25Pct = (outcomes.waitlistTop25.getValue(t) * 100.0).format(3) + "%"
        val wl50Pct = (outcomes.waitlistTop50.getValue(t) * 100.0).format(3) + "%"
        println(
            "%7d | %8d | %12s | %16s | %16s | %16s".format(
                t,
                category.entrants,
                lotteryPct,
                wl10Pct,
                wl25Pct,
                wl50Pct,
            )
        )
    }
}

/**
 * Prints a formatted table showing the probability of winning the main lottery (`1..260`),
 * landing in the first 50 or 75 waitlist spots, and landing in each 10-runner waitlist bucket.
 */
private fun printOutcomeTable(
    categories: List<Category>,
    outcomes: LotteryOutcomeProbabilities,
) {
    val buckets = outcomes.waitlistByBucket.keys.toList()
    val bucketHeaders = buckets.joinToString(" | ") { "%8s".format("WL ${it.label}") }
    val header = "%7s | %9s | %9s | %9s | %s".format(
        "Tickets",
        "Lottery",
        "WL 1-50",
        "WL 1-75",
        bucketHeaders,
    )
    println(header)
    println("-".repeat(header.length))

    for (category in categories.sortedBy { it.tickets }) {
        val t = category.tickets
        val mainPct = (outcomes.mainLottery.getValue(t) * 100.0).format(3) + "%"
        val wl50Pct = (outcomes.waitlistTop50.getValue(t) * 100.0).format(3) + "%"
        val wl75Pct = (outcomes.waitlistTop75.getValue(t) * 100.0).format(3) + "%"
        val bucketCols = buckets.joinToString(" | ") { bucket ->
            val pct = (outcomes.waitlistByBucket.getValue(bucket).getValue(t) * 100.0).format(3) + "%"
            "%8s".format(pct)
        }
        println("%7d | %9s | %9s | %9s | %s".format(t, mainPct, wl50Pct, wl75Pct, bucketCols))
    }
}

/**
 * Retrieves and parses the WSER lottery HTML document.
 *
 * To avoid hammering `wser.org` during repeated benchmark runs, we cache the raw HTML
 * locally on disk. Crucially, the Ktor [HttpClient] is only instantiated inside a `.use`
 * block when a network request is actually required; creating an unclosed client on cache
 * hits would leak background selector threads and sockets.
 *
 * @param url The remote WSER lottery page URL.
 * @param localCachePath Path to the local HTML cache file.
 * @param forceDownload When `true`, ignores any existing cached file and fetches fresh HTML.
 * @return The parsed Jsoup [Document].
 */
suspend fun getDocument(
    url: String,
    localCachePath: Path = Path(System.getProperty("user.home"), "tmp", "lottery$YEAR.html"),
    forceDownload: Boolean = false,
): Document {
    val htmlContent = if (!forceDownload && localCachePath.exists()) {
        try {
            localCachePath.readText()
        } catch (e: IOException) {
            downloadAndCacheHtml(url, localCachePath)
        }
    } else {
        downloadAndCacheHtml(url, localCachePath)
    }

    return Jsoup.parse(htmlContent)
}

/**
 * Downloads the lottery HTML from [url] and persists it to [localCachePath].
 */
private suspend fun downloadAndCacheHtml(url: String, localCachePath: Path): String {
    println("Downloading lottery data from $url...")
    return HttpClient(CIO).use { client ->
        val response = client.get(url)
        val content = response.bodyAsText()
        localCachePath.parent?.toFile()?.mkdirs()
        localCachePath.writeText(content)
        content
    }
}

/**
 * Extracts the ticket category summary table from the parsed WSER HTML [Document].
 *
 * The WSER page uses spacer `<td>` columns between data cells, so we select every
 * even-indexed cell (`0, 2, 4`) corresponding to `Tickets`, `Entrants`, and `Total`.
 *
 * @param doc The parsed WSER lottery HTML document.
 * @return A list of [Category] records in document order.
 */
fun createCategories(doc: Document): List<Category> {
    val path = "/html/body/div/table/tbody/tr[2]/td/table/tbody/tr[2]/td/table/tbody"
    val elements = doc.selectXpath(path)
    val rows = elements.select("tr")

    return rows.drop(1).mapNotNull { row ->
        val numericCells = row.select("td")
            .filterIndexed { index, _ -> index % 2 == 0 }
            .mapNotNull { td -> td.text().replace(",", "").trim().toIntOrNull() }

        if (numericCells.size >= 3) {
            Category(
                tickets = numericCells[0],
                entrants = numericCells[1],
                total = numericCells[2],
            )
        } else {
            null
        }
    }
}

/**
 * Executes [numSimulations] Monte Carlo trials for the main lottery spots only.
 *
 * @param scope The [CoroutineScope] in which worker coroutines are launched.
 * @param categories The lottery category distribution.
 * @param numSimulations Total number of Monte Carlo trials to run.
 * @param spots Number of unique winning entrants per trial.
 * @return A map from category ticket count (`1, 2, 4, ...`) to estimated win probability (`0.0..1.0`).
 */
suspend fun runSimulations(
    scope: CoroutineScope,
    categories: List<Category>,
    numSimulations: Int = DEFAULT_NUM_SIMULATIONS,
    spots: Int = DEFAULT_LOTTERY_SPOTS,
): Map<Int, Double> = runLotteryAndWaitlistSimulations(
    scope = scope,
    categories = categories,
    numSimulations = numSimulations,
    spots = spots,
    waitlistBuckets = emptyList(),
    includeWaitlistTotals = false,
).mainLottery

/**
 * Executes [numSimulations] Monte Carlo trials in parallel, simultaneously computing probabilities
 * for the main lottery (`1..spots`), each [WaitlistBucket] (`1-10`, `11-20`, etc.), and the
 * overall 50-runner and 75-runner waitlists in a single simulation pass.
 *
 * ### How single-pass waitlist tracking works without branching overhead:
 * In each simulation trial, we continue drawing unique athletes up to `spots + maxWaitlistRank`
 * (e.g., `260 + 75 = 335` total unique athletes). We pre-compute a lookup table
 * `drawIndexToStageOffset` mapping each 0-indexed draw order `0 until totalDraws` directly to
 * its stage offset inside a flat primitive [LongArray] (`stageWinCounts`).
 *
 * As a result, recording whether a drawn athlete won the main lottery or landed in a specific
 * 10-runner waitlist bucket requires only a single array lookup and integer increment inside the
 * inner loop.
 *
 * @param scope The [CoroutineScope] in which worker coroutines are launched.
 * @param categories The lottery category distribution.
 * @param numSimulations Total number of Monte Carlo trials to run.
 * @param spots Number of main lottery spots ($S = 260$).
 * @param waitlistBuckets The 1-indexed waitlist rank buckets to track.
 * @param includeWaitlistTotals Whether to draw at least through waitlist rank 75 to populate
 *   [LotteryOutcomeProbabilities.waitlistTop50] and [LotteryOutcomeProbabilities.waitlistTop75].
 * @return [LotteryOutcomeProbabilities] containing main lottery, bucketed waitlist, and 50/75 waitlist probabilities.
 */
suspend fun runLotteryAndWaitlistSimulations(
    scope: CoroutineScope,
    categories: List<Category>,
    numSimulations: Int = DEFAULT_NUM_SIMULATIONS,
    spots: Int = DEFAULT_LOTTERY_SPOTS,
    waitlistBuckets: List<WaitlistBucket> = DEFAULT_WAITLIST_BUCKETS,
    includeWaitlistTotals: Boolean = true,
): LotteryOutcomeProbabilities {
    val zeroMap = categories.associate { it.tickets to 0.0 }
    val activeCategories = categories.filter { it.entrants > 0 && it.tickets > 0 }
    if (activeCategories.isEmpty() || numSimulations <= 0 || spots <= 0) {
        return LotteryOutcomeProbabilities(
            mainLottery = zeroMap,
            waitlistByBucket = waitlistBuckets.associateWith { zeroMap },
            waitlistTop10 = zeroMap,
            waitlistTop25 = zeroMap,
            waitlistTop50 = zeroMap,
            waitlistTop75 = zeroMap,
        )
    }

    val numCategories = activeCategories.size
    val totalAthletes = activeCategories.sumOf { it.entrants }
    val totalTickets = activeCategories.sumOf { it.tickets * it.entrants }

    val maxBucketRank = waitlistBuckets.maxOfOrNull { it.endRank } ?: 0
    val maxWaitlistRank = if (includeWaitlistTotals) {
        maxOf(maxBucketRank, WAITLIST_SIZE_75)
    } else {
        maxBucketRank
    }
    val totalRequestedDraws = (spots + maxWaitlistRank).coerceAtMost(totalAthletes)

    // Pre-build flat primitive lookup tables mapping each ticket index in [0, totalTickets)
    // to its owner's unique athlete ID and category index.
    val ticketToAthleteId = IntArray(totalTickets)
    val ticketToCategoryIdx = IntArray(totalTickets)

    var athleteCursor = 0
    var ticketCursor = 0
    activeCategories.forEachIndexed { categoryIdx, category ->
        repeat(category.entrants) {
            val athleteId = athleteCursor++
            repeat(category.tickets) {
                ticketToAthleteId[ticketCursor] = athleteId
                ticketToCategoryIdx[ticketCursor] = categoryIdx
                ticketCursor++
            }
        }
    }

    // Map each draw position (0 until totalRequestedDraws) to its category-First index
    // in a flat `LongArray(totalRequestedDraws * numCategories)`, or group by draw rank:
    // Because `totalRequestedDraws` is at most ~335 and `numCategories` is ~11, an array of
    // size `totalRequestedDraws * numCategories` (~3,685 longs = 29 KB) fits entirely in L1 cache!
    // Recording exact counts per draw rank [0 until totalRequestedDraws] allows us to sum any
    // arbitrary waitlist bucket or cumulative threshold after the simulation with zero inner-loop logic.
    val counterArraySize = totalRequestedDraws * numCategories

    val numWorkers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val baseBatchSize = numSimulations / numWorkers
    val remainder = numSimulations % numWorkers

    val workerJobs = (0 until numWorkers).map { workerIdx ->
        val workerSimulations = baseBatchSize + if (workerIdx < remainder) 1 else 0

        scope.async(Dispatchers.Default) {
            // Flat L1-cache-resident counter array indexed by `[drawOrder * numCategories + categoryIdx]`
            val winsByDrawOrderAndCategory = LongArray(counterArraySize)
            val seenAtSim = IntArray(totalAthletes)
            val rng = ThreadLocalRandom.current()

            for (simId in 1..workerSimulations) {
                var drawOrder = 0
                while (drawOrder < totalRequestedDraws) {
                    val drawnTicket = rng.nextInt(totalTickets)
                    val athleteId = ticketToAthleteId[drawnTicket]
                    if (seenAtSim[athleteId] != simId) {
                        seenAtSim[athleteId] = simId
                        val categoryIdx = ticketToCategoryIdx[drawnTicket]
                        winsByDrawOrderAndCategory[drawOrder * numCategories + categoryIdx]++
                        drawOrder++
                    }
                }
            }
            winsByDrawOrderAndCategory
        }
    }

    // Aggregate counts across all workers.
    val totalWinsByDrawOrderAndCategory = LongArray(counterArraySize)
    for (workerCounts in workerJobs.awaitAll()) {
        for (i in 0 until counterArraySize) {
            totalWinsByDrawOrderAndCategory[i] += workerCounts[i]
        }
    }

    // Helper to sum probabilities for a 0-indexed draw order range [startDrawInclusive, endDrawExclusive).
    fun probabilitiesForDrawRange(startDrawInclusive: Int, endDrawExclusive: Int): Map<Int, Double> {
        val clampedStart = startDrawInclusive.coerceIn(0, totalRequestedDraws)
        val clampedEnd = endDrawExclusive.coerceIn(clampedStart, totalRequestedDraws)

        val activeProbs = activeCategories.mapIndexed { categoryIdx, category ->
            var sumWins = 0L
            for (drawOrder in clampedStart until clampedEnd) {
                sumWins += totalWinsByDrawOrderAndCategory[drawOrder * numCategories + categoryIdx]
            }
            val totalPossible = category.entrants.toDouble() * numSimulations.toDouble()
            category.tickets to (sumWins.toDouble() / totalPossible)
        }.toMap()

        return categories.associate { category ->
            category.tickets to (activeProbs[category.tickets] ?: 0.0)
        }
    }

    val mainLotteryProbs = probabilitiesForDrawRange(0, spots)
    val bucketProbs = waitlistBuckets.associateWith { bucket ->
        probabilitiesForDrawRange(
            startDrawInclusive = spots + bucket.startRank - 1,
            endDrawExclusive = spots + bucket.endRank,
        )
    }
    val waitlist10Probs = probabilitiesForDrawRange(spots, spots + WAITLIST_SIZE_10)
    val waitlist25Probs = probabilitiesForDrawRange(spots, spots + WAITLIST_SIZE_25)
    val waitlist50Probs = probabilitiesForDrawRange(spots, spots + WAITLIST_SIZE_50)
    val waitlist75Probs = probabilitiesForDrawRange(spots, spots + WAITLIST_SIZE_75)

    return LotteryOutcomeProbabilities(
        mainLottery = mainLotteryProbs,
        waitlistByBucket = bucketProbs,
        waitlistTop10 = waitlist10Probs,
        waitlistTop25 = waitlist25Probs,
        waitlistTop50 = waitlist50Probs,
        waitlistTop75 = waitlist75Probs,
    )
}

/**
 * Formats a [Double] to a fixed number of decimal [digits].
 *
 * @param digits Number of digits after the decimal point.
 * @return Formatted numeric string.
 */
fun Double.format(digits: Int): String = "%.${digits}f".format(this)