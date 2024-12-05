package com.sphericalchickens
import java.text.DecimalFormat
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

//    categories.keys.sortedDescending().forEach { tickets ->
//        println(categories[tickets])
//    }
//
//    val totals = categories.values.reduce { a, b ->
//        a + b
//    }

//    println("entrants:${totals.entrants}\ntickets: ${totals.total}")

    runSimulations(categories = categories, numSimulations = 100)

    return@runBlocking

//
//    val pool = mutableListOf<Int>()
//    var entrantId = 0
//
//    categories.values.forEach { category ->
//        repeat(category.entrants) {
//            val id = entrantId++
//            categoryForEntrant[id] = category.tickets
//            repeat(category.tickets) {
//                pool.add(id)
//            }
//        }
//    }
//
//    val cumulativeResult = mutableMapOf<Int, Average>()
//
//    val trials = 250_000
//
//    repeat(trials) {
//        simulateB(pool.toMutableList(), categories).forEach { (category, ratio) ->
//            cumulativeResult[category] =
//                cumulativeResult.getOrDefault(category, Average(0.0, 0)).add(ratio)
//        }
//    }
//
//    val formatter = DecimalFormat("0.000")
//
//    var sum = 0.0
//
//    println(cumulativeResult.entries.joinToString("\n") { (cat, counter) ->
//        val category = categories.getValue(cat)
//        val entrants = category.entrants
//        val average = counter.average()
//        val numSelected = entrants * average
//        sum += numSelected
//        "$cat: ${formatter.format(average * 100)}%  $numSelected of $entrants"
//    })
//
//    println("total: $sum")
}

fun runSimulations(categories: List<Category>, numSimulations: Int) {
//    val categories = listOf(
//        Category(512, 1, 512),
//        Category(256, 21, 5376),
//        Category(128, 84, 10752),
//        Category(64, 202, 12928),
//        Category(32, 319, 10208),
//        Category(16, 441, 7056),
//        Category(8, 870, 6960),
//        Category(4, 1472, 5888),
//        Category(2, 2461, 4922),
//        Category(1, 4122, 4122)
//    )

    val athletes = createAthleteList(categories)

    val athleteMap = athletes.toMap()

    val tickets = buildList {
        athletes.forEach { (id, numTickets) ->
            repeat(numTickets) { add(id) }
        }
    }

    val spots = 257

//    val totals = categories.associate { category ->
//        category.tickets to 0.0
//    }.toMutableMap()

    val results = generateSequence(0) { it + 1 }.take(numSimulations).fold(emptyMap<Int, Double>()) { acc, _ ->
        val winners = mutableSetOf<Int>() // Keep track of winning tickets

        while (winners.size < spots) {
            val winner = tickets.random()
            if (!winners.contains(winner)) {
                winners.add(winner)
            }
        }

        val winnersWithCat = winners.map {
            it to athleteMap[it]!!
        }

        val x = winnersWithCat.groupingBy { it.second }.eachCount().toSortedMap()

        val y = x.map { (categoryTickets, numWinners) ->
            val c = categories.first { it.tickets == categoryTickets }
            val winRatio = (numWinners.toDouble() / c.entrants)
            categoryTickets to winRatio
        }.toMap().toSortedMap()

//        y.forEach { (t, u) ->
//            totals[t] = totals[t]!! + u
//        }

        y.map { (id, percent) ->
            id to acc.getOrDefault(id, 0.0) + percent
        }.toMap()
    }

//    println(totals.map { (k, v) ->
//        k to v / numSimulations
//    }.toMap().toSortedMap())
//
//    println(totals.map { (k, v) ->
//        k to v / numSimulations
//    }.toMap().toSortedMap().toList().joinToString(separator = "\n") {
//        val p = String.format("%.3f", (it.second * 100))
//        "${it.first}: $p"
//    })

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

private operator fun Category.plus(other: Category): Category {
    return Category(0, this.entrants + other.entrants, this.total + other.total)
}

private fun Average.add(newValue: Double): Average {
    return Average(sum + newValue, count + 1)
}

data class Average(
    val sum: Double,
    val count: Int
) {
    fun average() = sum / count
}

private fun simulateB(
    pool: MutableList<Int>,
    categories: Map<Int, Category>,
): Map<Int, Double> {
    val selected = mutableMapOf<Int, Int>()

    val selectedEntrants = mutableSetOf<Int>()

    repeat(rounds) {

        while (true) {
            val drawn = pool.random()
            if (!selectedEntrants.contains(drawn)) {
                selectedEntrants.add(drawn)
                val category = categoryForEntrant[drawn]!!
                selected[category] = selected.getOrDefault(category, 0) + 1
                break
            }
        }
    }

    val result = selected.toSortedMap().map { (categoryId, numSelected) ->
        val category = categories[categoryId]!!
        val ratio = numSelected.toDouble() / category.entrants.toDouble()

        categoryId to ratio
    }.toMap()

    return result
}