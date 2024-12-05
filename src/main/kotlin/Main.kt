package com.sphericalchickens
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.readText
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking

data class Category(
    val tickets: Int,
    val entrants: Int,
    val total: Int,
)

// const val rounds = 270
const val rounds = 10

val categoryForEntrant = mutableMapOf<Int, Int>()

suspend fun getDocument(url: String): Document {
    val localFilePath = "/Users/dkhawk/tmp/lottery2025.html"
    val client = HttpClient(CIO)

    val htmlContent = try {
        Path(localFilePath).readText()
        // Try reading from the local file first
    } catch (e: Exception) {
        // If the file doesn't exist or can't be read, download it
        println("Local file not found or error reading. Downloading from URL...")
        val response = client.get(url)
        val content = response.bodyAsText()
        // Save the downloaded content to the local file
        File(localFilePath).writeText(content)
        content
    }

    return Jsoup.parse(htmlContent)
}

fun main(args: Array<String>) = runBlocking {

    val doc = getDocument("https://www.wser.org/lottery2025.html")

    val categories = createCategories(doc)

    println(categories)
    runSimulations(categories = categories, numSimulations = 50000)

    return@runBlocking
}

fun runSimulations(categories: List<Category>, numSimulations: Int) {
    val athletes = createAthleteList(categories)

    val athleteMap = athletes.toMap()

    val tickets = buildList {
        athletes.forEach { (id, numTickets) ->
            repeat(numTickets) { add(id) }
        }
    }

    val spots = 257
    val results = generateSequence(0) { it + 1 }.take(numSimulations).fold(emptyMap<Int, Double>()) { acc, _ ->
        val winners = mutableSetOf<Int>() // Keep track of winning tickets

        while (winners.size < spots) {
            val winner = tickets.random()
            if (!winners.contains(winner)) {
                winners.add(winner)
            }
        }

        val y = winners.map {
            it to athleteMap[it]!!
        }.groupingBy { it.second }.eachCount().map { (categoryTickets, numWinners) ->
            val c = categories.first { it.tickets == categoryTickets }
            val winRatio = (numWinners.toDouble() / c.entrants)
            categoryTickets to winRatio
        }.toMap()

        categories.associate { cat ->
            val id = cat.tickets
            val s = y.getOrDefault(id, 0.0) + acc.getOrDefault(id, 0.0)
            id to s
        }
    }

    println(results.map { (k, v) ->
        k to v / numSimulations
    }.toMap().toSortedMap().toList().joinToString(separator = "\n") {
        val p = String.format("%.3f", (it.second * 100))
        "${it.first}: $p"
    })
}

private fun createAthleteList(categories: List<Category>): List<Pair<Int, Int>> {
    var index = 0

    val athletes = categories.flatMap { category ->
        val l = buildList {
            repeat(category.entrants) { it ->
                add(index + it to category.tickets)
            }
        }

        index += category.entrants
        l
    }
    return athletes
}

private fun createCategories(doc: Document): List<Category> {
    val path = "/html/body/div/table/tbody/tr[2]/td/table/tbody/tr[2]/td/table/tbody"
    val elements = doc.selectXpath(path)

    val rows = elements.select("tr")

    return rows.drop(1).map { row ->
        val (tickets, entrants, total) =
            row.select("td").filterIndexed { index, _ -> index % 2 == 0 }.map { td ->
                td.text().replace(",", "").toInt()
            }
        Category(tickets, entrants, total)
    }
}
