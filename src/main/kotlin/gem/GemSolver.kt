package com.sphericalchickens.gem

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.io.path.Path
import kotlin.random.Random
import kotlin.system.measureTimeMillis

// --- Domain Models ---

/**
 * Represents a single category of lottery entrants.
 * @property tickets Number of tickets each entrant in this category holds. (Primary key for results)
 * @property entrants Number of unique athletes in this category.
 * @property total Total number of tickets contributed by this category.
 */
data class Category(
    val tickets: Int,
    val entrants: Int,
    val total: Int,
)

/**
 * Represents an athlete in the lottery pool.
 * @property id A unique ID for the athlete (position in the consolidated list).
 * @property categoryTickets The number of tickets this athlete holds (e.g., 2 for 2-year qualifier).
 */
data class Athlete(
    val id: Int,
    val categoryTickets: Int
)

// --- Utility Extensions ---

/**
 * Helper function to format a Double as a percentage string with specified decimal places.
 */
fun Double.toPercentString(digits: Int = 3): String {
    val percentage = this * 100.0
    return "%.${digits}f".format(percentage)
}

// --- Main Execution ---

fun main() {
    val t = measureTimeMillis {
        runBlocking {
            val url = "https://www.wser.org/lottery2026.html"
            // Use an OS-independent path construction
            val localCachePath = Path(System.getProperty("user.home"), "tmp", "lottery2026.html")
            val numSimulations = 500_000

            println("Fetching data from $url with local cache at $localCachePath...")

            val doc = fetchDocument(url, localCachePath)
            val categories = parseCategories(doc)

            println("\n--- Part 1: Category Data ---")
            println("Tickets | Entrants | Total Tickets")
            println(categories.joinToString("\n") {
                "%7d | %8d | %13d".format(it.tickets, it.entrants, it.total)
            })

            println("\n--- Part 2: Starting $numSimulations Simulations ---")

            val results = runSimulations(this, categories, numSimulations)

            println("\n--- Simulation Results (Probability of Winning) ---")
            results.toList()
                .sortedBy { (tickets, _) -> tickets }
                .joinToString(separator = "\n") { (tickets, probability) ->
                    "$tickets Tickets: ${probability.toPercentString()}%"
                }
                .let(::println)
        }
    }
    println("Time: $t")
}

// --- Data Fetching and Parsing ---

/**
 * Fetches the HTML document, prioritizing a local cache file.
 */
suspend fun fetchDocument(url: String, localFilePath: java.nio.file.Path): Document {
    val localFile = localFilePath.toFile()

    // Use functional try-catch for reading local text
    val htmlContent = try {
        localFile.readText()
    } catch (e: IOException) {
        println("Local cache not found or error reading. Downloading from URL...")

        // Use 'use' on the client for automatic resource closing
        HttpClient(CIO).use { client ->
            val response = client.get(url)
            val content = response.bodyAsText()

            localFile.parentFile?.mkdirs()
            localFile.writeText(content)
            content
        }
    }

    return Jsoup.parse(htmlContent)
}

/**
 * Parses the document to extract the lottery category data.
 */
private fun parseCategories(doc: Document): List<Category> {
    val path = "/html/body/div/table/tbody/tr[2]/td/table/tbody/tr[2]/td/table/tbody"
    val rows = doc.selectXpath(path).select("tr")

    return rows.drop(1).map { row ->
        val cells = row.select("td")

        val (tickets, entrants, total) = cells
            .filterIndexed { index, _ -> index % 2 == 0 }
            .map { it.text().replace(",", "").toIntOrNull() ?: 0 }

        Category(tickets, entrants, total)
    }
}

// --- Simulation Logic ---

/**
 * Runs the Monte Carlo simulation across multiple coroutines using structural concurrency.
 * @return A map of (Tickets -> Average Probability of Winning).
 */
suspend fun runSimulations(
    scope: CoroutineScope,
    categories: List<Category>,
    numSimulations: Int,
    spots: Int = 260
): Map<Int, Double> {
    val athletes = createAthleteList(categories)

    // Create the ticket pool as a list of athlete IDs.
    val ticketPool = athletes.flatMap { athlete ->
        List(athlete.categoryTickets) { athlete.id }
    }

    // Map of Athlete ID to the number of tickets they hold (category ID)
    val athleteMap = athletes.associate { it.id to it.categoryTickets }

    // Parallel processing of simulations using 'async'
    val jobs = (1..numSimulations).map {
        // async is available because runSimulations is a suspend function
        scope.async {
            runSingleSimulation(ticketPool, athleteMap, categories, spots)
        }
    }

    // Await all results and combine them functionally using reduce.
    return jobs.awaitAll()
        .reduce { acc, simResult ->
            // Combine maps by summing the ratios for each categoryTickets key.
            acc.mapValues { (categoryTickets, sum) ->
                sum + simResult.getValue(categoryTickets)
            }
        }
        // Finally, convert the accumulated sums into averages (probabilities).
        .mapValues { (_, totalSum) ->
            totalSum / numSimulations
        }
}

/**
 * Creates the initial list of athletes with their category ticket counts.
 */
private fun createAthleteList(categories: List<Category>): List<Athlete> {
    var currentId = 0
    return categories.flatMap { category ->
        List(category.entrants) {
            Athlete(currentId++, category.tickets)
        }
    }
}


/**
 * Runs a single lottery simulation, restoring the O(1) random access for performance.
 * The core simulation logic should not perform an O(T) shuffle inside the loop.
 */
private fun runSingleSimulation(
    ticketPool: List<Int>,
    athleteMap: Map<Int, Int>,
    categories: List<Category>,
    spots: Int
): Map<Int, Double> {

    val winners = mutableSetOf<Int>() // Set of unique winning athlete IDs

    // LOOP PERFORMANCE FIX: O(1) random access from the ticket pool
    while (winners.size < spots) {
        val winnerId = ticketPool.random(Random) // Use the List.random(Random) extension
        winners.add(winnerId)
    }

    // Calculate the category win ratios for this single simulation.
    val categoryWinCounts = winners
        // Group by the athlete's ticket count (category identifier)
        .groupingBy { athleteMap.getValue(it) }
        .eachCount()

    return categories.associate { category ->
        val winCount = categoryWinCounts.getOrDefault(category.tickets, 0)
        // Ratio of (winners in category) / (total entrants in category)
        val winRatio = winCount.toDouble() / category.entrants
        category.tickets to winRatio
    }
}

/**
 * --- Part 1: Category Data ---
 * Tickets | Entrants | Total Tickets
 *     512 |        5 |          2560
 *     256 |       47 |         12032
 *     128 |      129 |         16512
 *      64 |      242 |         15488
 *      32 |      354 |         11328
 *      16 |      675 |         10800
 *       8 |     1114 |          8912
 *       4 |     1772 |          7088
 *       2 |     2621 |          5242
 *       1 |     4531 |          4531
 *
 * --- Part 2: Starting 100000 Simulations ---
 *
 * --- Simulation Results (Probability of Winning) ---
 *
 * 1 Tickets: 0.308%
 * 2 Tickets: 0.615%
 * 4 Tickets: 1.227%
 * 8 Tickets: 2.439%
 * 16 Tickets: 4.818%
 * 32 Tickets: 9.410%
 * 64 Tickets: 17.928%
 * 128 Tickets: 32.652%
 * 256 Tickets: 54.633%
 * 512 Tickets: 79.446%
 * Time: 14815
 */