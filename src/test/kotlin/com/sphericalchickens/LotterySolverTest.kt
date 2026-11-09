package com.sphericalchickens

import com.sphericalchickens.gem.toPercentString
import com.sphericalchickens.gem2.calculateExactLotteryAndWaitlistProbabilities
import com.sphericalchickens.gem2.calculateExactProbabilities
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive test suite for the WSER Lottery probability engines and HTML parser.
 *
 * This suite leverages the **Kotlin Power-Assert compiler plugin** (`kotlin("plugin.power-assert")`).
 * Rather than writing manual failure message strings, assertions are expressed directly as
 * boolean expressions inside [assertTrue]; if any condition fails, the compiler plugin
 * automatically renders a full value diagram of every sub-expression.
 */
class LotterySolverTest {

    /**
     * Representative 2027 WSER lottery distribution across 11 ticket tiers.
     */
    private val wser2027Categories = listOf(
        Category(tickets = 1024, entrants = 1, total = 1_024),
        Category(tickets = 512, entrants = 7, total = 3_584),
        Category(tickets = 256, entrants = 46, total = 11_776),
        Category(tickets = 128, entrants = 94, total = 12_032),
        Category(tickets = 64, entrants = 175, total = 11_200),
        Category(tickets = 32, entrants = 342, total = 10_944),
        Category(tickets = 16, entrants = 511, total = 8_176),
        Category(tickets = 8, entrants = 778, total = 6_224),
        Category(tickets = 4, entrants = 998, total = 3_992),
        Category(tickets = 2, entrants = 1_435, total = 2_870),
        Category(tickets = 1, entrants = 1_717, total = 1_717),
    )

    @Test
    fun `createCategories extracts ticket tiers and strips comma separators from WSER HTML`() {
        val sampleHtml = """
            <html>
              <body>
                <div>
                  <table>
                    <tbody>
                      <tr><td>Header</td></tr>
                      <tr>
                        <td>
                          <table>
                            <tbody>
                              <tr><td>Summary</td></tr>
                              <tr>
                                <td>
                                  <table>
                                    <tbody>
                                      <tr>
                                        <th>Tickets</th><td>&nbsp;</td>
                                        <th>Entrants</th><td>&nbsp;</td>
                                        <th>Total</th>
                                      </tr>
                                      <tr>
                                        <td>1,024</td><td>&nbsp;</td>
                                        <td>1</td><td>&nbsp;</td>
                                        <td>1,024</td>
                                      </tr>
                                      <tr>
                                        <td>2</td><td>&nbsp;</td>
                                        <td>1,435</td><td>&nbsp;</td>
                                        <td>2,870</td>
                                      </tr>
                                    </tbody>
                                  </table>
                                </td>
                              </tr>
                            </tbody>
                          </table>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </body>
            </html>
        """.trimIndent()

        val document = Jsoup.parse(sampleHtml)
        val categories = createCategories(document)

        assertEquals(
            listOf(
                Category(tickets = 1024, entrants = 1, total = 1024),
                Category(tickets = 2, entrants = 1435, total = 2870),
            ),
            categories,
        )
        categories.forEach { category ->
            assertTrue(category.tickets * category.entrants == category.total)
        }
    }

    @Test
    fun `uniform single-category lottery yields exact spots-over-entrants probability`() = runBlocking {
        // When all entrants hold the exact same number of tickets, every entrant is equally likely
        // to be chosen regardless of ticket weight: P(Win) = spots / entrants.
        val uniformCategories = listOf(Category(tickets = 8, entrants = 500, total = 4_000))
        val spots = 125
        val expectedProbability = spots.toDouble() / 500.0

        val simProbs = runSimulations(this, uniformCategories, numSimulations = 50_000, spots = spots)
        val exactProbs = calculateExactProbabilities(uniformCategories, spots = spots)

        assertTrue(abs(simProbs.getValue(8) - expectedProbability) < 1e-9)
        assertTrue(abs(exactProbs.getValue(8) - expectedProbability) < 1e-4)
    }

    @Test
    fun `when spots equal or exceed total entrants everyone wins with probability 1`() = runBlocking {
        val totalEntrants = wser2027Categories.sumOf { it.entrants }

        val simProbs = runSimulations(this, wser2027Categories, numSimulations = 1_000, spots = totalEntrants + 50)
        val exactProbs = calculateExactProbabilities(wser2027Categories, spots = totalEntrants + 50)

        for (category in wser2027Categories) {
            assertTrue(simProbs.getValue(category.tickets) == 1.0)
            assertTrue(exactProbs.getValue(category.tickets) == 1.0)
        }
    }

    @Test
    fun `empty categories or zero spots return zero probabilities`() = runBlocking {
        val emptyResultSim = runSimulations(this, emptyList(), numSimulations = 1_000, spots = 260)
        val emptyResultExact = calculateExactProbabilities(emptyList(), spots = 260)

        assertTrue(emptyResultSim.isEmpty())
        assertTrue(emptyResultExact.isEmpty())

        val zeroSpotExact = calculateExactProbabilities(wser2027Categories, spots = 0)
        for (category in wser2027Categories) {
            assertTrue(zeroSpotExact.getValue(category.tickets) == 0.0)
        }
    }

    @Test
    fun `win probabilities are strictly increasing with ticket count`() = runBlocking {
        val spots = 260
        val simProbs = runSimulations(this, wser2027Categories, numSimulations = 200_000, spots = spots)
        val exactProbs = calculateExactProbabilities(wser2027Categories, spots = spots)

        val sortedByTickets = wser2027Categories.sortedBy { it.tickets }
        for (i in 0 until sortedByTickets.lastIndex) {
            val lowerTier = sortedByTickets[i].tickets
            val higherTier = sortedByTickets[i + 1].tickets

            assertTrue(simProbs.getValue(lowerTier) < simProbs.getValue(higherTier))
            assertTrue(exactProbs.getValue(lowerTier) < exactProbs.getValue(higherTier))
        }
    }

    @Test
    fun `expected number of winners across all categories equals spots`() = runBlocking {
        val spots = 260
        val simProbs = runSimulations(this, wser2027Categories, numSimulations = 200_000, spots = spots)
        val exactProbs = calculateExactProbabilities(wser2027Categories, spots = spots)

        val expectedSimWinners = wser2027Categories.sumOf { cat ->
            cat.entrants * simProbs.getValue(cat.tickets)
        }
        val expectedExactWinners = wser2027Categories.sumOf { cat ->
            cat.entrants * exactProbs.getValue(cat.tickets)
        }

        // In every simulation, exactly 260 unique entrants win, so the sum of
        // (entrants * winProbability) across categories must equal 260.0.
        assertTrue(abs(expectedSimWinners - spots) < 1e-6)
        assertTrue(abs(expectedExactWinners - spots) < 0.05)
    }

    @Test
    fun `analytical probabilities match Monte Carlo simulation within sampling tolerance`() = runBlocking {
        val spots = 260
        val simProbs = runSimulations(this, wser2027Categories, numSimulations = 500_000, spots = spots)
        val exactProbs = calculateExactProbabilities(wser2027Categories, spots = spots)

        for (category in wser2027Categories) {
            val sim = simProbs.getValue(category.tickets)
            val exact = exactProbs.getValue(category.tickets)
            assertTrue(abs(sim - exact) < 0.003)
        }
    }

    @Test
    fun `waitlist buckets sum to 50 and 75 totals and match between Monte Carlo and formula`() = runBlocking {
        val spots = 260
        val simOutcomes = runLotteryAndWaitlistSimulations(
            scope = this,
            categories = wser2027Categories,
            numSimulations = 300_000,
            spots = spots,
            waitlistBuckets = DEFAULT_WAITLIST_BUCKETS,
        )
        val exactOutcomes = calculateExactLotteryAndWaitlistProbabilities(
            categories = wser2027Categories,
            spots = spots,
            waitlistBuckets = DEFAULT_WAITLIST_BUCKETS,
        )

        val bucketsFirst50 = DEFAULT_WAITLIST_BUCKETS.filter { it.endRank <= WAITLIST_SIZE_50 }

        for (category in wser2027Categories) {
            val k = category.tickets

            // 1. Disjoint 10-runner buckets 1..50 must sum to waitlistTop50
            val sumBuckets50Sim = bucketsFirst50.sumOf { bucket ->
                simOutcomes.waitlistByBucket.getValue(bucket).getValue(k)
            }
            val sumBuckets50Exact = bucketsFirst50.sumOf { bucket ->
                exactOutcomes.waitlistByBucket.getValue(bucket).getValue(k)
            }
            assertTrue(abs(sumBuckets50Sim - simOutcomes.waitlistTop50.getValue(k)) < 1e-12)
            assertTrue(abs(sumBuckets50Exact - exactOutcomes.waitlistTop50.getValue(k)) < 1e-12)

            // 2. Disjoint buckets 1..75 must sum to waitlistTop75
            val sumBuckets75Sim = DEFAULT_WAITLIST_BUCKETS.sumOf { bucket ->
                simOutcomes.waitlistByBucket.getValue(bucket).getValue(k)
            }
            val sumBuckets75Exact = DEFAULT_WAITLIST_BUCKETS.sumOf { bucket ->
                exactOutcomes.waitlistByBucket.getValue(bucket).getValue(k)
            }
            assertTrue(abs(sumBuckets75Sim - simOutcomes.waitlistTop75.getValue(k)) < 1e-12)
            assertTrue(abs(sumBuckets75Exact - exactOutcomes.waitlistTop75.getValue(k)) < 1e-12)

            // 3. Monte Carlo and Analytical waitlist probabilities agree
            assertTrue(abs(simOutcomes.waitlistTop50.getValue(k) - exactOutcomes.waitlistTop50.getValue(k)) < 0.003)
            assertTrue(abs(simOutcomes.waitlistTop75.getValue(k) - exactOutcomes.waitlistTop75.getValue(k)) < 0.003)
        }

        // 4. Expected number of runners across all categories in each bucket equals bucket.size
        for (bucket in DEFAULT_WAITLIST_BUCKETS) {
            val expectedRunnersInBucketSim = wser2027Categories.sumOf { cat ->
                cat.entrants * simOutcomes.waitlistByBucket.getValue(bucket).getValue(cat.tickets)
            }
            val expectedRunnersInBucketExact = wser2027Categories.sumOf { cat ->
                cat.entrants * exactOutcomes.waitlistByBucket.getValue(bucket).getValue(cat.tickets)
            }
            assertTrue(abs(expectedRunnersInBucketSim - bucket.size) < 1e-6)
            assertTrue(abs(expectedRunnersInBucketExact - bucket.size) < 0.05)
        }
    }

    @Test
    fun `formatting helpers round percentages accurately`() {
        val probability = 0.422384
        assertTrue((probability * 100.0).format(3) == "42.238")
        assertTrue(probability.toPercentString(3) == "42.238")
        assertTrue(probability.toPercentString(1) == "42.2")
    }

    @Test
    fun `parseCliArgs defaults to current year and supports positional or flagged year and alternate inputs`() {
        val defaultOpts = parseCliArgs(emptyArray(), defaultYear = 2026)
        assertTrue(defaultOpts.year == 2026)
        assertTrue(!defaultOpts.forceDownload)
        assertTrue(defaultOpts.inputFile == null)
        assertTrue(defaultOpts.categoriesSpec == null)

        val positionalYearOpts = parseCliArgs(arrayOf("2027", "--force-download"))
        assertTrue(positionalYearOpts.year == 2027)
        assertTrue(positionalYearOpts.forceDownload)

        val flaggedOpts = parseCliArgs(
            arrayOf("--year=2025", "--trials", "250_000", "--categories", "1024:1,512:7")
        )
        assertTrue(flaggedOpts.year == 2025)
        assertTrue(flaggedOpts.numSimulations == 250_000)
        assertTrue(flaggedOpts.categoriesSpec == "1024:1,512:7")
    }

    @Test
    fun `createCategories supports legacy Years (Tickets) HTML format and fallback table discovery`() {
        val legacy2020Html = """
            <html>
              <body>
                <table>
                  <tr>
                    <th>Years (Tickets)</th><td>&nbsp;</td>
                    <th>Entrants</th><td>&nbsp;</td>
                    <th>Tickets</th>
                  </tr>
                  <tr>
                    <td>8 (128)</td><td>&nbsp;</td>
                    <td>9</td><td>&nbsp;</td>
                    <td>1152</td>
                  </tr>
                  <tr>
                    <td>7 (64)</td><td>&nbsp;</td>
                    <td>54</td><td>&nbsp;</td>
                    <td>3456</td>
                  </tr>
                </table>
              </body>
            </html>
        """.trimIndent()

        val categories = createCategories(Jsoup.parse(legacy2020Html))
        assertEquals(
            listOf(
                Category(tickets = 128, entrants = 9, total = 1152),
                Category(tickets = 64, entrants = 54, total = 3456),
            ),
            categories,
        )
    }

    @Test
    fun `parseCategoriesText and parseCategoriesSpec load alternate category inputs and report clear errors`() {
        val csvInput = """
            # Sample WSER ticket distribution
            Tickets,Entrants
            1024, 1
            512, 7, 3584
            256: 46
        """.trimIndent()

        val fromCsv = parseCategoriesText(csvInput)
        assertEquals(
            listOf(
                Category(tickets = 1024, entrants = 1, total = 1024),
                Category(tickets = 512, entrants = 7, total = 3584),
                Category(tickets = 256, entrants = 46, total = 11776),
            ),
            fromCsv,
        )

        val fromSpec = parseCategoriesSpec("1024:1,512:7;256=46")
        assertEquals(fromCsv, fromSpec)

        val tempHtml = createTempFile(prefix = "empty-wser-", suffix = ".html")
        try {
            tempHtml.writeText("<html><head><title>404 Not Found</title></head><body>No table</body></html>")
            val error = runCatching { loadCategoriesFromFile(tempHtml) }.exceptionOrNull()
            assertTrue(error is LotteryDataException)
            assertTrue(error.message!!.contains("Failed to find a valid ticket counts table"))
            assertTrue(error.message!!.contains("404 Not Found"))
        } finally {
            tempHtml.deleteIfExists()
        }
    }

    @Test
    fun `writeMarkdownReport generates tables and preserves daily snapshot history across runs`() {
        val doc = Jsoup.parse("<html><body><h4>Last Updated: 11-09-2026 08:15:00 AM</h4></body></html>")
        assertEquals("11-09-2026 08:15:00 AM", extractLastUpdated(doc))

        val options = parseCliArgs(arrayOf("2027", "--markdown-out", "RESULTS_2027.md"))
        assertEquals("RESULTS_2027.md", options.markdownOut?.toString())

        val outcomes = calculateExactLotteryAndWaitlistProbabilities(
            categories = wser2027Categories,
            spots = DEFAULT_LOTTERY_SPOTS,
            waitlistBuckets = DEFAULT_WAITLIST_BUCKETS,
        )

        val tempMd = createTempFile(prefix = "wser-report-", suffix = ".md")
        try {
            writeMarkdownReport(
                outputPath = tempMd,
                options = options,
                loadedData = LoadedLotteryData(wser2027Categories, "11-08-2026 09:00:00 AM"),
                simulatedOutcomes = outcomes,
                formulaOutcomes = outcomes,
                snapshotDateUtc = "2026-11-08",
            )
            writeMarkdownReport(
                outputPath = tempMd,
                options = options,
                loadedData = LoadedLotteryData(wser2027Categories, "11-09-2026 09:00:00 AM"),
                simulatedOutcomes = outcomes,
                formulaOutcomes = outcomes,
                snapshotDateUtc = "2026-11-09",
            )

            val content = tempMd.readText()
            assertTrue(content.contains("## 1. Main Lottery & Cumulative Waitlist Probabilities"))
            assertTrue(content.contains("## 2. 10-Runner Waitlist Buckets (Monte Carlo)"))
            assertTrue(content.contains("## Daily Snapshot History"))
            assertTrue(content.contains("| 2026-11-08 | 11-08-2026 09:00:00 AM |"))
            assertTrue(content.contains("| 2026-11-09 | 11-09-2026 09:00:00 AM |"))
        } finally {
            tempMd.deleteIfExists()
        }
    }
}

