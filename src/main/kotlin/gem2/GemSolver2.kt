package com.sphericalchickens.gem2

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import kotlin.io.path.Path
import kotlin.io.path.readText
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

// --- Domain Models ---

/**
 * Represents a single category of lottery entrants.
 * @property tickets Number of tickets each entrant in this category holds. (Key for calculation)
 * @property entrants Number of unique athletes in this category.
 * @property total Total number of tickets contributed by this category.
 */
data class Category(
    val tickets: Int,
    val entrants: Int,
    val total: Int,
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

fun main() = runBlocking {
    val url = "https://www.wser.org/lottery2025.html"
    val localCachePath = Path(System.getProperty("user.home"), "tmp", "lottery2025.html")

    println("Fetching data from $url with local cache at $localCachePath...")

    val doc = fetchDocument(url, localCachePath)
    val categories = parseCategories(doc)

    println("\n--- Part 1: Category Data ---")
    println("Tickets | Entrants | Total Tickets")
    println(categories.joinToString("\n") {
        "%7d | %8d | %13d".format(it.tickets, it.entrants, it.total)
    })

    // --- Part 2: Combinatorial Calculation ---
    val startTime = System.currentTimeMillis()
    val results = calculateExactProbabilities(categories)
    val endTime = System.currentTimeMillis()

    println("\n--- Part 2: Combinatorial Results ---")
    println("Calculation Time: ${endTime - startTime} ms")

    println("\n--- Exact Probability of Winning (Non-Simulated) ---")
    results.toList()
        .sortedBy { (tickets, _) -> tickets }
        .joinToString(separator = "\n") { (tickets, probability) ->
            "$tickets Tickets: ${probability.toPercentString()}%"
        }
        .let(::println)
}

// --- Data Fetching and Parsing (Retained from Refactor) ---

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

// --- Combinatorial Logic (The Core Algorithm) ---

/**
 * Calculates the exact probability of an athlete winning in each category using combinatorics.
 * * @param categories The list of lottery categories.
 * @param spots The number of available spots in the lottery (S).
 * @return A map of (Tickets -> Exact Probability of Winning).
 */
fun calculateExactProbabilities(
    categories: List<Category>,
    spots: Int = 260
): Map<Int, Double> {

    // Calculate the total number of tickets in the entire pool (T).
    val totalTickets = categories.sumOf { it.total }

    // The result is a map of Category Tickets to the calculated probability.
    return categories.associate { category ->
        val k = category.tickets // The number of tickets held by an athlete in this category (k).
        val N = category.entrants // The number of entrants in this category (N_c).

        // Total number of "other" tickets not belonging to the specific athlete (T_other).
        val totalOtherTickets = totalTickets - k

        // Probability of Winning P(Win) = 1 - P(Lose).
        // P(Lose) is the probability that *none* of the athlete's k tickets are drawn.
        // This is calculated as:
        // P(Lose) = (Ways to choose S winners from T_other) / (Ways to choose S winners from T)

        // This is a product of (T_other - i) / (T - i) for i=0 to S-1.

        var probabilityOfLosing = 1.0

        // Use a functional loop over the range of draws (i = 0 to spots - 1).
        (0 until spots).forEach { i ->
            // The ticket pool size decreases by 1 in each draw (non-replacement).
            // This is the number of tickets NOT belonging to the athlete, available for draw i.
            val otherTicketsAvailable = totalOtherTickets.toDouble() - i

            // This is the total number of tickets available for draw i.
            val totalTicketsAvailable = totalTickets.toDouble() - i

            // The probability that draw 'i' is NOT one of the athlete's tickets.
            val pDrawIsOther = otherTicketsAvailable / totalTicketsAvailable

            // P(Lose) is the product of these probabilities for all S draws.
            probabilityOfLosing *= pDrawIsOther
        }

        // Final probability of winning for an athlete in this category.
        val probabilityOfWinning = 1.0 - probabilityOfLosing

        // Map the ticket count (k) to the final probability.
        category.tickets to probabilityOfWinning
    }
}