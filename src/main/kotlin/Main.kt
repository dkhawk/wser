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
import kotlinx.coroutines.*

data class Category(
    val tickets: Int,
    val entrants: Int,
    val total: Int,
)

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

    println(categories.joinToString("\n"))

    launch(Dispatchers.Default) {
        runSimulations(this, categories = categories, numSimulations = 100000)
    }

    return@runBlocking
}

suspend fun runSimulations(
    scope: CoroutineScope,
    categories: List<Category>,
    numSimulations: Int,
    spots: Int = 260
) {
    val athletes = createAthleteList(categories)
    val athleteMap = athletes.toMap()

    val tickets = buildList {
        athletes.forEach { (id, numTickets) ->
            repeat(numTickets) { add(id) }
        }
    }

    val jobs = (0..numSimulations).map {
        scope.async {
            val winners = mutableSetOf<Int>() // Keep track of winning tickets

            while (winners.size < spots) {
                val winner = tickets.random()
                if (!winners.contains(winner)) {
                    winners.add(winner)
                }
            }

            winners.map {
                it to athleteMap[it]!!
            }.groupingBy { it.second }.eachCount().map { (categoryTickets, numWinners) ->
                val c = categories.first { it.tickets == categoryTickets }
                val winRatio = (numWinners.toDouble() / c.entrants)
                categoryTickets to winRatio
            }.toMap()
        }
    }

    val results = jobs.awaitAll().fold(emptyMap<Int, Double>()) { acc, simResult ->
        categories.associate { cat ->
            val id = cat.tickets
            val sum = simResult.getOrDefault(id, 0.0) + acc.getOrDefault(id, 0.0)
            id to sum
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
    return categories.fold(emptyList()) { list, category ->
        list + List(category.entrants) { list.size + it to category.tickets }
    }
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
