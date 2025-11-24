package com.sphericalchickens.gem

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
fun Double.toPercentString(digits: Int = 3) = "%.${digits}f".format(this * 100.0)

// --- Main Execution ---

fun main() = runBlocking {
    val url = "https://www.wser.org/lottery2026.html"
    val localCachePath = Path(System.getProperty("user.home"), "tmp", "lottery2026.html")
    val numSimulations = 100_000

    println("Fetching data from $url with local cache at $localCachePath...")

    // We use a safe-call with try-catch only on the networking/file-system boundary.
    val doc = fetchDocument(url, localCachePath)
    val categories = parseCategories(doc)

    println("\n--- Category Data ---")
    println("Tickets | Entrants | Total Tickets")
    println(categories.joinToString("\n") {
        "%7d | %8d | %13d".format(it.tickets, it.entrants, it.total)
    })

    println("\n--- Starting $numSimulations Simulations ---")

    // The coroutine scope is implicitly provided by runBlocking, no need to pass it.
    val results = runSimulations(this, categories, numSimulations)

    println("\n--- Simulation Results (Probability of Winning) ---")
    results.toList()
        .sortedBy { (tickets, _) -> tickets }
        .joinToString(separator = "\n") { (tickets, probability) ->
            "$tickets Tickets: ${probability.toPercentString()}%"
        }
        .let(::println) // Use 'let' to pass the resulting string to println
}

// --- Data Fetching and Parsing ---

/**
 * Fetches the HTML document, prioritizing a local cache file.
 */
suspend fun fetchDocument(url: String, localFilePath: java.nio.file.Path): Document {
    val localFile = localFilePath.toFile()

    // Use the functional approach to read text: try to read local file, if error, fetch from web.
    val htmlContent = try {
        localFile.readText() // More idiomatic Kotlin I/O
    } catch (e: IOException) {
        println("Local cache not found or error reading. Downloading from URL...")
        // We defer the Ktor client initialization here to only happen on a cache miss.
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)

        // Use 'use' on the client to ensure resources are closed, even if an error occurs.
        client.use {
            val response = it.get(url)
            val content = response.bodyAsText()

            // Ensure the parent directory exists before writing
            localFile.parentFile?.mkdirs()
            localFile.writeText(content)
            content
        }
    }

    return Jsoup.parse(htmlContent)
}

/**
 * Parses the document to extract the lottery category data.
 * The XPath is still fragile, but the structure is cleaner.
 */
private fun parseCategories(doc: Document): List<Category> {
    // Note: XPath is brittle. In a production app, we would look for more stable selectors (e.g., class/id).
    val path = "/html/body/div/table/tbody/tr[2]/td/table/tbody/tr[2]/td/table/tbody"
    val rows = doc.selectXpath(path).select("tr")

    return rows.drop(1).map { row ->
        val cells = row.select("td")

        // Use functional filter and map for clarity. The filter still relies on the table structure.
        val (tickets, entrants, total) = cells
            .filterIndexed { index, _ -> index % 2 == 0 }
            .map { it.text().replace(",", "").toIntOrNull() ?: 0 } // Use toIntOrNull for safer parsing

        Category(tickets, entrants, total)
    }
}

// --- Simulation Logic ---

/**
 * Runs the Monte Carlo simulation across multiple coroutines.
 * @return A map of (Tickets -> Average Probability of Winning).
 */
suspend fun runSimulations(
    scope: CoroutineScope,
    categories: List<Category>,
    numSimulations: Int,
    spots: Int = 260
): Map<Int, Double> {
    val athletes = createAthleteList(categories)

    // Create the ticket pool as a list of athlete IDs. Immutable 'val' is preferred.
    val ticketPool = athletes.flatMap { athlete ->
        List(athlete.categoryTickets) { athlete.id }
    }

    val athleteMap = athletes.associate { it.id to it.categoryTickets }

    // Parallel processing of simulations using 'async'
    val jobs = (1..numSimulations).map {
        scope.async {
            runSingleSimulation(ticketPool, athleteMap, categories, spots)
        }
    }

    // Await all results and combine them functionally.
    return jobs.awaitAll()
        .reduce { acc, simResult ->
            // Use 'reduce' to combine the maps into a single map of sums.
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
 * Runs a single lottery simulation to determine the win ratios for each category.
 */
private fun runSingleSimulation(
    ticketPool: List<Int>,
    athleteMap: Map<Int, Int>,
    categories: List<Category>,
    spots: Int
): Map<Int, Double> {
    // Using a Fisher-Yates shuffle approach on the ticket pool is much cleaner and more efficient
    // than a 'while' loop with random selection and set checking, especially for a large spots value.
    // 1. Shuffle the list of tickets.
    val shuffledTickets = ticketPool.shuffled(Random)

    // 2. Select the winners from the shuffled pool.
    val winners = mutableSetOf<Int>() // Set of unique winning athlete IDs

    // Iterate through the shuffled tickets and stop once we have enough unique winners.
    for (athleteId in shuffledTickets) {
        winners.add(athleteId)
        if (winners.size >= spots) break
    }

    // Calculate the category win ratios for this single simulation.
    val categoryWinCounts = winners
        // Group the unique winning athlete IDs by their ticket count (category identifier)
        .groupingBy { athleteMap[it]!! }
        .eachCount()

    return categories.associate { category ->
        val winCount = categoryWinCounts.getOrDefault(category.tickets, 0)
        // Ratio of (winners in category) / (total entrants in category)
        val winRatio = winCount.toDouble() / category.entrants
        category.tickets to winRatio
    }
}

/**
 * Creates the initial list of athletes with their category ticket counts.
 */
private fun createAthleteList(categories: List<Category>): List<Athlete> {
    // Use flatMap to create a single list of Athlete objects in a functional way.
    // The index is implicitly handled by 'runningReduce' or manually, here we use a mutable counter
    // for simplicity and local scope control, which is acceptable in a private utility function.
    var currentId = 0
    return categories.flatMap { category ->
        List(category.entrants) {
            // Post-increment is used to assign the ID and then prepare for the next athlete.
            Athlete(currentId++, category.tickets)
        }
    }
}
