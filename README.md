# Western States 100 (WSER) Lottery & Waitlist Probability Calculator

A high-performance Kotlin calculator that computes **exact** and **simulated** selection probabilities for the [Western States Endurance Run (WSER)](https://www.wser.org/) 100-mile lottery and waitlist.

---

## How the WSER Lottery & Waitlist Work

1. **Exponential Ticket Weighting**: An entrant with $n$ consecutive years in the lottery receives $2^{n-1}$ tickets ($1, 2, 4, 8, \dots, 1024$).
2. **Sampling Without Replacement of Athletes**: Tickets are drawn one at a time uniformly at random from the hat. When an athlete's ticket is drawn:
   - That athlete claims the next available spot.
   - All remaining $(k - 1)$ tickets belonging to that same athlete are **ignored** (effectively removed) for the remainder of the draw.
3. **Main Lottery & Waitlist**:
   - The first **260** unique athletes drawn win a spot in the **Main Lottery**.
   - The next **50 to 75** unique athletes drawn (draw positions `261` through `335`) are placed in order on the **Waitlist**.

---

## Dual-Solver Architecture

This project implements two independent methods to compute selection probabilities and cross-validates them against each other:

### 1. Zero-Allocation Parallel Monte Carlo Simulator (`Main.kt`)
* **Flat Primitive Lookup Tables**: Pre-maps every ticket index `[0, totalTickets)` into two contiguous `IntArray` tables (`ticketToAthleteId` and `ticketToCategoryIdx`) to eliminate object pointer chasing.
* **Generation-Stamped Deduplication**: Each worker coroutine maintains a thread-local `IntArray(totalAthletes)` storing the simulation index at which an athlete was already selected, avoiding `HashSet` allocations or array clearing between trials.
* **L1-Cache-Resident Rank Accumulator**: Tracks wins across every exact draw rank (`0 until 335`) and ticket category in a `29 KB` `LongArray`, enabling instant slicing of the Main Lottery (`1..260`), cumulative waitlist thresholds (`1..10`, `1..25`, `1..50`, `1..75`), and 10-runner waitlist buckets (`1-10`, `11-20`, ..., `71-75`).
* **Performance**: Runs **1,000,000 full lottery + waitlist trials** (~335 unique runners drawn per trial from 73,539 tickets) in **~0.81 seconds** on Apple Silicon.

### 2. Analytical Solver via Wallenius' Noncentral Hypergeometric Distribution (`GemSolver2.kt`)
* **Why Simple Hypergeometric Formulas Fail**: Treating non-winning tickets as static ignores that high-ticket runners are disproportionately selected early, removing all of their duplicate tickets from the hat and increasing the effective odds of lower-ticket runners in later draws.
* **Exponential Race Representation**: Models each athlete $j$ with $w_j$ tickets as having an independent arrival time $T_j \sim \text{Exponential}(w_j)$. Selecting $M$ unique athletes without replacement is mathematically identical to selecting the $M$ athletes with the smallest arrival times $T_j$.
* **Continuity-Corrected Edgeworth Integration**: Uses Newton-Raphson to locate the critical time $t^*$ where the expected number of prior winners is $M - 0.5$, then integrates the conditional survival distribution across $[t^* - 8\sigma_t, t^* + 8\sigma_t]$ in **~6.8 ms**.

---

## 2027 WSER Lottery Results (`1,000,000` Trials vs. Analytical Formula)

Source data: [`https://www.wser.org/lottery2027.html`](https://www.wser.org/lottery2027.html) (**6,104 entrants**, **73,539 total tickets**).

### Cumulative Lottery & Waitlist Probabilities

| Tickets | Entrants | Total Tickets | Main Lottery (`1–260`) MC / Formula | WL First 10 (`1–10`) | WL Up to 25th (`1–25`) | WL Up to 50th (`1–50`) MC / Formula | WL Up to 75th (`1–75`) MC / Formula |
| ---: | ---: | ---: | :---: | ---: | ---: | :---: | :---: |
| **1** | 1,717 | 1,717 | **0.427%** / 0.428% | 0.019% | 0.049% | 0.100% / 0.100% | 0.151% / 0.151% |
| **2** | 1,435 | 2,870 | **0.853%** / 0.853% | 0.039% | 0.098% | 0.198% / 0.198% | 0.301% / 0.301% |
| **4** | 998 | 3,992 | **1.699%** / 1.699% | 0.077% | 0.194% | 0.393% / 0.393% | 0.597% / 0.597% |
| **8** | 778 | 6,224 | **3.370%** / 3.370% | 0.151% | 0.380% | 0.771% / 0.770% | 1.170% / 1.170% |
| **16** | 511 | 8,176 | **6.627%** / 6.626% | 0.291% | 0.733% | 1.483% / 1.483% | 2.248% / 2.247% |
| **32** | 342 | 10,944 | **12.816%** / 12.815% | 0.544% | 1.365% | 2.748% / 2.747% | 4.146% / 4.147% |
| **64** | 175 | 11,200 | **23.993%** / 23.993% | 0.946% | 2.362% | 4.715% / 4.715% | 7.057% / 7.059% |
| **128** | 94 | 12,032 | **42.235%** / 42.238% | 1.426% | 3.531% | 6.942% / 6.945% | 10.227% / 10.231% |
| **256** | 46 | 11,776 | **66.630%** / 66.636% | 1.628% | 3.955% | 7.534% / 7.534% | 10.765% / 10.763% |
| **512** | 7 | 3,584 | **88.826%** / 88.838% | 1.062% | 2.481% | 4.465% / 4.461% | 6.035% / 6.027% |
| **1024** | 1 | 1,024 | **98.733%** / 98.732% | 0.231% | 0.498% | 0.808% / 0.808% | 0.997% / 0.997% |

### 10-Runner Waitlist Buckets (Monte Carlo `1,000,000` Trials)

| Tickets | WL `1–10` | WL `11–20` | WL `21–30` | WL `31–40` | WL `41–50` | WL `51–60` | WL `61–70` | WL `71–75` |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **1** | 0.019% | 0.020% | 0.020% | 0.020% | 0.020% | 0.020% | 0.021% | 0.010% |
| **2** | 0.039% | 0.039% | 0.040% | 0.040% | 0.041% | 0.041% | 0.042% | 0.021% |
| **4** | 0.077% | 0.078% | 0.078% | 0.079% | 0.080% | 0.081% | 0.082% | 0.041% |
| **8** | 0.151% | 0.153% | 0.154% | 0.155% | 0.157% | 0.158% | 0.160% | 0.080% |
| **16** | 0.291% | 0.294% | 0.296% | 0.299% | 0.302% | 0.304% | 0.307% | 0.154% |
| **32** | 0.544% | 0.546% | 0.550% | 0.552% | 0.555% | 0.557% | 0.560% | 0.281% |
| **64** | 0.946% | 0.945% | 0.943% | 0.941% | 0.940% | 0.939% | 0.937% | 0.467% |
| **128** | 1.426% | 1.409% | 1.388% | 1.368% | 1.350% | 1.328% | 1.311% | 0.647% |
| **256** | 1.628% | 1.566% | 1.504% | 1.447% | 1.389% | 1.330% | 1.282% | 0.620% |
| **512** | 1.062% | 0.970% | 0.887% | 0.813% | 0.734% | 0.674% | 0.611% | 0.285% |
| **1024** | 0.231% | 0.188% | 0.154% | 0.132% | 0.103% | 0.084% | 0.072% | 0.033% |

---

## Getting Started

### Prerequisites
* **JDK 21** (configured via Gradle `jvmToolchain(21)`)

### Run the Simulator & Analytical Solver
```bash
./gradlew run
```

### Force a Fresh Download of Source Data from `wser.org`
By default, the HTML table from `https://www.wser.org/lottery2027.html` is cached locally at `~/tmp/lottery2027.html`. To bypass the local cache and re-download the latest data:
```bash
./gradlew run --args="--force-download"
```

### Run Unit Tests (with Kotlin Power-Assert)
The test suite uses the [Kotlin Power-Assert compiler plugin](https://kotlinlang.org/docs/power-assert.html) to verify HTML table parsing, boundary invariants, exact small-pool probabilities, waitlist rank conservation, and convergence between the Monte Carlo engine and the analytical Wallenius solver:
```bash
./gradlew test
```

---

## License

Licensed under the [MIT License](LICENSE).
