package com.sphericalchickens

import com.sphericalchickens.gem2.calculateExactLotteryAndWaitlistProbabilities
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate
import java.time.Year
import java.time.ZoneOffset
import java.util.concurrent.ThreadLocalRandom
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.system.exitProcess
import kotlin.time.measureTimedValue

/**
 * The default Western States Endurance Run (WSER) lottery year, defaulting to the current calendar year.
 */
val YEAR: Int = Year.now().value

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
const val DEFAULT_NUM_SIMULATIONS = 1_000_000

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
 * 5-slot waitlist cumulative step thresholds (`5, 10, 15, ..., 75`).
 */
val DEFAULT_WAITLIST_5_SLOT_STEPS: List<Int> = (5..WAITLIST_SIZE_75 step 5).toList()

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
    val total: Int = tickets * entrants,
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
 * cumulative waitlist thresholds (`1..10`, `1..25`, `1..50`, `1..75`), and 5-slot cumulative
 * waitlist milestones (`5, 10, 15, ..., 75`) for each ticket category.
 *
 * @property mainLottery Map of (`tickets -> probability of winning one of the main lottery spots`).
 * @property waitlistByBucket Map of (`WaitlistBucket -> (tickets -> probability of landing in that bucket)`).
 * @property waitlistTop10 Map of (`tickets -> probability of landing in waitlist positions 1..10`).
 * @property waitlistTop25 Map of (`tickets -> probability of landing in waitlist positions 1..25`).
 * @property waitlistTop50 Map of (`tickets -> probability of landing in waitlist positions 1..50`).
 * @property waitlistTop75 Map of (`tickets -> probability of landing in waitlist positions 1..75`).
 * @property waitlistCumulativeBy5 Map of (`maxWaitlistRank (5, 10, ..., 75) -> (tickets -> probability of landing in waitlist 1..maxWaitlistRank)`).
 */
data class LotteryOutcomeProbabilities(
    val mainLottery: Map<Int, Double>,
    val waitlistByBucket: Map<WaitlistBucket, Map<Int, Double>>,
    val waitlistTop10: Map<Int, Double>,
    val waitlistTop25: Map<Int, Double>,
    val waitlistTop50: Map<Int, Double>,
    val waitlistTop75: Map<Int, Double>,
    val waitlistCumulativeBy5: Map<Int, Map<Int, Double>> = emptyMap(),
)

/**
 * Parsed command-line configuration for the WSER Lottery & Waitlist calculator.
 *
 * @property year Target lottery year (defaults to the current calendar year [YEAR]).
 * @property forceDownload Whether to bypass the local HTML cache and fetch fresh data from `wser.org`.
 * @property inputFile Optional path to a local CSV/TSV/whitespace text file or HTML file containing ticket counts.
 * @property categoriesSpec Optional inline category string (e.g. `"1024:1,512:7,256:46"`).
 * @property markdownOut Optional file path to write a formatted GitHub Markdown report (e.g. `RESULTS_2027.md`).
 * @property htmlOut Optional file path to write an interactive static HTML report (e.g. `docs/index.html`).
 * @property numSimulations Number of Monte Carlo trials to run.
 * @property showHelp Whether `--help` / `-h` was requested.
 */
data class CliOptions(
    val year: Int = YEAR,
    val forceDownload: Boolean = false,
    val inputFile: Path? = null,
    val categoriesSpec: String? = null,
    val markdownOut: Path? = null,
    val htmlOut: Path? = null,
    val numSimulations: Int = DEFAULT_NUM_SIMULATIONS,
    val showHelp: Boolean = false,
) {
    /**
     * Human-readable label describing the data source being analyzed.
     */
    val sourceLabel: String
        get() = when {
            categoriesSpec != null -> "Inline CLI Categories"
            inputFile != null -> "File ($inputFile)"
            else -> "$year WSER Lottery"
        }
}

/**
 * Container for loaded lottery categories and any upstream metadata scraped from `wser.org`.
 *
 * @property categories The parsed ticket tiers.
 * @property lastUpdated Optional `"Last Updated"` timestamp extracted from the WSER HTML page.
 */
data class LoadedLotteryData(
    val categories: List<Category>,
    val lastUpdated: String? = null,
)

/**
 * Exception thrown when WSER lottery ticket count data cannot be fetched or parsed.
 *
 * Carries an actionable diagnostic message explaining why the table could not be loaded
 * and how the user can either target a different year or supply data via `--file` or `--categories`.
 */
class LotteryDataException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Parses command-line arguments into a validated [CliOptions] instance.
 *
 * Supported arguments:
 * - `<year>` or `--year <year>` / `--year=<year>` / `-y <year>`: Target WSER lottery year (default: current year).
 * - `--force-download` / `-f`: Re-download the HTML page from `wser.org` even if cached locally.
 * - `--file <path>` / `--file=<path>` / `--input <path>`: Load ticket counts from a local CSV/TSV/text or HTML file.
 * - `--categories <spec>` / `--categories=<spec>`: Supply inline `tickets:entrants` pairs (e.g. `"1024:1,512:7,..."`).
 * - `--markdown-out <path>` / `--markdown-out=<path>`: Write a formatted GitHub Markdown report to `<path>`.
 * - `--html-out <path>` / `--html-out=<path>`: Write a static HTML dashboard with per-bucket cumulative graphs to `<path>`.
 * - `--trials <n>` / `--trials=<n>` / `--simulations <n>`: Number of Monte Carlo simulation runs.
 * - `--help` / `-h`: Print CLI usage instructions.
 *
 * @throws IllegalArgumentException If an unrecognized flag or invalid numeric argument is provided.
 */
fun parseCliArgs(args: Array<String>, defaultYear: Int = YEAR): CliOptions {
    var year = defaultYear
    var forceDownload = false
    var inputFile: Path? = null
    var categoriesSpec: String? = null
    var markdownOut: Path? = null
    var htmlOut: Path? = null
    var numSimulations = DEFAULT_NUM_SIMULATIONS
    var showHelp = false

    var i = 0
    while (i < args.size) {
        val arg = args[i].trim()
        when {
            arg.isEmpty() -> Unit
            arg == "--help" || arg == "-h" -> showHelp = true
            arg == "--force-download" || arg == "-f" -> forceDownload = true

            arg == "--year" || arg == "-y" -> {
                val next = args.getOrNull(++i)
                    ?: throw IllegalArgumentException("Missing year value after '$arg'. Example: --year 2027")
                year = parseYearValue(next)
            }
            arg.startsWith("--year=") -> {
                year = parseYearValue(arg.substringAfter("--year="))
            }

            arg == "--file" || arg == "--input" -> {
                val next = args.getOrNull(++i)
                    ?: throw IllegalArgumentException("Missing file path after '$arg'. Example: --file counts.csv")
                inputFile = Path(next)
            }
            arg.startsWith("--file=") -> inputFile = Path(arg.substringAfter("--file="))
            arg.startsWith("--input=") -> inputFile = Path(arg.substringAfter("--input="))

            arg == "--categories" || arg == "--data" -> {
                val next = args.getOrNull(++i)
                    ?: throw IllegalArgumentException(
                        "Missing category specification after '$arg'. Example: --categories 1024:1,512:7,256:46"
                    )
                categoriesSpec = next
            }
            arg.startsWith("--categories=") -> categoriesSpec = arg.substringAfter("--categories=")
            arg.startsWith("--data=") -> categoriesSpec = arg.substringAfter("--data=")

            arg == "--markdown-out" || arg == "--output" -> {
                val next = args.getOrNull(++i)
                    ?: throw IllegalArgumentException(
                        "Missing file path after '$arg'. Example: --markdown-out RESULTS_2027.md"
                    )
                markdownOut = Path(next)
            }
            arg.startsWith("--markdown-out=") -> markdownOut = Path(arg.substringAfter("--markdown-out="))
            arg.startsWith("--output=") -> markdownOut = Path(arg.substringAfter("--output="))

            arg == "--html-out" -> {
                val next = args.getOrNull(++i)
                    ?: throw IllegalArgumentException(
                        "Missing file path after '$arg'. Example: --html-out docs/index.html"
                    )
                htmlOut = Path(next)
            }
            arg.startsWith("--html-out=") -> htmlOut = Path(arg.substringAfter("--html-out="))

            arg == "--trials" || arg == "--simulations" -> {
                val next = args.getOrNull(++i)
                    ?: throw IllegalArgumentException("Missing integer value after '$arg'. Example: --trials 1000000")
                numSimulations = parsePositiveInt(next, "simulation trials")
            }
            arg.startsWith("--trials=") -> {
                numSimulations = parsePositiveInt(arg.substringAfter("--trials="), "simulation trials")
            }
            arg.startsWith("--simulations=") -> {
                numSimulations = parsePositiveInt(arg.substringAfter("--simulations="), "simulation trials")
            }

            // Allow a bare 4-digit year as a convenient positional argument (e.g., `./gradlew run --args="2027"`)
            arg.matches(Regex("""^\d{4}$""")) -> {
                year = parseYearValue(arg)
            }

            else -> {
                throw IllegalArgumentException(
                    "Unrecognized argument: '$arg'. Run with --help to see supported options."
                )
            }
        }
        i++
    }

    return CliOptions(
        year = year,
        forceDownload = forceDownload,
        inputFile = inputFile,
        categoriesSpec = categoriesSpec,
        markdownOut = markdownOut,
        htmlOut = htmlOut,
        numSimulations = numSimulations,
        showHelp = showHelp,
    )
}

private fun parseYearValue(raw: String): Int {
    val parsed = raw.trim().toIntOrNull()
    require(parsed != null && parsed in 1974..2100) {
        "Invalid lottery year '$raw'. Expected a 4-digit year between 1974 and 2100 (e.g., 2027)."
    }
    return parsed
}

private fun parsePositiveInt(raw: String, name: String): Int {
    val parsed = raw.replace(",", "").replace("_", "").trim().toIntOrNull()
    require(parsed != null && parsed > 0) {
        "Invalid $name value '$raw'. Expected a positive integer."
    }
    return parsed
}

/**
 * Returns formatted CLI usage text describing how to target different years or provide custom data.
 */
fun cliUsageText(): String = """
    Usage: ./gradlew run [--args="<options>"]

    Options:
      <year>, -y, --year <year>         Target WSER lottery year (default: current year, $YEAR).
                                        Example: ./gradlew run --args="2027"
      -f, --force-download              Bypass local cache (~/tmp/lottery<year>.html) and re-download from wser.org.
      --file, --input <path>            Load ticket counts from a local CSV/TSV/whitespace file or HTML file.
                                        Format per line: <tickets>,<entrants>[,<total>]
      --categories <spec>               Pass ticket counts inline on the command line as comma-separated pairs.
                                        Example: --categories "1024:1,512:7,256:46,128:94,64:175,32:342,16:511,8:778,4:998,2:1435,1:1717"
      --markdown-out <path>             Write a formatted GitHub Markdown report (with daily history) to <path>.
      --html-out <path>                 Write a static HTML report with per-bucket cumulative graphs to <path>.
      --trials, --simulations <count>   Number of Monte Carlo trials to run (default: $DEFAULT_NUM_SIMULATIONS).
      -h, --help                        Show this help message and exit.
""".trimIndent()

/**
 * Entry point for the WSER Lottery & Waitlist probability calculator.
 *
 * Measures and reports execution time for each distinct stage:
 * 1. **Data Loading & Parsing** ([loadLotteryData])
 * 2. **Parallel Monte Carlo Simulation** ([runLotteryAndWaitlistSimulations])
 * 3. **Analytical Formula Solution** ([calculateExactLotteryAndWaitlistProbabilities])
 *
 * @param args Command-line arguments parsed by [parseCliArgs].
 */
fun main(args: Array<String>) {
    val options = try {
        parseCliArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message}\n")
        System.err.println(cliUsageText())
        exitProcess(2)
    }

    if (options.showHelp) {
        println(cliUsageText())
        return
    }

    try {
        val (_, totalDuration) = measureTimedValue {
            runBlocking {
                // --- Phase 1: Load and parse WSER lottery data ---
                val (loadedData, loadDuration) = measureTimedValue {
                    loadLotteryData(options)
                }
                val categories = loadedData.categories

                println("=== Part 1: Category Data (${options.sourceLabel}) ===")
                loadedData.lastUpdated?.let { println("WSER Page Last Updated: $it") }
                println(categories.joinToString("\n"))
                println("Data Load & Parse Time: $loadDuration\n")

                // --- Phase 2: Parallel Monte Carlo Simulation (Main Lottery + Waitlist) ---
                val (simulatedOutcomes, simDuration) = measureTimedValue {
                    withContext(Dispatchers.Default) {
                        runLotteryAndWaitlistSimulations(
                            scope = this,
                            categories = categories,
                            numSimulations = options.numSimulations,
                            spots = DEFAULT_LOTTERY_SPOTS,
                            waitlistBuckets = DEFAULT_WAITLIST_BUCKETS,
                        )
                    }
                }

                println("=== Part 2A: Monte Carlo Cumulative Waitlist Table (${options.numSimulations} runs) ===")
                printCumulativeWaitlistTable(categories, simulatedOutcomes)
                println()

                println("=== Part 2B: Monte Carlo 10-Runner Waitlist Buckets (${options.numSimulations} runs) ===")
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

                options.markdownOut?.let { outPath ->
                    writeMarkdownReport(
                        outputPath = outPath,
                        options = options,
                        loadedData = loadedData,
                        simulatedOutcomes = simulatedOutcomes,
                        formulaOutcomes = formulaOutcomes,
                    )
                    println("Markdown Report Written: $outPath")
                }

                options.htmlOut?.let { outPath ->
                    writeHtmlReport(
                        outputPath = outPath,
                        options = options,
                        loadedData = loadedData,
                        simulatedOutcomes = simulatedOutcomes,
                        formulaOutcomes = formulaOutcomes,
                    )
                    println("HTML Report Written:     $outPath")
                }
            }
        }

        println("Total Execution Time:    $totalDuration")
    } catch (e: LotteryDataException) {
        System.err.println("\nERROR: ${e.message}")
        exitProcess(1)
    }
}

/**
 * Loads WSER ticket count categories according to [options].
 */
suspend fun loadCategories(options: CliOptions): List<Category> = loadLotteryData(options).categories

/**
 * Loads WSER ticket count categories and upstream `"Last Updated"` metadata according to [options]:
 * 1. From an inline `--categories` CLI specification if provided,
 * 2. From a local `--file` / `--input` path (CSV/TSV/whitespace text or HTML) if provided, or
 * 3. From `https://www.wser.org/lottery<year>.html` (using the local cache at `~/tmp/lottery<year>.html`).
 *
 * @throws LotteryDataException With a clear explanation and fallback instructions if the data
 *   cannot be retrieved or if no valid ticket counts table is found.
 */
suspend fun loadLotteryData(options: CliOptions): LoadedLotteryData {
    options.categoriesSpec?.let { spec ->
        return LoadedLotteryData(categories = parseCategoriesSpec(spec))
    }

    options.inputFile?.let { filePath ->
        return LoadedLotteryData(categories = loadCategoriesFromFile(filePath))
    }

    val year = options.year
    val url = "https://www.wser.org/lottery$year.html"
    val cachePath = Path(System.getProperty("user.home"), "tmp", "lottery$year.html")
    val usedCache = !options.forceDownload && cachePath.exists()

    val doc = getDocument(url, cachePath, forceDownload = options.forceDownload)
    val categories = createCategories(doc)
    if (categories.isEmpty()) {
        val pageTitle = doc.title().ifBlank { "<untitled page>" }
        val cacheHint = if (usedCache) {
            "\n  - Note: Loaded from local cache file '$cachePath'. If this cached file is stale or incomplete, " +
                "re-run with '--force-download'."
        } else {
            ""
        }
        throw LotteryDataException(
            buildString {
                appendLine("Failed to find a valid ticket counts table for the $year WSER lottery.")
                appendLine("  - Source URL:  $url")
                appendLine("  - Local Cache: $cachePath (usedCache=$usedCache)")
                appendLine("  - Page Title:  \"$pageTitle\"$cacheHint")
                appendLine()
                appendLine("Why this happens:")
                appendLine("  The $year lottery page on wser.org may not have published its ticket summary table yet,")
                appendLine("  or the page structure for $year does not include a 'Tickets | Entrants | Total' summary table.")
                appendLine()
                appendLine("How to proceed:")
                appendLine("  1. Target a different lottery year:")
                appendLine("       ./gradlew run --args=\"2027\"")
                appendLine("  2. Supply ticket counts from a local CSV/TSV/text file (2 columns: tickets,entrants):")
                appendLine("       ./gradlew run --args=\"--file path/to/tickets.csv\"")
                appendLine("  3. Supply ticket counts directly on the command line:")
                appendLine("       ./gradlew run --args=\"--categories 1024:1,512:7,256:46,128:94,64:175,32:342,16:511,8:778,4:998,2:1435,1:1717\"")
            }.trimEnd()
        )
    }
    return LoadedLotteryData(
        categories = categories,
        lastUpdated = extractLastUpdated(doc),
    )
}

/**
 * Loads categories from a local file ([filePath]), supporting both HTML files and plain-text
 * CSV / TSV / whitespace / colon-delimited ticket count files.
 *
 * @throws LotteryDataException If the file does not exist, cannot be read, or contains no valid categories.
 */
fun loadCategoriesFromFile(filePath: Path): List<Category> {
    if (!filePath.exists()) {
        throw LotteryDataException(
            "Input file not found: '$filePath'. Verify the path passed to --file / --input."
        )
    }

    val rawContent = try {
        filePath.readText()
    } catch (e: IOException) {
        throw LotteryDataException("Failed to read input file '$filePath': ${e.message}", e)
    }

    val fileNameLower = filePath.fileName?.toString()?.lowercase().orEmpty()
    val looksLikeHtml = fileNameLower.endsWith(".html") ||
        fileNameLower.endsWith(".htm") ||
        rawContent.trimStart().startsWith("<")

    if (looksLikeHtml) {
        val doc = Jsoup.parse(rawContent)
        val categories = createCategories(doc)
        if (categories.isEmpty()) {
            throw LotteryDataException(
                "Failed to find a valid ticket counts table in HTML file '$filePath' (title: \"${doc.title()}\").\n" +
                    "You can also provide a simple text/CSV file with 'tickets,entrants' per line."
            )
        }
        return categories
    }

    return parseCategoriesText(rawContent, sourceDescription = filePath.toString())
}

/**
 * Parses a multi-line text / CSV / TSV representation of lottery categories.
 *
 * Supported line formats (commas inside numbers are only allowed when tab- or colon-separated,
 * or standard unquoted CSV `tickets,entrants[,total]`):
 * - `1024, 1, 1024` or `1024, 1`
 * - `1024:1`
 * - `1024   1   1024` (whitespace/tab-separated)
 *
 * Blank lines, lines starting with `#` or `//`, and header lines (such as `Tickets,Entrants,Total`)
 * are automatically ignored.
 *
 * @throws LotteryDataException If a numeric line is malformed or if no categories are found.
 */
fun parseCategoriesText(content: String, sourceDescription: String = "input"): List<Category> {
    val categories = mutableListOf<Category>()

    content.lineSequence().forEachIndexed { index, rawLine ->
        val lineNum = index + 1
        val line = rawLine.substringBefore('#').trim()
        if (line.isEmpty() || line.startsWith("//")) return@forEachIndexed

        val tokens = line.split(Regex("""[,:\t\s]+""")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return@forEachIndexed

        // Skip header rows like "Tickets,Entrants,Total"
        if (tokens[0].any { it.isLetter() }) return@forEachIndexed

        if (tokens.size < 2) {
            throw LotteryDataException(
                "Malformed category entry at $sourceDescription:$lineNum ('$rawLine'). " +
                    "Expected '<tickets>,<entrants>' (e.g., '1024,1')."
            )
        }

        val tickets = tokens[0].replace("_", "").toIntOrNull()
        val entrants = tokens[1].replace("_", "").toIntOrNull()
        val total = if (tokens.size >= 3) {
            tokens[2].replace("_", "").toIntOrNull()
        } else if (tickets != null && entrants != null) {
            tickets * entrants
        } else {
            null
        }

        if (tickets == null || entrants == null || total == null || tickets <= 0 || entrants <= 0) {
            throw LotteryDataException(
                "Invalid category numbers at $sourceDescription:$lineNum ('$rawLine'). " +
                    "Both tickets and entrants must be positive integers."
            )
        }

        categories.add(Category(tickets = tickets, entrants = entrants, total = total))
    }

    if (categories.isEmpty()) {
        throw LotteryDataException(
            "No valid ticket categories found in $sourceDescription. " +
                "Expected lines of '<tickets>,<entrants>' (e.g., '1024,1')."
        )
    }

    return categories
}

/**
 * Parses an inline CLI category specification string such as:
 * `"1024:1,512:7,256:46,128:94,64:175,32:342,16:511,8:778,4:998,2:1435,1:1717"`.
 *
 * Pairs may be separated by commas or semicolons, and key/value within each pair may be
 * separated by `:` or `=`.
 *
 * @throws LotteryDataException If the specification is empty or contains malformed pairs.
 */
fun parseCategoriesSpec(spec: String): List<Category> {
    val entries = spec.split(Regex("""[,;]+""")).map { it.trim() }.filter { it.isNotEmpty() }
    if (entries.isEmpty()) {
        throw LotteryDataException(
            "Empty --categories specification. Expected 'tickets:entrants' pairs such as '1024:1,512:7,256:46'."
        )
    }

    return entries.map { entry ->
        val parts = entry.split(Regex("""[:=]""")).map { it.trim().replace("_", "") }
        if (parts.size !in 2..3) {
            throw LotteryDataException(
                "Malformed category pair '$entry' in --categories. Expected '<tickets>:<entrants>' (e.g., '1024:1')."
            )
        }
        val tickets = parts[0].toIntOrNull()
        val entrants = parts[1].toIntOrNull()
        if (tickets == null || entrants == null || tickets <= 0 || entrants <= 0) {
            throw LotteryDataException(
                "Invalid numbers in category pair '$entry'. Both tickets and entrants must be positive integers."
            )
        }
        val total = if (parts.size == 3) {
            parts[2].toIntOrNull() ?: throw LotteryDataException("Invalid total in category pair '$entry'.")
        } else {
            tickets * entrants
        }
        Category(tickets = tickets, entrants = entrants, total = total)
    }
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
 * @throws LotteryDataException If the HTTP request fails or returns a non-2xx status code.
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
 * Downloads the lottery HTML from [url] and persists it to [localCachePath] only if the server
 * returns a `2xx` HTTP response.
 *
 * Checking `response.status.isSuccess()` prevents caching `404 Not Found` pages when querying
 * a future lottery year whose entrant page has not been published yet.
 */
private suspend fun downloadAndCacheHtml(url: String, localCachePath: Path): String {
    println("Downloading lottery data from $url...")
    return try {
        HttpClient(CIO).use { client ->
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                throw LotteryDataException(
                    buildString {
                        appendLine("HTTP ${response.status.value} (${response.status.description}) when fetching $url.")
                        appendLine("The lottery page for this year may not be published on wser.org yet.")
                        appendLine()
                        appendLine("How to proceed:")
                        appendLine("  1. Target a published lottery year (e.g., ./gradlew run --args=\"2027\")")
                        appendLine("  2. Provide a local CSV/TSV/HTML file: ./gradlew run --args=\"--file path/to/tickets.csv\"")
                        appendLine("  3. Provide categories inline: ./gradlew run --args=\"--categories 1024:1,512:7,...\"")
                    }.trimEnd()
                )
            }
            val content = response.bodyAsText()
            localCachePath.parent?.toFile()?.mkdirs()
            localCachePath.writeText(content)
            content
        }
    } catch (e: LotteryDataException) {
        throw e
    } catch (e: Exception) {
        throw LotteryDataException(
            "Network error while fetching lottery data from $url: ${e.message}\n" +
                "You can supply offline data using --file <path> or --categories <spec>.",
            e,
        )
    }
}

private val PARENTHESIZED_NUMBER_REGEX = Regex("""\(\s*([\d,]+)\s*\)""")

/**
 * Extracts the ticket category summary table from the parsed WSER HTML [Document].
 *
 * Supports both:
 * 1. The standard WSER nested table layout (`Tickets | Entrants | Total`), and
 * 2. Older WSER lottery pages (such as 2019–2020) where the first column header is
 *    `Years (Tickets)` and cells are formatted as `"8 (128)"`.
 *
 * If the primary XPath does not match, falls back to locating any `<table>` whose header
 * row contains both `"Tickets"` and `"Entrants"`.
 *
 * @param doc The parsed WSER lottery HTML document.
 * @return A list of [Category] records in document order, or an empty list if no valid table exists.
 */
fun createCategories(doc: Document): List<Category> {
    val primaryXpath = "/html/body/div/table/tbody/tr[2]/td/table/tbody/tr[2]/td/table/tbody"
    val primaryRows = doc.selectXpath(primaryXpath).select("tr")
    val fromPrimary = parseCategoryRows(primaryRows.drop(1))
    if (fromPrimary.isNotEmpty()) {
        return fromPrimary
    }

    // Fallback: locate any table whose header row mentions both "Tickets" and "Entrants"
    for (table in doc.select("table")) {
        val rows = table.select("> tbody > tr, > tr")
        if (rows.size < 2) continue
        val headerText = rows.first()!!.select("th, td").joinToString(" ") { it.text().lowercase() }
        if ("tickets" in headerText && "entrants" in headerText) {
            val parsed = parseCategoryRows(rows.drop(1))
            if (parsed.isNotEmpty()) {
                return parsed
            }
        }
    }

    return emptyList()
}

private fun parseCategoryRows(rows: List<Element>): List<Category> {
    return rows.mapNotNull { row ->
        val tds = row.select("td")
        val dataCells = when {
            tds.size >= 5 -> tds.filterIndexed { index, _ -> index % 2 == 0 }
            tds.size == 3 -> tds
            else -> emptyList()
        }

        if (dataCells.size >= 3) {
            val tickets = parseTicketCellCount(dataCells[0].text())
            val entrants = parsePlainCount(dataCells[1].text())
            val total = parsePlainCount(dataCells[2].text())
            if (tickets != null && entrants != null && total != null && tickets > 0 && entrants > 0) {
                Category(tickets = tickets, entrants = entrants, total = total)
            } else {
                null
            }
        } else {
            null
        }
    }
}

/**
 * Parses a ticket count cell, supporting both plain numbers (`"1,024"`) and legacy
 * `Years (Tickets)` values (`"8 (128)"` -> `128`).
 */
private fun parseTicketCellCount(rawText: String): Int? {
    parsePlainCount(rawText)?.let { return it }
    val match = PARENTHESIZED_NUMBER_REGEX.find(rawText) ?: return null
    return parsePlainCount(match.groupValues[1])
}

private fun parsePlainCount(rawText: String): Int? =
    rawText.replace(",", "").replace("\u00a0", "").trim().toIntOrNull()

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
    val waitlistCumBy5 = DEFAULT_WAITLIST_5_SLOT_STEPS.associateWith { stepEnd ->
        probabilitiesForDrawRange(spots, spots + stepEnd)
    }

    return LotteryOutcomeProbabilities(
        mainLottery = mainLotteryProbs,
        waitlistByBucket = bucketProbs,
        waitlistTop10 = waitlist10Probs,
        waitlistTop25 = waitlist25Probs,
        waitlistTop50 = waitlist50Probs,
        waitlistTop75 = waitlist75Probs,
        waitlistCumulativeBy5 = waitlistCumBy5,
    )
}

private val LAST_UPDATED_REGEX = Regex("""Last\s+Updated:\s*([^\r\n<]+)""", RegexOption.IGNORE_CASE)

/**
 * Extracts the `"Last Updated: ..."` timestamp string from a WSER lottery HTML [Document], if present.
 *
 * Searches raw HTML so the closing tag (`</H4>`) cleanly terminates the captured timestamp.
 */
fun extractLastUpdated(doc: Document): String? {
    val match = LAST_UPDATED_REGEX.find(doc.html()) ?: return null
    return match.groupValues[1].replace("&nbsp;", " ").trim().takeIf { it.isNotEmpty() }
}

private const val DAILY_HISTORY_HEADER = "## Daily Snapshot History"

/**
 * Writes a GitHub-flavored Markdown report of the lottery and waitlist probabilities to [outputPath].
 *
 * If [outputPath] already exists and contains a [DAILY_HISTORY_HEADER] table, previous daily snapshot
 * rows are preserved and today's row (keyed by UTC date `YYYY-MM-DD`) is added or updated in-place.
 */
fun writeMarkdownReport(
    outputPath: Path,
    options: CliOptions,
    loadedData: LoadedLotteryData,
    simulatedOutcomes: LotteryOutcomeProbabilities,
    formulaOutcomes: LotteryOutcomeProbabilities,
    snapshotDateUtc: String = LocalDate.now(ZoneOffset.UTC).toString(),
) {
    val categories = loadedData.categories.sortedBy { it.tickets }
    val totalEntrants = categories.sumOf { it.entrants }
    val totalTickets = categories.sumOf { it.total }
    val lastUpdatedLabel = loadedData.lastUpdated ?: "N/A"

    // Preserve any existing history rows from prior days in the Markdown file
    val existingHistoryRows = LinkedHashMap<String, String>()
    if (outputPath.exists()) {
        runCatching { outputPath.readText() }.getOrNull()?.let { prevContent ->
            val historySection = prevContent.substringAfter(DAILY_HISTORY_HEADER, missingDelimiterValue = "")
            historySection.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("| 20") }
                .forEach { row ->
                    val dateKey = row.removePrefix("|").substringBefore("|").trim()
                    if (dateKey.isNotEmpty()) {
                        existingHistoryRows[dateKey] = row
                    }
                }
        }
    }

    fun pctForTier(tickets: Int): String =
        formulaOutcomes.mainLottery[tickets]?.let { "${(it * 100.0).format(2)}%" } ?: "—"

    val todayRow = "| %s | %s | %,d | %,d | %s | %s | %s | %s | %s | %s | %s |".format(
        snapshotDateUtc,
        lastUpdatedLabel,
        totalEntrants,
        totalTickets,
        pctForTier(1),
        pctForTier(16),
        pctForTier(64),
        pctForTier(128),
        pctForTier(256),
        pctForTier(512),
        pctForTier(1024),
    )
    existingHistoryRows[snapshotDateUtc] = todayRow

    val markdown = buildString {
        appendLine("# ${options.sourceLabel} — Lottery & Waitlist Probabilities")
        appendLine()
        appendLine("- **Snapshot Date (UTC)**: `$snapshotDateUtc`")
        appendLine("- **WSER Page Last Updated**: `$lastUpdatedLabel`")
        appendLine("- **Total Entrants**: **%,d**".format(totalEntrants))
        appendLine("- **Total Tickets in Hat**: **%,d**".format(totalTickets))
        appendLine("- **Main Lottery Spots**: **$DEFAULT_LOTTERY_SPOTS**")
        appendLine("- **Monte Carlo Trials**: **%,d**".format(options.numSimulations))
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("## 1. Main Lottery & Cumulative Waitlist Probabilities")
        appendLine()
        appendLine("| Tickets | Entrants | Total Tickets | Expected Winners | Main Lottery (MC / Formula) | WL First 10 (`1–10`) | WL Up to 25th (`1–25`) | WL Up to 50th (`1–50`) | WL Up to 75th (`1–75`) |")
        appendLine("| ---: | ---: | ---: | ---: | :---: | ---: | ---: | ---: | ---: |")
        for (category in categories) {
            val t = category.tickets
            val mainMc = (simulatedOutcomes.mainLottery.getValue(t) * 100.0).format(3)
            val mainFm = (formulaOutcomes.mainLottery.getValue(t) * 100.0).format(3)
            val expectedWinners = (category.entrants * formulaOutcomes.mainLottery.getValue(t)).format(1)
            val wl10 = (simulatedOutcomes.waitlistTop10.getValue(t) * 100.0).format(3)
            val wl25 = (simulatedOutcomes.waitlistTop25.getValue(t) * 100.0).format(3)
            val wl50 = (simulatedOutcomes.waitlistTop50.getValue(t) * 100.0).format(3)
            val wl75 = (simulatedOutcomes.waitlistTop75.getValue(t) * 100.0).format(3)
            appendLine(
                "| **%,d** | %,d | %,d | %s | **%s%%** / %s%% | %s%% | %s%% | %s%% | %s%% |".format(
                    t,
                    category.entrants,
                    category.total,
                    expectedWinners,
                    mainMc,
                    mainFm,
                    wl10,
                    wl25,
                    wl50,
                    wl75,
                )
            )
        }
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("## 2. 10-Runner Waitlist Buckets (Monte Carlo)")
        appendLine()
        val buckets = simulatedOutcomes.waitlistByBucket.keys.toList()
        val bucketHeaderCols = buckets.joinToString(" | ") { "WL `${it.label}`" }
        val bucketAlignCols = buckets.joinToString(" | ") { "---:" }
        appendLine("| Tickets | Entrants | $bucketHeaderCols |")
        appendLine("| ---: | ---: | $bucketAlignCols |")
        for (category in categories) {
            val t = category.tickets
            val rowCols = buckets.joinToString(" | ") { bucket ->
                "${(simulatedOutcomes.waitlistByBucket.getValue(bucket).getValue(t) * 100.0).format(3)}%"
            }
            appendLine("| **%,d** | %,d | %s |".format(t, category.entrants, rowCols))
        }
        appendLine()
        appendLine("---")
        appendLine()
        appendLine(DAILY_HISTORY_HEADER)
        appendLine()
        appendLine("Tracks daily changes in entrant counts and main lottery selection probabilities leading up to registration closing:")
        appendLine()
        appendLine("| Date (UTC) | WSER Last Updated | Entrants | Total Tickets | 1 Tkt | 16 Tkt | 64 Tkt | 128 Tkt | 256 Tkt | 512 Tkt | 1024 Tkt |")
        appendLine("| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        for (row in existingHistoryRows.values) {
            appendLine(row)
        }
    }

    outputPath.parent?.toFile()?.mkdirs()
    outputPath.writeText(markdown)
}

/**
 * Formats a [Double] to a fixed number of decimal [digits].
 *
 * @param digits Number of digits after the decimal point.
 * @return Formatted numeric string.
 */
fun Double.format(digits: Int): String = "%.${digits}f".format(this)