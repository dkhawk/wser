package com.sphericalchickens.gem

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.random.Random
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlin.system.measureTimeMillis

// --- Domain Models ---

/**
 * Represents a single category of lottery entrants.
 */
data class Category(
    val tickets: Int,
    val entrants: Int,
    val total: Int,
)

/**
 * Represents an athlete in the lottery pool.
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

val year = 2026

fun main() {
    val t = measureTimeMillis {
        runBlocking {

            val url = "https://www.wser.org/lottery$year.html"
            val localCachePath = Path(System.getProperty("user.home"), "tmp", "lottery$year.html")
            // Use your large, demanding number of simulations for a proper benchmark.
            val numSimulations = 500_000

            println("Fetching data from $url with local cache at $localCachePath...")

            val doc = fetchDocument(url, localCachePath)
            val categories = parseCategories(doc)

            println("\n--- Part 1: Category Data ---")
            println(categories.joinToString("\n"))

            println("\n--- Part 2: Starting $numSimulations Parallel Simulations ---")
            val startTime = System.currentTimeMillis()

            val results = runParallelSimulations(this, categories, numSimulations)

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

    println("Time: $t")
}

// --- Data Fetching and Parsing (Retained) ---

/**
 * Fetches the HTML document, prioritizing a local cache file.
 */
suspend fun fetchDocument(url: String, localFilePath: java.nio.file.Path): Document {
    val localFile = localFilePath.toFile()

    val htmlContent = try {
        localFile.readText()
    } catch (e: IOException) {
        println("Local cache not found or error reading. Downloading from URL...")

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

// --- High-Performance Simulation Logic ---

/**
 * Runs the Monte Carlo simulation across multiple coroutines, optimizing for speed.
 * The inner loop uses O(1) random access draw with a mutable Set for unique winners.
 */
suspend fun runParallelSimulations(
    scope: CoroutineScope,
    categories: List<Category>,
    numSimulations: Int,
    spots: Int = 260
): Map<Int, Double> {
    val athletes = createAthleteList(categories)

    // Create the ticket pool ONCE (O(T) setup)
    val ticketPool = athletes.flatMap { athlete ->
        List(athlete.categoryTickets) { athlete.id }
    }

    // Map of Athlete ID to the number of tickets they hold (category ID)
    val athleteMap = athletes.associate { it.id to it.categoryTickets }

    // Parallel processing of simulations using 'async'
    // We launch 100 coroutines, each running a batch of simulations.
    // This reduces the per-simulation overhead of coroutine launch/management.
    val numWorkers = 100
    val batchSize = numSimulations / numWorkers

    val jobs = (1..numWorkers).map {
        // Run on the default Dispatcher (CPU-bound)
        scope.async {
            // Each coroutine runs a batch of simulations and returns an aggregated map of results
            (1..batchSize).fold(categories.associate { it.tickets to 0.0 }) { acc, _ ->
                val simResult = runSingleSimulation(ticketPool, athleteMap, categories, spots)

                // Aggregation: Manually summing the results in the loop for maximum speed
                acc.mapValues { (categoryTickets, currentSum) ->
                    currentSum + simResult.getValue(categoryTickets)
                }
            }
        }
    }

    // Await all batch results and combine them into a final single map.
    return jobs.awaitAll()
        .reduce { acc, batchResult ->
            // Combine batch maps by summing the total ratios.
            acc.mapValues { (categoryTickets, sum) ->
                sum + batchResult.getValue(categoryTickets)
            }
        }
        // Finally, convert the accumulated sums into averages (probabilities).
        .mapValues { (_, totalSum) ->
            totalSum / numSimulations
        }
}

/**
 * Runs a single lottery simulation using the verified O(1) random draw method.
 */
private fun runSingleSimulation(
    ticketPool: List<Int>,
    athleteMap: Map<Int, Int>,
    categories: List<Category>,
    spots: Int
): Map<Int, Double> {

    val winners = mutableSetOf<Int>() // Set of unique winning athlete IDs

    // Core Draw Loop: O(1) random access + O(1) set check. FAST.
    while (winners.size < spots) {
        val winnerId = ticketPool.random(Random)
        winners.add(winnerId)
    }

    // Calculate the category win ratios for this single simulation.
    val categoryWinCounts = winners
        // Group by the athlete's ticket count (category identifier)
        .groupingBy { athleteMap.getValue(it) }
        .eachCount()

    // Map the counts back to the final ratio.
    return categories.associate { category ->
        val winCount = categoryWinCounts.getOrDefault(category.tickets, 0)
        // Ratio of (winners in category) / (total entrants in category)
        val winRatio = winCount.toDouble() / category.entrants
        category.tickets to winRatio
    }
}