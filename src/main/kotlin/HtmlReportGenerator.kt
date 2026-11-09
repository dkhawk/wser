package com.sphericalchickens

import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.writeText
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Generates a self-contained, responsive static HTML dashboard (`docs/index.html`) for GitHub Pages.
 *
 * For each ticket category (`1` through `1,024` tickets), renders a dedicated card with an inline
 * SVG chart showing:
 * 1. The **Outright Main Lottery selection probability** (`1..260`), and
 * 2. The **cumulative probability across every 5-slot waitlist bucket** (`+5`, `+10`, `+15`, ..., `+75`),
 *    stacked atop the outright selection probability so both the **Waitlist-Only Cumulative (`1..k`)**
 *    and **Combined Total Chance (`Outright + WL 1..k`)** are immediately visible.
 */
fun writeHtmlReport(
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
    val steps = DEFAULT_WAITLIST_5_SLOT_STEPS

    val html = buildString(64_000) {
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("  <meta charset=\"utf-8\" />")
        appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />")
        // Strict CSP: zero external scripts or network requests required.
        appendLine(
            "  <meta http-equiv=\"Content-Security-Policy\" " +
                "content=\"default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none';\" />"
        )
        appendLine("  <title>${escapeHtml(options.sourceLabel)} — Probability Breakdown &amp; 5-Slot Waitlist Graphs</title>")
        appendLine("  <style>")
        appendLine(DASHBOARD_CSS)
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <div class=\"page-shell\">")

        // --- Hero Header ---
        appendLine("    <header class=\"hero-card\">")
        appendLine("      <div class=\"hero-top\">")
        appendLine("        <div class=\"buckle-badge\" aria-hidden=\"true\">")
        appendLine("          <span class=\"buckle-title\">WSER</span>")
        appendLine("          <span class=\"buckle-sub\">100 MILE</span>")
        appendLine("        </div>")
        appendLine("        <div>")
        appendLine("          <h1>${escapeHtml(options.sourceLabel)} — Selection &amp; Waitlist Probabilities</h1>")
        appendLine("          <p class=\"hero-subtitle\">")
        appendLine("            Outright Main Lottery odds (<strong>$DEFAULT_LOTTERY_SPOTS spots</strong>) plus cumulative selection across every ")
        appendLine("            <strong>5-slot Waitlist bucket</strong> (<code>+5</code> through <code>+75</code>), verified by ")
        appendLine("            <strong>${"%,d".format(options.numSimulations)}</strong> Monte Carlo trials &amp; Wallenius' Noncentral Hypergeometric formula.")
        appendLine("          </p>")
        appendLine("        </div>")
        appendLine("      </div>")
        appendLine("      <div class=\"stat-strip\">")
        appendStatPill("Snapshot Date (UTC)", snapshotDateUtc)
        appendStatPill("WSER Page Updated", lastUpdatedLabel)
        appendStatPill("Total Entrants", "%,d".format(totalEntrants))
        appendStatPill("Total Tickets in Hat", "%,d".format(totalTickets))
        appendStatPill("Main Lottery Spots", DEFAULT_LOTTERY_SPOTS.toString())
        appendStatPill("Waitlist Depth", "50–75 (by 5s)")
        appendLine("      </div>")
        appendLine("    </header>")

        // --- Quick Jump Navigation Bar ---
        appendLine("    <nav class=\"tier-nav\" aria-label=\"Jump to ticket category\">")
        appendLine("      <span class=\"tier-nav-label\">Jump to Ticket Bucket:</span>")
        for (cat in categories) {
            val label = if (cat.tickets == 1) "1 Ticket" else "${"%,d".format(cat.tickets)} Tickets"
            appendLine("      <a class=\"tier-chip\" href=\"#bucket-${cat.tickets}\">${escapeHtml(label)}</a>")
        }
        appendLine("      <a class=\"tier-chip tier-chip-table\" href=\"#summary-table\">All Buckets Table</a>")
        appendLine("    </nav>")

        // --- Legend Banner ---
        appendLine("    <section class=\"legend-banner\" aria-label=\"Chart Legend\">")
        appendLine("      <div class=\"legend-item\"><span class=\"swatch swatch-main\"></span><strong>Outright Main Lottery (1–$DEFAULT_LOTTERY_SPOTS)</strong>: Selected directly in the main draw</div>")
        appendLine("      <div class=\"legend-item\"><span class=\"swatch swatch-wl\"></span><strong>Cumulative Waitlist (+5 to +75)</strong>: Stacked probability of landing in waitlist slots <code>1..k</code></div>")
        appendLine("      <div class=\"legend-item\"><span class=\"swatch swatch-key\"></span><strong>Highlighted Cutoffs (+10, +25, +50, +75)</strong>: Key waitlist depth milestones</div>")
        appendLine("    </section>")

        // --- Per-Bucket Cards Grid ---
        appendLine("    <main class=\"bucket-grid\">")
        for (cat in categories) {
            appendBucketCard(
                category = cat,
                steps = steps,
                simulatedOutcomes = simulatedOutcomes,
                formulaOutcomes = formulaOutcomes,
            )
        }
        appendLine("    </main>")

        // --- Complete Summary Comparison Table ---
        appendSummarySection(
            categories = categories,
            steps = steps,
            simulatedOutcomes = simulatedOutcomes,
            formulaOutcomes = formulaOutcomes,
        )

        appendLine("    <footer class=\"page-footer\">")
        appendLine("      <p>Generated by the Western States 100 (WSER) Lottery &amp; Waitlist Probability Calculator.</p>")
        appendLine("    </footer>")
        appendLine("  </div>")
        appendLine("</body>")
        appendLine("</html>")
    }

    outputPath.parent?.toFile()?.mkdirs()
    outputPath.writeText(html)
}

private fun StringBuilder.appendStatPill(label: String, value: String) {
    appendLine("        <div class=\"stat-pill\">")
    appendLine("          <span class=\"stat-label\">${escapeHtml(label)}</span>")
    appendLine("          <span class=\"stat-value\">${escapeHtml(value)}</span>")
    appendLine("        </div>")
}

private fun StringBuilder.appendBucketCard(
    category: Category,
    steps: List<Int>,
    simulatedOutcomes: LotteryOutcomeProbabilities,
    formulaOutcomes: LotteryOutcomeProbabilities,
) {
    val t = category.tickets
    val approxYears = (ln(t.toDouble()) / ln(2.0)).roundToInt() + 1
    val mainMc = simulatedOutcomes.mainLottery.getValue(t)
    val mainFm = formulaOutcomes.mainLottery.getValue(t)

    // Cumulative waitlist probabilities for 5, 10, ..., 75
    val wlCumByStep = steps.associateWith { stepEnd ->
        simulatedOutcomes.waitlistCumulativeBy5[stepEnd]?.get(t)
            ?: formulaOutcomes.waitlistCumulativeBy5[stepEnd]?.get(t)
            ?: 0.0
    }

    val wl10 = wlCumByStep[10] ?: simulatedOutcomes.waitlistTop10.getValue(t)
    val wl25 = wlCumByStep[25] ?: simulatedOutcomes.waitlistTop25.getValue(t)
    val wl50 = wlCumByStep[50] ?: simulatedOutcomes.waitlistTop50.getValue(t)
    val wl75 = wlCumByStep[75] ?: simulatedOutcomes.waitlistTop75.getValue(t)

    val totalAt10 = mainMc + wl10
    val totalAt25 = mainMc + wl25
    val totalAt50 = mainMc + wl50
    val totalAt75 = mainMc + wl75
    val expectedOutrightWinners = category.entrants * mainFm
    val expectedTotalWithWl50 = category.entrants * (mainFm + (formulaOutcomes.waitlistTop50[t] ?: wl50))

    val ticketTitle = if (t == 1) "1 Ticket" else "${"%,d".format(t)} Tickets"

    appendLine("      <article class=\"bucket-card-container\" id=\"bucket-$t\">")
    appendLine("        <div class=\"bucket-card\">")
    appendLine("          <div class=\"bucket-header\">")
    appendLine("            <div>")
    appendLine("              <div class=\"bucket-title-row\">")
    appendLine("                <h2>${escapeHtml(ticketTitle)}</h2>")
    appendLine("                <span class=\"year-badge\">Year $approxYears (2<sup>${approxYears - 1}</sup>)</span>")
    appendLine("              </div>")
    appendLine(
        "              <p class=\"bucket-meta\">" +
            "<strong>${"%,d".format(category.entrants)}</strong> entrant${if (category.entrants == 1) "" else "s"} &middot; " +
            "<strong>${"%,d".format(category.total)}</strong> total tickets &middot; " +
            "Expected winners: <strong>${expectedOutrightWinners.format(1)}</strong> outright " +
            "(<strong>${expectedTotalWithWl50.format(1)}</strong> incl. WL 1–50)" +
            "</p>"
    )
    appendLine("            </div>")
    appendLine("          </div>")

    // Milestone Callout Strip
    appendLine("          <div class=\"kpi-strip\">")
    appendKpiBox(
        title = "Selected Outright",
        mainPct = mainMc * 100.0,
        subLabel = "Formula: ${(mainFm * 100.0).format(3)}%",
        accentClass = "kpi-outright",
    )
    appendKpiBox(
        title = "Outright + WL ≤ 10",
        mainPct = totalAt10 * 100.0,
        subLabel = "WL 1–10 only: +${(wl10 * 100.0).format(3)}%",
        accentClass = "kpi-wl",
    )
    appendKpiBox(
        title = "Outright + WL ≤ 25",
        mainPct = totalAt25 * 100.0,
        subLabel = "WL 1–25 only: +${(wl25 * 100.0).format(3)}%",
        accentClass = "kpi-wl",
    )
    appendKpiBox(
        title = "Outright + WL ≤ 50",
        mainPct = totalAt50 * 100.0,
        subLabel = "WL 1–50 only: +${(wl50 * 100.0).format(3)}%",
        accentClass = "kpi-highlight",
    )
    appendKpiBox(
        title = "Outright + WL ≤ 75",
        mainPct = totalAt75 * 100.0,
        subLabel = "WL 1–75 only: +${(wl75 * 100.0).format(3)}%",
        accentClass = "kpi-wl",
    )
    appendLine("          </div>")

    // Inline SVG Cumulative Chart (Outright + 15 x 5-Slot Waitlist Steps)
    appendBucketSvgChart(
        tickets = t,
        mainProb = mainMc,
        steps = steps,
        wlCumByStep = wlCumByStep,
    )

    // Collapsible 5-Slot Breakdown Table
    appendLine("          <details class=\"bucket-details\">")
    appendLine("            <summary>View exact 5-slot waitlist breakdown table for ${escapeHtml(ticketTitle)}</summary>")
    appendLine("            <div class=\"table-scroll\">")
    appendLine("              <table class=\"mini-table\">")
    appendLine("                <thead>")
    appendLine("                  <tr>")
    appendLine("                    <th>Stage / Cutoff</th>")
    appendLine("                    <th>Draw Ranks</th>")
    appendLine("                    <th>5-Slot Bucket Prob</th>")
    appendLine("                    <th>Cumulative Waitlist (1..k)</th>")
    appendLine("                    <th>Total Cumulative (Outright + WL 1..k)</th>")
    appendLine("                  </tr>")
    appendLine("                </thead>")
    appendLine("                <tbody>")
    appendLine("                  <tr class=\"row-outright\">")
    appendLine("                    <td><strong>Selected Outright (Lottery)</strong></td>")
    appendLine("                    <td><code>1–$DEFAULT_LOTTERY_SPOTS</code></td>")
    appendLine("                    <td>—</td>")
    appendLine("                    <td>0.000%</td>")
    appendLine("                    <td><strong>${(mainMc * 100.0).format(3)}%</strong></td>")
    appendLine("                  </tr>")

    var prevWlCum = 0.0
    for (stepEnd in steps) {
        val stepStart = stepEnd - 4
        val wlCum = wlCumByStep.getValue(stepEnd)
        val bucketSlice = (wlCum - prevWlCum).coerceAtLeast(0.0)
        val combinedCum = mainMc + wlCum
        val isKeyMilestone = stepEnd == 10 || stepEnd == 25 || stepEnd == 50 || stepEnd == 75
        val rowClass = if (isKeyMilestone) " class=\"row-milestone\"" else ""
        appendLine("                  <tr$rowClass>")
        appendLine("                    <td>Waitlist ≤ $stepEnd (<code>+$stepEnd</code>)</td>")
        appendLine("                    <td><code>WL $stepStart–$stepEnd</code></td>")
        appendLine("                    <td>+${(bucketSlice * 100.0).format(3)}%</td>")
        appendLine("                    <td>${(wlCum * 100.0).format(3)}%</td>")
        appendLine("                    <td><strong>${(combinedCum * 100.0).format(3)}%</strong></td>")
        appendLine("                  </tr>")
        prevWlCum = wlCum
    }
    appendLine("                </tbody>")
    appendLine("              </table>")
    appendLine("            </div>")
    appendLine("          </details>")

    appendLine("        </div>")
    appendLine("      </article>")
}

private fun StringBuilder.appendKpiBox(
    title: String,
    mainPct: Double,
    subLabel: String,
    accentClass: String,
) {
    appendLine("            <div class=\"kpi-box $accentClass\">")
    appendLine("              <span class=\"kpi-title\">${escapeHtml(title)}</span>")
    appendLine("              <span class=\"kpi-value\">${mainPct.format(3)}%</span>")
    appendLine("              <span class=\"kpi-sub\">${escapeHtml(subLabel)}</span>")
    appendLine("            </div>")
}

/**
 * Renders an inline SVG chart for a single ticket tier showing 16 cumulative columns:
 * - Column 0: `Outright` (`Main Lottery 1..260`)
 * - Columns 1..15: `+5`, `+10`, `+15`, ..., `+75` (`Outright` base bar + stacked `Waitlist 1..k` bar)
 */
private fun StringBuilder.appendBucketSvgChart(
    tickets: Int,
    mainProb: Double,
    steps: List<Int>,
    wlCumByStep: Map<Int, Double>,
) {
    val maxWl = wlCumByStep[steps.last()] ?: 0.0
    val maxCombinedPct = ((mainProb + maxWl) * 100.0).coerceAtLeast(0.01)
    // Add 14% headroom above the highest bar so top value callouts never clip
    val yMaxPct = (maxCombinedPct * 1.14).coerceAtMost(100.0)

    val svgWidth = 760
    val svgHeight = 250
    val plotLeft = 58.0
    val plotRight = 738.0
    val plotTop = 26.0
    val plotBottom = 202.0
    val plotWidth = plotRight - plotLeft
    val plotHeight = plotBottom - plotTop

    val totalColumns = 1 + steps.size // 16 columns: Outright + 15 waitlist steps
    val colSlotWidth = plotWidth / totalColumns
    val barWidth = (colSlotWidth * 0.64).coerceIn(14.0, 28.0)

    fun yForPct(pct: Double): Double {
        val ratio = (pct / yMaxPct).coerceIn(0.0, 1.0)
        return plotBottom - ratio * plotHeight
    }

    val yDigits = when {
        yMaxPct < 1.0 -> 3
        yMaxPct < 10.0 -> 2
        else -> 1
    }

    appendLine("          <div class=\"chart-wrap\">")
    appendLine(
        "            <svg viewBox=\"0 0 $svgWidth $svgHeight\" width=\"100%\" height=\"100%\" " +
            "role=\"img\" aria-label=\"Cumulative probability chart for $tickets tickets\">"
    )

    // Horizontal Y-axis grid lines (0%, 25%, 50%, 75%, 100% of yMaxPct)
    appendLine("              <g class=\"grid-lines\">")
    for (tickIdx in 0..4) {
        val frac = tickIdx / 4.0
        val tickPct = yMaxPct * frac
        val y = yForPct(tickPct)
        val strokeColor = if (tickIdx == 0) "#475569" else "#1e293b"
        appendLine(
            "                <line x1=\"${plotLeft.format(1)}\" y1=\"${y.format(1)}\" " +
                "x2=\"${plotRight.format(1)}\" y2=\"${y.format(1)}\" stroke=\"$strokeColor\" stroke-width=\"1\" />"
        )
        appendLine(
            "                <text x=\"${(plotLeft - 7.0).format(1)}\" y=\"${(y + 3.5).format(1)}\" " +
                "text-anchor=\"end\" fill=\"#94a3b8\" font-size=\"10\" font-family=\"ui-monospace, SFMono-Regular, Menlo, monospace\">" +
                "${tickPct.format(yDigits)}%</text>"
        )
    }
    appendLine("              </g>")

    // Baseline dashed reference line showing Outright Lottery level across all waitlist columns
    val outrightPct = mainProb * 100.0
    val yOutright = yForPct(outrightPct)
    appendLine(
        "              <line x1=\"${plotLeft.format(1)}\" y1=\"${yOutright.format(1)}\" " +
            "x2=\"${plotRight.format(1)}\" y2=\"${yOutright.format(1)}\" " +
            "stroke=\"#38bdf8\" stroke-width=\"1.2\" stroke-dasharray=\"4 3\" opacity=\"0.65\" />"
    )

    // Build bars and collect polyline points for the cumulative trajectory
    val trajectoryPoints = mutableListOf<Pair<Double, Double>>()
    var prevWlProb = 0.0

    for (colIdx in 0 until totalColumns) {
        val centerX = plotLeft + (colIdx + 0.5) * colSlotWidth
        val barX = centerX - barWidth / 2.0

        val stepRank = if (colIdx == 0) 0 else steps[colIdx - 1]
        val wlCumProb = if (colIdx == 0) 0.0 else wlCumByStep.getValue(stepRank)
        val wlSliceProb = if (colIdx == 0) 0.0 else (wlCumProb - prevWlProb).coerceAtLeast(0.0)
        prevWlProb = wlCumProb

        val wlCumPct = wlCumProb * 100.0
        val combinedPct = outrightPct + wlCumPct
        val yCombined = yForPct(combinedPct)
        val mainBarHeight = (plotBottom - yOutright).coerceAtLeast(0.0)
        val wlBarHeight = (yOutright - yCombined).coerceAtLeast(0.0)

        trajectoryPoints.add(centerX to yCombined)

        val isKeyMilestone = stepRank == 10 || stepRank == 25 || stepRank == 50 || stepRank == 75
        val xLabel = if (colIdx == 0) "Lottery" else "+$stepRank"
        val tooltipText = if (colIdx == 0) {
            "$tickets Tickets — Selected Outright (Draws 1–$DEFAULT_LOTTERY_SPOTS): ${outrightPct.format(3)}%"
        } else {
            "$tickets Tickets — Outright + Waitlist 1–$stepRank: ${combinedPct.format(3)}% " +
                "(Outright: ${outrightPct.format(3)}% + Cumulative WL 1–$stepRank: ${wlCumPct.format(3)}%, " +
                "5-Slot Bucket WL ${stepRank - 4}–$stepRank: +${(wlSliceProb * 100.0).format(3)}%)"
        }

        appendLine("              <g class=\"bar-group\">")
        appendLine("                <title>${escapeHtml(tooltipText)}</title>")

        // Base bar: Outright Main Lottery probability
        val baseFill = if (colIdx == 0) "#0ea5e9" else "#0284c7"
        val baseOpacity = if (colIdx == 0) "0.95" else "0.48"
        appendLine(
            "                <rect x=\"${barX.format(1)}\" y=\"${yOutright.format(1)}\" " +
                "width=\"${barWidth.format(1)}\" height=\"${mainBarHeight.format(1)}\" " +
                "rx=\"2\" fill=\"$baseFill\" opacity=\"$baseOpacity\" />"
        )

        // Top stacked bar: Cumulative Waitlist (1..stepRank) probability
        if (colIdx > 0 && wlBarHeight > 0.0) {
            val wlFill = when (stepRank) {
                50 -> "#10b981"
                10, 25, 75 -> "#f59e0b"
                else -> "#fbbf24"
            }
            val wlOpacity = if (isKeyMilestone) "0.95" else "0.78"
            appendLine(
                "                <rect x=\"${barX.format(1)}\" y=\"${yCombined.format(1)}\" " +
                    "width=\"${barWidth.format(1)}\" height=\"${wlBarHeight.format(1)}\" " +
                    "rx=\"2\" fill=\"$wlFill\" opacity=\"$wlOpacity\" />"
            )
        }

        // Value label above key milestones (Outright, +10, +25, +50, +75)
        if (colIdx == 0 || isKeyMilestone) {
            val labelColor = when (stepRank) {
                0 -> "#7dd3fc"
                50 -> "#6ee7b7"
                else -> "#fde68a"
            }
            val labelDigits = if (combinedPct < 1.0) 3 else 2
            appendLine(
                "                <text x=\"${centerX.format(1)}\" y=\"${(yCombined - 7.0).format(1)}\" " +
                    "text-anchor=\"middle\" fill=\"$labelColor\" font-size=\"9.5\" font-weight=\"700\" " +
                    "font-family=\"ui-monospace, SFMono-Regular, Menlo, monospace\">" +
                    "${combinedPct.format(labelDigits)}%</text>"
            )
        }

        // X-axis tick label
        val xLabelColor = if (colIdx == 0 || isKeyMilestone) "#f8fafc" else "#94a3b8"
        val xLabelWeight = if (colIdx == 0 || isKeyMilestone) "700" else "500"
        appendLine(
            "                <text x=\"${centerX.format(1)}\" y=\"${(plotBottom + 16.0).format(1)}\" " +
                "text-anchor=\"middle\" fill=\"$xLabelColor\" font-size=\"9.5\" font-weight=\"$xLabelWeight\" " +
                "font-family=\"ui-monospace, SFMono-Regular, Menlo, monospace\">$xLabel</text>"
        )

        // Waitlist-only sub-label under key milestones (+10, +25, +50, +75)
        if (isKeyMilestone) {
            val subDigits = if (wlCumPct < 1.0) 2 else 1
            appendLine(
                "                <text x=\"${centerX.format(1)}\" y=\"${(plotBottom + 29.0).format(1)}\" " +
                    "text-anchor=\"middle\" fill=\"#fbbf24\" font-size=\"8.5\" " +
                    "font-family=\"ui-monospace, SFMono-Regular, Menlo, monospace\">" +
                    "(+${wlCumPct.format(subDigits)}%)</text>"
            )
        }

        appendLine("              </g>")
    }

    // Cumulative trend line connecting all 16 bar tops
    val polyPoints = trajectoryPoints.joinToString(" ") { (x, y) -> "${x.format(1)},${y.format(1)}" }
    appendLine(
        "              <polyline points=\"$polyPoints\" fill=\"none\" stroke=\"#fde047\" " +
            "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\"0.9\" />"
    )
    for ((idx, pt) in trajectoryPoints.withIndex()) {
        val stepRank = if (idx == 0) 0 else steps[idx - 1]
        val isKey = idx == 0 || stepRank == 10 || stepRank == 25 || stepRank == 50 || stepRank == 75
        val radius = if (isKey) "3.6" else "2.4"
        val dotFill = when {
            idx == 0 -> "#38bdf8"
            stepRank == 50 -> "#10b981"
            isKey -> "#fde047"
            else -> "#f59e0b"
        }
        appendLine(
            "              <circle cx=\"${pt.first.format(1)}\" cy=\"${pt.second.format(1)}\" " +
                "r=\"$radius\" fill=\"$dotFill\" stroke=\"#0f172a\" stroke-width=\"1.2\" />"
        )
    }

    // Bottom axis caption
    appendLine(
        "              <text x=\"${((plotLeft + plotRight) / 2.0).format(1)}\" y=\"${(svgHeight - 4).toDouble().format(1)}\" " +
            "text-anchor=\"middle\" fill=\"#64748b\" font-size=\"10\" font-family=\"system-ui, -apple-system, sans-serif\">" +
            "Waitlist 5-Slot Cumulative Milestones (Outright Lottery 1–$DEFAULT_LOTTERY_SPOTS + Waitlist 1..k)</text>"
    )

    appendLine("            </svg>")
    appendLine("          </div>")
}

private fun StringBuilder.appendSummarySection(
    categories: List<Category>,
    steps: List<Int>,
    simulatedOutcomes: LotteryOutcomeProbabilities,
    formulaOutcomes: LotteryOutcomeProbabilities,
) {
    appendLine("    <section class=\"summary-card\" id=\"summary-table\">")
    appendLine("      <h2>All Ticket Buckets — Cumulative Selection Matrix (Outright &amp; 5-Slot Waitlist Steps)</h2>")
    appendLine("      <p class=\"summary-sub\">Each cell shows the <strong>total cumulative probability</strong> of being selected either outright in the Main Lottery (1–$DEFAULT_LOTTERY_SPOTS) or within the first <code>k</code> spots on the Waitlist.</p>")
    appendLine("      <div class=\"table-scroll\">")
    appendLine("        <table class=\"matrix-table\">")
    appendLine("          <thead>")
    appendLine("            <tr>")
    appendLine("              <th>Tickets</th>")
    appendLine("              <th>Entrants</th>")
    appendLine("              <th>Outright</th>")
    for (stepEnd in steps) {
        appendLine("              <th>+WL &le;$stepEnd</th>")
    }
    appendLine("            </tr>")
    appendLine("          </thead>")
    appendLine("          <tbody>")
    for (cat in categories) {
        val t = cat.tickets
        val mainMc = simulatedOutcomes.mainLottery.getValue(t)
        appendLine("            <tr>")
        appendLine("              <td><strong>${"%,d".format(t)}</strong></td>")
        appendLine("              <td>${"%,d".format(cat.entrants)}</td>")
        appendLine("              <td class=\"cell-outright\"><strong>${(mainMc * 100.0).format(2)}%</strong></td>")
        for (stepEnd in steps) {
            val wlCum = simulatedOutcomes.waitlistCumulativeBy5[stepEnd]?.get(t)
                ?: formulaOutcomes.waitlistCumulativeBy5[stepEnd]?.get(t)
                ?: 0.0
            val combined = (mainMc + wlCum) * 100.0
            val cellClass = if (stepEnd == 10 || stepEnd == 25 || stepEnd == 50 || stepEnd == 75) {
                " class=\"cell-milestone\""
            } else {
                ""
            }
            appendLine("              <td$cellClass>${combined.format(2)}%</td>")
        }
        appendLine("            </tr>")
    }
    appendLine("          </tbody>")
    appendLine("        </table>")
    appendLine("      </div>")
    appendLine("    </section>")
}

/**
 * Escapes untrusted or dynamic strings for safe HTML text and attribute rendering.
 */
internal fun escapeHtml(raw: String): String = buildString(raw.length) {
    for (ch in raw) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}

private const val DASHBOARD_CSS = """
    :root {
      color-scheme: dark;
      --bg-page: #070b14;
      --bg-card: #0f172a;
      --bg-card-inner: #090e1a;
      --border-subtle: #1e293b;
      --border-strong: #334155;
      --text-main: #f8fafc;
      --text-muted: #94a3b8;
      --accent-blue: #38bdf8;
      --accent-gold: #f59e0b;
      --accent-emerald: #10b981;
    }
    * {
      box-sizing: border-box;
    }
    body {
      margin: 0;
      padding: 0;
      background: radial-gradient(circle at 50% 0%, #172554 0%, var(--bg-page) 55%);
      color: var(--text-main);
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      line-height: 1.5;
    }
    .page-shell {
      max-width: 1440px;
      margin: 0 auto;
      padding: 28px 20px 60px;
    }
    .hero-card {
      background: linear-gradient(135deg, rgba(30, 41, 59, 0.92), rgba(15, 23, 42, 0.96));
      border: 1px solid var(--border-strong);
      border-radius: 18px;
      padding: 26px 28px;
      box-shadow: 0 18px 40px rgba(0, 0, 0, 0.45);
      margin-bottom: 20px;
    }
    .hero-top {
      display: flex;
      align-items: center;
      gap: 20px;
      flex-wrap: wrap;
    }
    .buckle-badge {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      width: 74px;
      height: 58px;
      border-radius: 14px;
      background: linear-gradient(135deg, #fde047, #f59e0b, #b45309);
      color: #0f172a;
      font-weight: 900;
      box-shadow: inset 0 0 0 3px #0f172a;
      flex-shrink: 0;
    }
    .buckle-title {
      font-size: 16px;
      letter-spacing: 0.8px;
      line-height: 1.1;
    }
    .buckle-sub {
      font-size: 10px;
      letter-spacing: 0.6px;
    }
    h1 {
      margin: 0 0 6px;
      font-size: clamp(1.35rem, 2.4vw, 1.95rem);
      font-weight: 800;
      letter-spacing: -0.02em;
    }
    .hero-subtitle {
      margin: 0;
      color: #cbd5e1;
      font-size: 0.96rem;
    }
    .stat-strip {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(175px, 1fr));
      gap: 12px;
      margin-top: 20px;
      padding-top: 18px;
      border-top: 1px solid var(--border-subtle);
    }
    .stat-pill {
      background: rgba(9, 14, 26, 0.65);
      border: 1px solid var(--border-subtle);
      border-radius: 12px;
      padding: 10px 14px;
      display: flex;
      flex-direction: column;
    }
    .stat-label {
      font-size: 0.75rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-muted);
    }
    .stat-value {
      font-size: 1.08rem;
      font-weight: 700;
      color: #f8fafc;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      margin-top: 2px;
    }
    .tier-nav {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      background: rgba(15, 23, 42, 0.85);
      border: 1px solid var(--border-subtle);
      border-radius: 14px;
      padding: 12px 16px;
      margin-bottom: 16px;
    }
    .tier-nav-label {
      font-size: 0.82rem;
      font-weight: 700;
      color: var(--text-muted);
      margin-right: 4px;
    }
    .tier-chip {
      text-decoration: none;
      color: #e2e8f0;
      background: #1e293b;
      border: 1px solid #334155;
      border-radius: 999px;
      padding: 4px 12px;
      font-size: 0.82rem;
      font-weight: 600;
      transition: background 0.15s ease, border-color 0.15s ease;
    }
    .tier-chip:hover {
      background: #0284c7;
      border-color: #38bdf8;
      color: #ffffff;
    }
    .tier-chip-table {
      background: #3b0764;
      border-color: #a855f7;
      color: #f3e8ff;
    }
    .legend-banner {
      display: flex;
      flex-wrap: wrap;
      gap: 18px;
      background: rgba(15, 23, 42, 0.75);
      border: 1px solid var(--border-subtle);
      border-radius: 12px;
      padding: 11px 16px;
      margin-bottom: 22px;
      font-size: 0.85rem;
      color: #cbd5e1;
    }
    .legend-item {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .swatch {
      width: 14px;
      height: 14px;
      border-radius: 4px;
      display: inline-block;
      flex-shrink: 0;
    }
    .swatch-main {
      background: #0ea5e9;
    }
    .swatch-wl {
      background: #fbbf24;
    }
    .swatch-key {
      background: #10b981;
    }
    .bucket-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(560px, 1fr));
      gap: 22px;
    }
    @media (max-width: 640px) {
      .bucket-grid {
        grid-template-columns: 1fr;
      }
    }
    .bucket-card-container {
      container-type: inline-size;
      content-visibility: auto;
      contain-intrinsic-size: auto 560px auto 430px;
    }
    @supports not (content-visibility: auto) {
      .bucket-card-container {
        contain: layout style paint;
      }
    }
    .bucket-card {
      background: var(--bg-card);
      border: 1px solid var(--border-strong);
      border-radius: 16px;
      padding: 20px 22px;
      box-shadow: 0 10px 26px rgba(0, 0, 0, 0.35);
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .bucket-title-row {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
    .bucket-title-row h2 {
      margin: 0;
      font-size: 1.32rem;
      font-weight: 800;
      color: #f8fafc;
    }
    .year-badge {
      background: #1e293b;
      border: 1px solid #475569;
      color: #bae6fd;
      border-radius: 999px;
      padding: 2px 10px;
      font-size: 0.78rem;
      font-weight: 600;
    }
    .bucket-meta {
      margin: 4px 0 0;
      font-size: 0.86rem;
      color: var(--text-muted);
    }
    .kpi-strip {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 8px;
    }
    @container (min-width: 520px) {
      .kpi-strip {
        grid-template-columns: repeat(5, 1fr);
      }
    }
    .kpi-box {
      background: var(--bg-card-inner);
      border: 1px solid var(--border-subtle);
      border-radius: 10px;
      padding: 8px 10px;
      display: flex;
      flex-direction: column;
    }
    .kpi-outright {
      border-color: rgba(56, 189, 248, 0.5);
      background: rgba(14, 165, 233, 0.1);
    }
    .kpi-highlight {
      border-color: rgba(16, 185, 129, 0.55);
      background: rgba(16, 185, 129, 0.1);
    }
    .kpi-title {
      font-size: 0.7rem;
      color: var(--text-muted);
      font-weight: 600;
    }
    .kpi-value {
      font-size: 1.02rem;
      font-weight: 800;
      color: #f8fafc;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      margin: 2px 0;
    }
    .kpi-sub {
      font-size: 0.68rem;
      color: #cbd5e1;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    }
    .chart-wrap {
      background: var(--bg-card-inner);
      border: 1px solid var(--border-subtle);
      border-radius: 12px;
      padding: 10px 8px 6px;
    }
    .bar-group {
      cursor: pointer;
    }
    .bar-group:hover rect {
      filter: brightness(1.22);
    }
    .bucket-details {
      font-size: 0.84rem;
      color: #cbd5e1;
    }
    .bucket-details summary {
      cursor: pointer;
      color: #7dd3fc;
      font-weight: 600;
      padding: 4px 0;
    }
    .table-scroll {
      overflow-x: auto;
      margin-top: 8px;
    }
    .mini-table, .matrix-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.8rem;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    }
    .mini-table th, .mini-table td,
    .matrix-table th, .matrix-table td {
      padding: 6px 8px;
      text-align: right;
      border-bottom: 1px solid var(--border-subtle);
      white-space: nowrap;
    }
    .mini-table th:first-child, .mini-table td:first-child {
      text-align: left;
      font-family: system-ui, -apple-system, sans-serif;
    }
    .mini-table thead th, .matrix-table thead th {
      color: var(--text-muted);
      background: rgba(15, 23, 42, 0.9);
      font-weight: 700;
    }
    .row-outright {
      background: rgba(14, 165, 233, 0.12);
    }
    .row-milestone, .cell-milestone {
      background: rgba(245, 158, 11, 0.12);
      color: #fde68a;
      font-weight: 700;
    }
    .cell-outright {
      background: rgba(14, 165, 233, 0.14);
      color: #7dd3fc;
    }
    .summary-card {
      margin-top: 28px;
      background: var(--bg-card);
      border: 1px solid var(--border-strong);
      border-radius: 16px;
      padding: 22px 24px;
    }
    .summary-card h2 {
      margin: 0 0 6px;
      font-size: 1.25rem;
    }
    .summary-sub {
      margin: 0 0 14px;
      color: var(--text-muted);
      font-size: 0.9rem;
    }
    .page-footer {
      margin-top: 32px;
      text-align: center;
      color: var(--text-muted);
      font-size: 0.85rem;
    }
"""
