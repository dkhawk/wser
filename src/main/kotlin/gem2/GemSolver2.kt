package com.sphericalchickens.gem2

import com.sphericalchickens.Category
import com.sphericalchickens.DEFAULT_LOTTERY_SPOTS
import com.sphericalchickens.DEFAULT_WAITLIST_BUCKETS
import com.sphericalchickens.LotteryOutcomeProbabilities
import com.sphericalchickens.WAITLIST_SIZE_50
import com.sphericalchickens.WAITLIST_SIZE_75
import com.sphericalchickens.WaitlistBucket
import com.sphericalchickens.YEAR
import com.sphericalchickens.createCategories
import com.sphericalchickens.gem.toPercentString
import com.sphericalchickens.getDocument
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Entry point for the analytical (non-simulated) WSER lottery probability solver.
 *
 * Pass `--force-download` in [args] to bypass the local HTML cache and re-download
 * the latest entrant data from `wser.org`.
 */
fun main(args: Array<String>) = runBlocking {
    val forceDownload = "--force-download" in args
    val url = "https://www.wser.org/lottery$YEAR.html"
    val localCachePath = Path(System.getProperty("user.home"), "tmp", "lottery$YEAR.html")

    println("Fetching data from $url with local cache at $localCachePath...")

    val doc = getDocument(url, localCachePath, forceDownload = forceDownload)
    val categories = createCategories(doc)

    println("\n--- Part 1: Category Data ---")
    println("Tickets | Entrants | Total Tickets")
    println(
        categories.joinToString("\n") {
            "%7d | %8d | %13d".format(it.tickets, it.entrants, it.total)
        }
    )

    // --- Part 2: Analytical Calculation ---
    val startTime = System.nanoTime()
    val results = calculateExactProbabilities(categories)
    val elapsedMicros = (System.nanoTime() - startTime) / 1_000.0

    println("\n--- Part 2: Analytical Results ---")
    println("Calculation Time: %.2f µs".format(elapsedMicros))

    println("\n--- Exact Probability of Winning (Non-Simulated) ---")
    results.toList()
        .sortedBy { (tickets, _) -> tickets }
        .joinToString(separator = "\n") { (tickets, probability) ->
            "$tickets Tickets: ${probability.toPercentString()}%"
        }
        .let(::println)
}

/**
 * Analytically computes the probability of winning the main lottery (`1..spots`), landing in each
 * 10-runner [WaitlistBucket] (`1-10`, `11-20`, etc.), and landing in the overall 50-runner or
 * 75-runner waitlists.
 *
 * ### Mathematical Principle:
 * Because the WSER waitlist is drawn sequentially right after the [spots] main lottery winners
 * using the exact same weighted sampling without replacement of entrants, the event
 * $E(M) = \{\text{selected within the first } M \text{ unique entrants}\}$ is monotonically nested:
 * $E(M_1) \subset E(M_2)$ whenever $M_1 < M_2$.
 *
 * Consequently, the probability of an entrant in category $c$ landing in waitlist ranks $[a, b]$
 * (which correspond to overall draw positions $[\text{spots} + a, \, \text{spots} + b]$) is the
 * exact difference between two cumulative Exponential Race evaluations:
 * $$P(\text{Waitlist } a..b \mid c) = P\left(E(\text{spots} + b) \mid c\right) - P\left(E(\text{spots} + a - 1) \mid c\right)$$
 *
 * Cumulative evaluations are memoized by boundary rank so shared endpoints (such as rank 10
 * between `1-10` and `11-20`) are computed only once.
 *
 * @param categories The list of lottery categories.
 * @param spots Number of main lottery spots ($S = 260$).
 * @param waitlistBuckets The 1-indexed waitlist rank buckets to compute.
 * @return [LotteryOutcomeProbabilities] containing main lottery, bucketed waitlist, and 50/75 waitlist probabilities.
 */
fun calculateExactLotteryAndWaitlistProbabilities(
    categories: List<Category>,
    spots: Int = DEFAULT_LOTTERY_SPOTS,
    waitlistBuckets: List<WaitlistBucket> = DEFAULT_WAITLIST_BUCKETS,
): LotteryOutcomeProbabilities {
    val zeroMap = categories.associate { it.tickets to 0.0 }
    if (categories.isEmpty() || spots <= 0) {
        return LotteryOutcomeProbabilities(
            mainLottery = zeroMap,
            waitlistByBucket = waitlistBuckets.associateWith { zeroMap },
            waitlistTop50 = zeroMap,
            waitlistTop75 = zeroMap,
        )
    }

    // Memoize cumulative probability maps F(M) = P(drawn in first M unique runners)
    val cumulativeCache = mutableMapOf<Int, Map<Int, Double>>()
    fun cumulativeAt(totalDrawnSpots: Int): Map<Int, Double> =
        cumulativeCache.getOrPut(totalDrawnSpots) {
            calculateExactProbabilities(categories, spots = totalDrawnSpots)
        }

    fun intervalProbability(startDrawExclusive: Int, endDrawInclusive: Int): Map<Int, Double> {
        if (endDrawInclusive <= startDrawExclusive) return zeroMap
        val upper = cumulativeAt(endDrawInclusive)
        val lower = cumulativeAt(startDrawExclusive)
        return categories.associate { cat ->
            val diff = (upper.getValue(cat.tickets) - lower.getValue(cat.tickets)).coerceAtLeast(0.0)
            cat.tickets to diff
        }
    }

    val mainLottery = cumulativeAt(spots)
    val bucketProbabilities = waitlistBuckets.associateWith { bucket ->
        intervalProbability(
            startDrawExclusive = spots + bucket.startRank - 1,
            endDrawInclusive = spots + bucket.endRank,
        )
    }
    val waitlist50 = intervalProbability(spots, spots + WAITLIST_SIZE_50)
    val waitlist75 = intervalProbability(spots, spots + WAITLIST_SIZE_75)

    return LotteryOutcomeProbabilities(
        mainLottery = mainLottery,
        waitlistByBucket = bucketProbabilities,
        waitlistTop50 = waitlist50,
        waitlistTop75 = waitlist75,
    )
}

/**
 * Calculates the analytical probability of an athlete winning in each category using the
 * **Exponential Race** representation of **Wallenius' Noncentral Hypergeometric Distribution**.
 *
 * ### Why the previous hypergeometric ticket formula was wrong:
 * The previous implementation calculated $\prod_{i=0}^{S-1} \frac{T - k - i}{T - i}$, which
 * models drawing $S = 260$ *tickets* without replacement. However, in the WSER lottery,
 * **entrants** are drawn without replacement: once an athlete with $k$ tickets wins a spot,
 * all $k$ of their tickets are removed from the pool (duplicate draws are discarded and
 * re-drawn until 260 unique athletes win). Because high-ticket entrants are drawn more often,
 * the ticket pool shrinks by far more than 1 ticket per winner, so the naive formula
 * systematically underestimated every entrant's chance of winning.
 *
 * ### The Exact Exponential Race Formulation:
 * By the memoryless property of the exponential distribution, sequential weighted sampling
 * without replacement is mathematically identical to assigning each entrant $j$ (holding $k_j$
 * tickets) an independent arrival time $E_j \sim \text{Exponential}(k_j)$ and selecting the
 * $S$ entrants with the smallest arrival times.
 *
 * An entrant in category $c$ (with $k_c$ tickets) wins if and only if at their arrival time
 * $t = E_i$, fewer than $S$ other entrants have arrived ($X_{-c}(t) \le S - 1$):
 * $$P(\text{Win}_c) = \int_0^\infty P\left(X_{-c}(t) \le S - 1\right) k_c e^{-k_c t} \, dt$$
 *
 * Because $X_{-c}(t)$ is the sum of independent Binomial$(n'_m, 1 - e^{-k_m t})$ counts across
 * ~10,000 entrants, $P(X_{-c}(t) \le S - 1)$ transitions sharply from $1$ to $0$ in a narrow
 * window around the critical time $t^*$ where $\mathbb{E}[X_{-c}(t^*)] = S - 0.5$. We locate
 * $t^*$ via Newton-Raphson and integrate across $[t^* - 8\sigma_t, t^* + 8\sigma_t]$ using
 * an Edgeworth skew-normal CDF with continuity correction.
 *
 * @param categories The list of lottery categories.
 * @param spots The number of available spots in the lottery ($S$).
 * @return A map of (`Tickets -> Analytical Probability of Winning`).
 */
fun calculateExactProbabilities(
    categories: List<Category>,
    spots: Int = DEFAULT_LOTTERY_SPOTS,
): Map<Int, Double> {
    val activeCategories = categories.filter { it.entrants > 0 && it.tickets > 0 }
    val totalEntrants = activeCategories.sumOf { it.entrants }
    if (activeCategories.isEmpty() || spots <= 0) {
        return categories.associate { it.tickets to 0.0 }
    }
    if (spots >= totalEntrants) {
        return categories.associate { it.tickets to if (it.entrants > 0 && it.tickets > 0) 1.0 else 0.0 }
    }


    val activeResults = activeCategories.associate { targetCategory ->
        // Build the pool of "other" entrants (excluding one representative athlete from `targetCategory`).
        val otherCounts = IntArray(activeCategories.size) { idx ->
            val cat = activeCategories[idx]
            if (cat.tickets == targetCategory.tickets) cat.entrants - 1 else cat.entrants
        }
        val ticketWeights = DoubleArray(activeCategories.size) { idx ->
            activeCategories[idx].tickets.toDouble()
        }

        // Step 1: Solve for the critical time t* where E[X_{-c}(t*)] = spots - 0.5 via Newton-Raphson.
        val totalOtherTickets = activeCategories.indices.sumOf { idx ->
            otherCounts[idx].toLong() * activeCategories[idx].tickets.toLong()
        }.toDouble()
        var tStar = (spots - 0.5) / totalOtherTickets

        repeat(25) {
            var mean = 0.0
            var derivative = 0.0
            for (idx in activeCategories.indices) {
                val n = otherCounts[idx]
                if (n <= 0) continue
                val k = ticketWeights[idx]
                val q = exp(-k * tStar)
                val p = 1.0 - q
                mean += n * p
                derivative += n * k * q
            }
            val diff = mean - (spots - 0.5)
            if (abs(diff) < 1e-12) return@repeat
            tStar = (tStar - diff / derivative).coerceAtLeast(1e-15)
        }

        // Compute the standard deviation of the arrival time threshold: sigma_t = sigma(t*) / mu'(t*).
        var varianceAtStar = 0.0
        var derivativeAtStar = 0.0
        for (idx in activeCategories.indices) {
            val n = otherCounts[idx]
            if (n <= 0) continue
            val k = ticketWeights[idx]
            val q = exp(-k * tStar)
            val p = 1.0 - q
            varianceAtStar += n * p * q
            derivativeAtStar += n * k * q
        }
        val sigmaT = sqrt(varianceAtStar) / derivativeAtStar

        // Step 2: Integrate P(X_{-c}(t) <= spots - 1) * k_c * exp(-k_c * t) dt.
        // For t in [0, tMin], P(X_{-c}(t) <= spots - 1) == 1.0 to machine precision,
        // contributing closed-form probability (1 - exp(-k_c * tMin)).
        val kTarget = targetCategory.tickets.toDouble()
        val tMin = max(0.0, tStar - 8.0 * sigmaT)
        val tMax = tStar + 8.0 * sigmaT
        val numSteps = 128
        val dt = (tMax - tMin) / numSteps

        var integral = 1.0 - exp(-kTarget * tMin)

        // Composite midpoint rule across [tMin, tMax].
        for (step in 0 until numSteps) {
            val t = tMin + (step + 0.5) * dt
            val survivalProb = probabilityFewerThanSpotsArrived(
                t = t,
                spots = spots,
                otherCounts = otherCounts,
                ticketWeights = ticketWeights,
            )
            val density = kTarget * exp(-kTarget * t)
            integral += survivalProb * density * dt
        }

        targetCategory.tickets to integral.coerceIn(0.0, 1.0)
    }

    return categories.associate { category ->
        category.tickets to (activeResults[category.tickets] ?: 0.0)
    }
}

/**
 * Evaluates $P(X_{-c}(t) \le \text{spots} - 1)$ at arrival time [t] using a continuity-corrected
 * Edgeworth expansion (normal CDF plus third-cumulant skewness correction).
 */
private fun probabilityFewerThanSpotsArrived(
    t: Double,
    spots: Int,
    otherCounts: IntArray,
    ticketWeights: DoubleArray,
): Double {
    var mean = 0.0
    var variance = 0.0
    var thirdCentralMoment = 0.0

    for (idx in otherCounts.indices) {
        val n = otherCounts[idx]
        if (n <= 0) continue
        val k = ticketWeights[idx]
        val q = exp(-k * t)
        val p = 1.0 - q
        val npq = n * p * q
        mean += n * p
        variance += npq
        thirdCentralMoment += npq * (q - p)
    }

    if (variance <= 1e-15) {
        return if (mean <= spots - 0.5) 1.0 else 0.0
    }

    val sigma = sqrt(variance)
    val z = (spots - 0.5 - mean) / sigma
    val skewness = thirdCentralMoment / (variance * sigma)

    val phi = exp(-0.5 * z * z) / sqrt(2.0 * Math.PI)
    val edgeworthCorrection = -(skewness / 6.0) * (z * z - 1.0) * phi

    return (standardNormalCdf(z) + edgeworthCorrection).coerceIn(0.0, 1.0)
}

/**
 * High-precision approximation of the standard normal cumulative distribution function $\Phi(z)$
 * (Abramowitz & Stegun 7.1.26, maximum absolute error $< 1.5 \times 10^{-7}$).
 */
private fun standardNormalCdf(z: Double): Double {
    val sign = if (z < 0.0) -1.0 else 1.0
    val x = abs(z) / sqrt(2.0)

    val t = 1.0 / (1.0 + 0.3275911 * x)
    val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    val erf = 1.0 - poly * exp(-x * x)

    return 0.5 * (1.0 + sign * erf)
}