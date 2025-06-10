package games.planetwars.runners

import games.planetwars.agents.PartialObservationAgent
import games.planetwars.agents.random.PartialObservationBetterRandomAgent
import games.planetwars.agents.random.PartialObservationPureRandomAgent
import games.planetwars.agents.strategic.TeamTitansPartialAgentV1
import games.planetwars.agents.strategic.TeamTitansPartialAgentV2
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.KClass
import kotlin.random.Random
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Parameter variance mode for competition testing
 */
//enum class ParameterVarianceMode {
//    FIXED,              // Use fixed parameters (development/debugging)
//    COMPETITION_RANGES, // Use official GECCO 2025 competition parameter ranges
//    EXTREME_STRESS     // Use even more extreme ranges for stress testing
//}

/**
 * An improved version of PartialObservationRoundRobinLeague that uses coroutines for parallel execution
 * and supports GECCO 2025 competition parameter variance
 */
data class PartialObservationRoundRobinLeagueImproved(
    val agentFactories: List<() -> PartialObservationAgent>, // Use factories to create fresh instances
    val gamesPerPair: Int = 10,
    val baseGameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 400), // Base parameters for FIXED mode
    val parameterVarianceMode: ParameterVarianceMode = ParameterVarianceMode.COMPETITION_RANGES,
    val parallelism: Int = Runtime.getRuntime().availableProcessors(), // Default to number of available cores
    val verboseGameLogs: Boolean = false, // Control verbose logging of individual games
    val showParameterVariance: Boolean = true, // Show parameter details in logs
    val randomSeed: Long? = null, // Optional seed for reproducible parameter generation
    val enableDetailedParameterAnalysis: Boolean = true // Enable detailed parameter-performance tracking
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(parallelism)
    private val outputLock = Any()
    private val parameterStats = ParameterStats()
    private val random = Random(randomSeed ?: System.currentTimeMillis())
    private val detailedGameResults = mutableListOf<GameResult>()
    private var gameIdCounter = AtomicInteger(0)

    /**
     * Generate game parameters based on the variance mode
     */
    private fun generateGameParams(): GameParams {
        return when (parameterVarianceMode) {
            ParameterVarianceMode.FIXED -> baseGameParams
            
            ParameterVarianceMode.COMPETITION_RANGES -> {
                // OFFICIAL GECCO 2025 Competition Ranges (CONFIRMED)
                GameParams(
                    numPlanets = random.nextInt(10, 31), // 10-30 planets
                    initialNeutralRatio = random.nextDouble(0.25, 0.35), // ~0.3 (25%-35% range)
                    maxTicks = random.nextInt(400, 1501), // 400-1500 ticks (reasonable range)
                    minInitialShipsPerPlanet = 2, // Fixed minimum
                    maxInitialShipsPerPlanet = random.nextInt(10, 21), // 10-20 ships
                    minGrowthRate = 0.02, // Fixed minimum
                    maxGrowthRate = random.nextDouble(0.05, 0.21), // 0.05-0.2 growth rate
                    transporterSpeed = random.nextDouble(2.0, 5.1), // 2.0-5.0 speed
                    width = baseGameParams.width,
                    height = baseGameParams.height,
                    edgeSeparation = baseGameParams.edgeSeparation,
                    radialSeparation = baseGameParams.radialSeparation,
                    growthToRadiusFactor = baseGameParams.growthToRadiusFactor,
                    newMapEachRun = baseGameParams.newMapEachRun
                )
            }
            
            ParameterVarianceMode.EXTREME_STRESS -> {
                GameParams(
                    numPlanets = random.nextInt(5, 41), // 5-40 planets
                    initialNeutralRatio = random.nextDouble(0.1, 0.7), // 10%-70% neutral
                    maxTicks = random.nextInt(200, 3001), // 200-3000 ticks
                    minInitialShipsPerPlanet = random.nextInt(1, 5), // 1-4 ships
                    maxInitialShipsPerPlanet = random.nextInt(15, 31), // 15-30 ships
                    minGrowthRate = random.nextDouble(0.01, 0.05), // 0.01-0.05
                    maxGrowthRate = random.nextDouble(0.1, 0.31), // 0.1-0.3 growth rate
                    transporterSpeed = random.nextDouble(1.0, 8.1), // 1.0-8.0 speed
                    width = baseGameParams.width,
                    height = baseGameParams.height,
                    edgeSeparation = baseGameParams.edgeSeparation,
                    radialSeparation = baseGameParams.radialSeparation,
                    growthToRadiusFactor = baseGameParams.growthToRadiusFactor,
                    newMapEachRun = baseGameParams.newMapEachRun
                )
            }
        }
    }

    /**
     * Run a matchup between two agents in parallel with parameter variance
     */
    suspend fun runPairParallel(agentFactory1: () -> PartialObservationAgent, agentFactory2: () -> PartialObservationAgent): Map<Player, Int> = coroutineScope {
        val agent1Type = agentFactory1().getAgentType()
        val agent2Type = agentFactory2().getAgentType()
        
        synchronized(outputLock) {
            println("\nRunning ${gamesPerPair} games in parallel: $agent1Type vs $agent2Type")
            println("Parameter variance mode: $parameterVarianceMode")
            if (parameterVarianceMode == ParameterVarianceMode.FIXED && !verboseGameLogs) {
                printGameParams(baseGameParams)
            }
        }
        
        val startTime = System.currentTimeMillis()
        val aggregateResults = ConcurrentHashMap<Player, AtomicInteger>().apply {
            put(Player.Player1, AtomicInteger(0))
            put(Player.Player2, AtomicInteger(0))
            put(Player.Neutral, AtomicInteger(0))
        }
        
        val jobs = List(gamesPerPair) { gameNumber ->
            async(dispatcher) {
                try {
                    val gameParams = generateGameParams()
                    
                    synchronized(parameterStats) {
                        parameterStats.recordGame(gameParams)
                    }
                    
                    val agent1 = agentFactory1()
                    val agent2 = agentFactory2()
                    
                    val gameStartTime = System.currentTimeMillis()
                    val gameRunner = PartialObservationGameRunner(agent1, agent2, gameParams)
                    val finalModel = gameRunner.runGame()
                    val winner = finalModel.getLeader()
                    val gameElapsedTime = (System.currentTimeMillis() - gameStartTime) / 1000.0
                    
                    if (enableDetailedParameterAnalysis) {
                        val gameResult = GameResult(
                            gameId = gameIdCounter.incrementAndGet(),
                            agent1Type = agent1.getAgentType(),
                            agent2Type = agent2.getAgentType(),
                            winner = winner,
                            gameParams = gameParams,
                            gameDurationSeconds = gameElapsedTime,
                            finalTick = finalModel.state.gameTick
                        )
                        
                        synchronized(detailedGameResults) {
                            detailedGameResults.add(gameResult)
                        }
                    }
                    
                    aggregateResults[winner]!!.incrementAndGet()
                    
                    if (verboseGameLogs) {
                        synchronized(outputLock) {
                            val paramInfo = if (showParameterVariance && parameterVarianceMode != ParameterVarianceMode.FIXED) {
                                " [P:${gameParams.numPlanets} N:${"%.2f".format(gameParams.initialNeutralRatio)} S:${"%.1f".format(gameParams.transporterSpeed)} G:${"%.3f".format(gameParams.maxGrowthRate)}]"
                            } else ""
                            
                            println("Game $gameNumber: Winner=${winner} " +
                                    "(${if (winner == Player.Player1) agent1.getAgentType() else if (winner == Player.Player2) agent2.getAgentType() else "Draw"}) " +
                                    "[${gameElapsedTime}s]$paramInfo")
                        }
                    }
                } catch (e: Exception) {
                    synchronized(outputLock) {
                        println("ERROR in game $gameNumber: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
        
        jobs.awaitAll()
        
        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0
        synchronized(outputLock) {
            println("Match summary - P1($agent1Type)=${aggregateResults[Player.Player1]!!.get()} | " +
                    "P2($agent2Type)=${aggregateResults[Player.Player2]!!.get()} | " +
                    "Draws=${aggregateResults[Player.Neutral]!!.get()} (${elapsedTime}s)")
        }
        
        mapOf(
            Player.Player1 to aggregateResults[Player.Player1]!!.get(),
            Player.Player2 to aggregateResults[Player.Player2]!!.get(),
            Player.Neutral to aggregateResults[Player.Neutral]!!.get()
        )
    }

    /**
     * Run the entire round robin tournament
     */
    suspend fun runRoundRobinParallel(): Map<String, LeagueEntry> = coroutineScope {
        val t = System.currentTimeMillis()
        val agentTypes = agentFactories.map { it().getAgentType() }
        
        synchronized(outputLock) {
            println("\n----- Starting Parallel Round Robin Tournament (Partial Observation) -----")
            println("Agents: $agentTypes")
            println("Games per matchup: $gamesPerPair")
            println("Parallelism: $parallelism threads")
            println("Parameter variance mode: $parameterVarianceMode")
            
            when (parameterVarianceMode) {
                ParameterVarianceMode.FIXED -> {
                    println("Using FIXED parameters:")
                    printGameParams(baseGameParams)
                }
                ParameterVarianceMode.COMPETITION_RANGES -> {
                    println("Using OFFICIAL GECCO 2025 Competition parameter ranges:")
                    println("  • Planets: 10-30 (variable each game)")
                    println("  • Neutral Ratio: ~0.3 (25%-35% range)")
                    println("  • Growth Rate: 0.05-0.2")
                    println("  • Transporter Speed: 2.0-5.0")
                    println("  • Game Duration: 400-1500 ticks")
                }
                ParameterVarianceMode.EXTREME_STRESS -> {
                    println("Using EXTREME STRESS testing ranges:")
                    println("  • Planets: 5-40")
                    println("  • Neutral Ratio: 10%-70%")
                    println("  • Growth Rate: 0.1-0.3")
                    println("  • Transporter Speed: 1.0-8.0")
                    println("  • Game Duration: 200-3000 ticks")
                }
            }
            randomSeed?.let { println("Random seed: $it") }
        }
        
        val scores = ConcurrentHashMap<String, LeagueEntry>()
        for (agentType in agentTypes) {
            scores[agentType] = LeagueEntry(agentType)
        }
        
        val totalMatchups = agentFactories.size * (agentFactories.size - 1)
        val completedMatchups = AtomicInteger(0)
        
        val matchups = mutableListOf<Pair<Int, Int>>()
        for (i in agentFactories.indices) {
            for (j in agentFactories.indices) {
                if (i != j) {
                    matchups.add(Pair(i, j))
                }
            }
        }
        
        matchups.chunked(parallelism).forEach { chunk ->
            val chunkJobs = chunk.map { (i, j) ->
                async(dispatcher) {
                    try {
                        val agentFactory1 = agentFactories[i]
                        val agentFactory2 = agentFactories[j]
                        
                        val agent1Type = agentFactory1().getAgentType()
                        val agent2Type = agentFactory2().getAgentType()
                        
                        val result = runPairParallel(agentFactory1, agentFactory2)
                        
                        synchronized(scores) {
                            val leagueEntry1 = scores[agent1Type]!!
                            val leagueEntry2 = scores[agent2Type]!!
                            leagueEntry1.points += result[Player.Player1]!!
                            leagueEntry2.points += result[Player.Player2]!!
                            leagueEntry1.nGames += gamesPerPair
                            leagueEntry2.nGames += gamesPerPair
                        }
                        
                        val current = completedMatchups.incrementAndGet()
                        val progressPercent = (current * 100.0 / totalMatchups).toInt()
                        synchronized(outputLock) {
                            println("Progress: $current/$totalMatchups matchups ($progressPercent%)")
                        }
                        
                        if (current % (totalMatchups / 4).coerceAtLeast(1) == 0 || current == totalMatchups) {
                            printCurrentStandings(scores)
                        }
                    } catch (e: Exception) {
                        synchronized(outputLock) {
                            println("ERROR in matchup: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
            
            runBlocking { 
                chunkJobs.awaitAll()
            }
        }
        
        val totalTime = (System.currentTimeMillis() - t) / 1000.0
        synchronized(outputLock) {
            println("\n----- Parallel Round Robin Tournament Complete -----")
            println("Total time: $totalTime seconds (${totalTime / 60} minutes)")
            
            if (parameterVarianceMode != ParameterVarianceMode.FIXED) {
                parameterStats.printSummary()
            }
        }
        
        scores.toMap()
    }

    private fun printCurrentStandings(scores: ConcurrentHashMap<String, LeagueEntry>) {
        synchronized(outputLock) {
            println("\n----- Current Standings -----")
            val currentRankings = scores.toList().sortedByDescending { it.second.points }.toMap()
            for ((index, entry) in currentRankings.values.withIndex()) {
                println("${index + 1}. ${entry.agentName}: ${entry.points} points (${entry.nGames} games)")
            }
            println("--------------------------\n")
        }
    }

    companion object {
        fun createLeagueWithAgentClasses(
            agentClasses: List<KClass<out PartialObservationAgent>>, 
            gamesPerPair: Int = 100,
            baseGameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 400),
            parameterVarianceMode: ParameterVarianceMode = ParameterVarianceMode.COMPETITION_RANGES,
            parallelism: Int = Runtime.getRuntime().availableProcessors(),
            verboseGameLogs: Boolean = false,
            showParameterVariance: Boolean = true,
            randomSeed: Long? = null
        ): PartialObservationRoundRobinLeagueImproved {
            val factories = agentClasses.map { agentClass ->
                { 
                    agentClass.primaryConstructor?.call() ?: 
                    agentClass.java.getDeclaredConstructor().newInstance()
                }
            }
            
            return PartialObservationRoundRobinLeagueImproved(
                agentFactories = factories,
                gamesPerPair = gamesPerPair,
                baseGameParams = baseGameParams,
                parameterVarianceMode = parameterVarianceMode,
                parallelism = parallelism,
                verboseGameLogs = verboseGameLogs,
                showParameterVariance = showParameterVariance,
                randomSeed = randomSeed
            )
        }
        
        fun createCompetitionLeague(
            agentFactories: List<() -> PartialObservationAgent>,
            gamesPerPair: Int = 100,
            parallelism: Int = Runtime.getRuntime().availableProcessors(),
            verboseGameLogs: Boolean = false,
            randomSeed: Long? = null
        ): PartialObservationRoundRobinLeagueImproved {
            return PartialObservationRoundRobinLeagueImproved(
                agentFactories = agentFactories,
                gamesPerPair = gamesPerPair,
                baseGameParams = GameParams(),
                parameterVarianceMode = ParameterVarianceMode.COMPETITION_RANGES,
                parallelism = parallelism,
                verboseGameLogs = verboseGameLogs,
                showParameterVariance = true,
                randomSeed = randomSeed,
                enableDetailedParameterAnalysis = true
            )
        }
    }
}

/**
 * Main function to run the competition mode only - simplified for GECCO 2025
 */
fun main(args: Array<String>) = runBlocking {
    println("🎯 GECCO 2025 Competition League - Partial Observation Parameter Analysis")
    println("=".repeat(60))
    
    ensureResultsDirectoryStructure()
    
    val gamesPerPair = 5
    val parallelism = 10
    
    val agentFactories = listOf<() -> PartialObservationAgent>(
        { PartialObservationPureRandomAgent() },
        { PartialObservationBetterRandomAgent() },
        { TeamTitansPartialAgentV1()},
        { TeamTitansPartialAgentV2()},
    )
    
    println("Games per pair: $gamesPerPair")
    println("Agents: ${agentFactories.map { it().getAgentType() }}")
    
    runCompetitionMode(agentFactories, gamesPerPair, parallelism)
}

/**
 * Competition mode: Official GECCO 2025 parameter ranges
 */
suspend fun runCompetitionMode(
    agentFactories: List<() -> PartialObservationAgent>, 
    gamesPerPair: Int, 
    parallelism: Int
) {
    println("\n🏆 COMPETITION MODE - GECCO 2025 Official Parameter Ranges (Partial Observation)")
    
    val league = PartialObservationRoundRobinLeagueImproved.createCompetitionLeague(
        agentFactories = agentFactories,
        gamesPerPair = gamesPerPair,
        parallelism = parallelism,
        verboseGameLogs = false,
        randomSeed = System.currentTimeMillis()
    )
    
    val timeTaken = measureTimeMillis {
        val results = league.runRoundRobinParallel()
        saveAndDisplayResults(results, "GECCO 2025 Competition Preparation - Partial Observation")
    }
    
    println("\n🎯 Competition preparation complete in ${timeTaken / 1000.0} seconds")
    println("📊 Agents tested against full GECCO 2025 parameter ranges!")
}

private fun saveAndDisplayResults(results: Map<String, LeagueEntry>, testName: String) {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val testNameFormatted = testName.replace(" ", "_").replace("/", "-")
    val resultsDir = "results/tournaments/$testNameFormatted/$timestamp/"
    
    println("\n=== $testName Results ===")
    val writer = LeagueWriter(outputDir = resultsDir, filename = "league_results.md")
    val leagueResult = LeagueResult(results.values.toList())
    val markdownContent = writer.generateMarkdownTable(leagueResult)
    writer.saveMarkdownToFile(markdownContent)
    
    try {
        val dir = File(resultsDir)
        if (!dir.exists()) dir.mkdirs()
        
        val summaryContent = StringBuilder()
        summaryContent.appendLine("$testName - Tournament Summary")
        summaryContent.appendLine("=" .repeat(50))
        summaryContent.appendLine("Generated: ${LocalDateTime.now()}")
        summaryContent.appendLine("Total agents: ${results.size}")
        summaryContent.appendLine("Games per matchup: Variable (depends on mode)")
        summaryContent.appendLine()
        summaryContent.appendLine("Final Rankings:")
        
        val sortedResults = results.toList().sortedByDescending { it.second.points }.toMap()
        for ((index, entry) in sortedResults.values.withIndex()) {
            val winRate = entry.points / entry.nGames * 100
            summaryContent.appendLine("${index + 1}. ${entry.agentName}: ${"%.1f".format(winRate)}% win rate (${entry.points.toInt()}/${entry.nGames} games)")
        }
        
        File(resultsDir + "tournament_summary.txt").writeText(summaryContent.toString())
        println("📄 Tournament summary saved to: ${resultsDir}tournament_summary.txt")
    } catch (e: Exception) {
        println("❌ Failed to save tournament summary: ${e.message}")
    }

    println("\nFinal Rankings:")
    val sortedResults = results.toList().sortedByDescending { it.second.points }.toMap()
    for ((index, entry) in sortedResults.values.withIndex()) {
        val winRate = entry.points / entry.nGames * 100
        println("${index + 1}. ${entry.agentName} : ${"%.1f".format(winRate)}% win rate (${entry.points.toInt()}/${entry.nGames} games)")
    }
    
    println("\n📁 All results saved to: $resultsDir")
}

private fun ensureResultsDirectoryStructure() {
    val directories = listOf(
        "results/tournaments/",
        "results/parameter_analysis/",
        "results/performance_logs/"
    )
    
    for (dir in directories) {
        val directory = File(dir)
        if (!directory.exists()) {
            directory.mkdirs()
            println("📁 Created directory: $dir")
        }
    }
}