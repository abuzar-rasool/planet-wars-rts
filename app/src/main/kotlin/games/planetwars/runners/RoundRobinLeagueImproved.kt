package games.planetwars.runners

import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.evo.SimpleEvoAgent
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import games.planetwars.agents.strategic.StrategicAgent
import games.planetwars.agents.strategic.StrategicAgentImproved
import games.planetwars.agents.strategic.BocsimackoAgent
import games.planetwars.agents.strategic.StrategicAgentDynamic
import games.planetwars.agents.strategic.TeamTitansAgent
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.KClass
import kotlin.random.Random
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Parameter variance mode for competition testing
 */
enum class ParameterVarianceMode {
    FIXED,              // Use fixed parameters (development/debugging)
    COMPETITION_RANGES, // Use official GECCO 2025 competition parameter ranges
    EXTREME_STRESS     // Use even more extreme ranges for stress testing
}

/**
 * Statistics about parameter usage across games
 */
data class ParameterStats(
    val planetCounts: MutableList<Int> = mutableListOf(),
    val neutralRatios: MutableList<Double> = mutableListOf(),
    val transporterSpeeds: MutableList<Double> = mutableListOf(),
    val maxGrowthRates: MutableList<Double> = mutableListOf(),
    val gameDurations: MutableList<Int> = mutableListOf()
) {
    fun recordGame(params: GameParams) {
        planetCounts.add(params.numPlanets)
        neutralRatios.add(params.initialNeutralRatio)
        transporterSpeeds.add(params.transporterSpeed)
        maxGrowthRates.add(params.maxGrowthRate)
        gameDurations.add(params.maxTicks)
    }
    
    fun printSummary() {
        println("\n----- Parameter Usage Statistics -----")
        println("Planet Counts: ${planetCounts.minOrNull()}-${planetCounts.maxOrNull()} (avg: ${"%.1f".format(planetCounts.average())})")
        println("Neutral Ratios: ${"%.2f".format(neutralRatios.minOrNull())}-${"%.2f".format(neutralRatios.maxOrNull())} (avg: ${"%.2f".format(neutralRatios.average())})")
        println("Transporter Speeds: ${"%.1f".format(transporterSpeeds.minOrNull())}-${"%.1f".format(transporterSpeeds.maxOrNull())} (avg: ${"%.1f".format(transporterSpeeds.average())})")
        println("Max Growth Rates: ${"%.3f".format(maxGrowthRates.minOrNull())}-${"%.3f".format(maxGrowthRates.maxOrNull())} (avg: ${"%.3f".format(maxGrowthRates.average())})")
        println("Game Durations: ${gameDurations.minOrNull()}-${gameDurations.maxOrNull()} (avg: ${"%.0f".format(gameDurations.average())})")
        println("------------------------------------")
    }
}

/**
 * Time performance data for agent decision making
 */
data class AgentTimePerformance(
    val agentType: String,
    val decisionsCount: Int,
    val avgDecisionTimeMs: Double,
    val maxDecisionTimeMs: Long,
    val minDecisionTimeMs: Long,
    val timeViolations100ms: Int, // Number of decisions > 100ms
    val timeViolations200ms: Int, // Number of decisions > 200ms
    val timeComplianceRate: Double, // Percentage of decisions under 100ms
    val parameterCategory: String = "Overall",
    val parameterRange: String = "All"
) {
    fun getCompetitionReadinessRating(): String = when {
        timeComplianceRate >= 99.0 && avgDecisionTimeMs <= 50.0 -> "🟢 EXCELLENT"
        timeComplianceRate >= 95.0 && avgDecisionTimeMs <= 75.0 -> "🟡 GOOD"
        timeComplianceRate >= 90.0 && avgDecisionTimeMs <= 90.0 -> "🟠 MARGINAL"
        else -> "🔴 POOR - NOT COMPETITION READY"
    }
}

/**
 * Head-to-head matchup performance data
 */
data class HeadToHeadResult(
    val agent1Type: String,
    val agent2Type: String,
    val agent1Wins: Int,
    val agent2Wins: Int,
    val draws: Int,
    val totalGames: Int,
    val agent1WinRate: Double,
    val parameterCategory: String = "Overall",
    val parameterRange: String = "All"
)

/**
 * Individual game result with enhanced tracking
 */
data class GameResult(
    val gameId: Int,
    val agent1Type: String,
    val agent2Type: String,
    val winner: Player,
    val gameParams: GameParams,
    val gameDurationSeconds: Double,
    val finalTick: Int,
    val agent1TimePerformance: AgentTimePerformance? = null,
    val agent2TimePerformance: AgentTimePerformance? = null,
    val invalidActionCounts: Map<String, Int> = emptyMap()
)

/**
 * Detailed performance analysis for an agent under specific parameter ranges
 */
data class AgentParameterPerformance(
    val agentName: String,
    val parameterCategory: String,
    val parameterRange: String,
    val gamesPlayed: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val winRate: Double,
    val avgGameDuration: Double
) {
    fun getPerformanceRating(): String = when {
        winRate >= 75.0 -> "🥇 EXCELLENT"
        winRate >= 65.0 -> "🥈 VERY_GOOD"
        winRate >= 55.0 -> "🥉 GOOD"
        winRate >= 45.0 -> "⚡ AVERAGE"
        winRate >= 35.0 -> "⚠️ BELOW_AVERAGE"
        else -> "❌ POOR"
    }
}

/**
 * Comprehensive parameter analysis results with enhanced metrics
 */
data class ParameterAnalysisResult(
    val agentPerformances: List<AgentParameterPerformance>,
    val gameResults: List<GameResult>,
    val timePerformances: List<AgentTimePerformance>,
    val headToHeadResults: List<HeadToHeadResult>,
    val analysisTimestamp: String,
    val totalGamesAnalyzed: Int,
    val competitionReadinessScores: Map<String, String> // Agent -> Readiness Rating
)

/**
 * An improved version of RoundRobinLeague that uses coroutines for parallel execution
 * and supports GECCO 2025 competition parameter variance
 */
data class RoundRobinLeagueImproved(
    val agentFactories: List<() -> PlanetWarsAgent>, // Use factories to create fresh instances
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
    // Synchronize console output to prevent garbled logs
    private val outputLock = Any()
    private val parameterStats = ParameterStats()
    private val random = Random(randomSeed ?: System.currentTimeMillis())
    
    // Detailed game tracking for parameter analysis
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
                    // Keep other parameters as defaults
                    width = baseGameParams.width,
                    height = baseGameParams.height,
                    edgeSeparation = baseGameParams.edgeSeparation,
                    radialSeparation = baseGameParams.radialSeparation,
                    growthToRadiusFactor = baseGameParams.growthToRadiusFactor,
                    newMapEachRun = baseGameParams.newMapEachRun
                )
            }
            
            ParameterVarianceMode.EXTREME_STRESS -> {
                // Even more extreme ranges for stress testing
                GameParams(
                    numPlanets = random.nextInt(5, 41), // 5-40 planets (beyond competition)
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
    suspend fun runPairParallel(agentFactory1: () -> PlanetWarsAgent, agentFactory2: () -> PlanetWarsAgent): Map<Player, Int> = coroutineScope {
        // Get agent types for logging (using temporary instances that won't be used for games)
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
                    // Generate parameters for this specific game
                    val gameParams = generateGameParams()
                    
                    // Record parameter usage
                    synchronized(parameterStats) {
                        parameterStats.recordGame(gameParams)
                    }
                    
                    // Create fresh instances for each game to avoid thread safety issues
                    val agent1 = agentFactory1()
                    val agent2 = agentFactory2()
                    
                    val gameStartTime = System.currentTimeMillis()
                    val gameRunner = GameRunner(agent1, agent2, gameParams)
                    val finalModel = gameRunner.runGame()
                    val winner = finalModel.getLeader()
                    val gameElapsedTime = (System.currentTimeMillis() - gameStartTime) / 1000.0
                    
                    // Record detailed game result for parameter analysis
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
                    
                    // Update results atomically
                    aggregateResults[winner]!!.incrementAndGet()
                    
                    // Log individual game result if verbose logging is enabled
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
        
        // Wait for all games to finish
        jobs.awaitAll()
        
        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0
        synchronized(outputLock) {
            println("Match summary - P1($agent1Type)=${aggregateResults[Player.Player1]!!.get()} | " +
                    "P2($agent2Type)=${aggregateResults[Player.Player2]!!.get()} | " +
                    "Draws=${aggregateResults[Player.Neutral]!!.get()} (${elapsedTime}s)")
        }
        
        // Convert AtomicInteger to Int in the result map
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
        
        // Get agent types for display (using temporary instances that won't be used for games)
        val agentTypes = agentFactories.map { it().getAgentType() }
        
        synchronized(outputLock) {
            println("\n----- Starting Parallel Round Robin Tournament -----")
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
        
        // Thread-safe scores map
        val scores = ConcurrentHashMap<String, LeagueEntry>()
        for (agentType in agentTypes) {
            scores[agentType] = LeagueEntry(agentType)
        }
        
        // Calculate total number of matchups for progress tracking
        val totalMatchups = agentFactories.size * (agentFactories.size - 1)
        val completedMatchups = AtomicInteger(0)
        
        // Create all matchup pairs
        val matchups = mutableListOf<Pair<Int, Int>>()
        for (i in agentFactories.indices) {
            for (j in agentFactories.indices) {
                if (i != j) {
                    matchups.add(Pair(i, j))
                }
            }
        }
        
        // Run each matchup (limiting to parallelism level for resource management)
        matchups.chunked(parallelism).forEach { chunk ->
            val chunkJobs = chunk.map { (i, j) ->
                async(dispatcher) {
                    try {
                        val agentFactory1 = agentFactories[i]
                        val agentFactory2 = agentFactories[j]
                        
                        // Get agent types
                        val agent1Type = agentFactory1().getAgentType()
                        val agent2Type = agentFactory2().getAgentType()
                        
                        val result = runPairParallel(agentFactory1, agentFactory2)
                        
                        // Update the league scores for each agent
                        synchronized(scores) {
                            val leagueEntry1 = scores[agent1Type]!!
                            val leagueEntry2 = scores[agent2Type]!!
                            leagueEntry1.points += result[Player.Player1]!!
                            leagueEntry2.points += result[Player.Player2]!!
                            leagueEntry1.nGames += gamesPerPair
                            leagueEntry2.nGames += gamesPerPair
                        }
                        
                        // Update progress
                        val current = completedMatchups.incrementAndGet()
                        val progressPercent = (current * 100.0 / totalMatchups).toInt()
                        synchronized(outputLock) {
                            println("Progress: $current/$totalMatchups matchups ($progressPercent%)")
                        }
                        
                        // Print current standings at intervals
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
            
            // Wait for this chunk to complete before starting the next chunk
            runBlocking { 
                chunkJobs.awaitAll()
            }
        }
        
        val totalTime = (System.currentTimeMillis() - t) / 1000.0
        synchronized(outputLock) {
            println("\n----- Parallel Round Robin Tournament Complete -----")
            println("Total time: $totalTime seconds (${totalTime / 60} minutes)")
            
            // Print parameter statistics if using variance
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
    
    /**
     * Analyze agent performance across different parameter ranges with enhanced metrics
     */
    fun analyzeParameterPerformance(): ParameterAnalysisResult {
        if (!enableDetailedParameterAnalysis || detailedGameResults.isEmpty()) {
            println("⚠️ No detailed game data available for parameter analysis")
            return ParameterAnalysisResult(emptyList(), emptyList(), emptyList(), emptyList(), 
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), 0, emptyMap())
        }
        
        val allAgents = detailedGameResults.flatMap { listOf(it.agent1Type, it.agent2Type) }.distinct()
        val performances = mutableListOf<AgentParameterPerformance>()
        
        // Analyze performance across different parameter categories
        for (agentName in allAgents) {
            performances.addAll(analyzeAgentByPlanetCount(agentName))
            performances.addAll(analyzeAgentByTransporterSpeed(agentName))
            performances.addAll(analyzeAgentByGrowthRate(agentName))
            performances.addAll(analyzeAgentByNeutralRatio(agentName))
            performances.addAll(analyzeAgentByGameDuration(agentName))
        }
        
        // Analyze time performance across parameter ranges
        val timePerformances = analyzeTimePerformanceAcrossParameters()
        
        // Analyze head-to-head matchups
        val headToHeadResults = analyzeHeadToHeadMatchups()
        
        // Calculate competition readiness scores
        val readinessScores = calculateCompetitionReadinessScores(timePerformances, performances)
        
        return ParameterAnalysisResult(
            agentPerformances = performances,
            gameResults = detailedGameResults.toList(),
            timePerformances = timePerformances,
            headToHeadResults = headToHeadResults,
            analysisTimestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            totalGamesAnalyzed = detailedGameResults.size,
            competitionReadinessScores = readinessScores
        )
    }
    
    /**
     * Analyze time performance across different parameter ranges (simulated for now)
     * TODO: Implement actual time tracking in GameRunner
     */
    private fun analyzeTimePerformanceAcrossParameters(): List<AgentTimePerformance> {
        val allAgents = detailedGameResults.flatMap { listOf(it.agent1Type, it.agent2Type) }.distinct()
        val timePerformances = mutableListOf<AgentTimePerformance>()
        
        // For now, create simulated time performance data based on game complexity
        for (agentName in allAgents) {
            val agentGames = detailedGameResults.filter { 
                it.agent1Type == agentName || it.agent2Type == agentName 
            }
            
            // Simulate time performance based on game parameters
            val avgComplexity = agentGames.map { 
                it.gameParams.numPlanets * it.gameParams.transporterSpeed / 100.0
            }.average()
            
            // Simulate realistic time performance metrics
            val simulatedAvgTime = (20.0 + avgComplexity * 15.0).coerceAtMost(120.0)
            val simulatedMaxTime = (simulatedAvgTime * 3.0).toLong()
            val simulatedMinTime = (simulatedAvgTime * 0.3).toLong()
            val simulatedViolations = if (simulatedAvgTime > 80.0) agentGames.size / 20 else 0
            val complianceRate = ((agentGames.size - simulatedViolations).toDouble() / agentGames.size) * 100.0
            
            timePerformances.add(
                AgentTimePerformance(
                    agentType = agentName,
                    decisionsCount = agentGames.size * 50, // Approximate decisions per game
                    avgDecisionTimeMs = simulatedAvgTime,
                    maxDecisionTimeMs = simulatedMaxTime,
                    minDecisionTimeMs = simulatedMinTime,
                    timeViolations100ms = simulatedViolations,
                    timeViolations200ms = simulatedViolations / 3,
                    timeComplianceRate = complianceRate
                )
            )
            
            // Add parameter-specific time analysis
            val planetRanges = listOf(
                "Small (10-15)" to { planets: Int -> planets in 10..15 },
                "Medium (16-22)" to { planets: Int -> planets in 16..22 },
                "Large (23-30)" to { planets: Int -> planets in 23..30 }
            )
            
            for ((rangeName, predicate) in planetRanges) {
                val rangeGames = agentGames.filter { predicate(it.gameParams.numPlanets) }
                if (rangeGames.isNotEmpty()) {
                    val rangeComplexity = rangeGames.map { 
                        it.gameParams.numPlanets * it.gameParams.transporterSpeed / 100.0
                    }.average()
                    
                    val rangeAvgTime = (20.0 + rangeComplexity * 15.0).coerceAtMost(120.0)
                    val rangeViolations = if (rangeAvgTime > 80.0) rangeGames.size / 20 else 0
                    val rangeComplianceRate = ((rangeGames.size - rangeViolations).toDouble() / rangeGames.size) * 100.0
                    
                    timePerformances.add(
                        AgentTimePerformance(
                            agentType = agentName,
                            decisionsCount = rangeGames.size * 50,
                            avgDecisionTimeMs = rangeAvgTime,
                            maxDecisionTimeMs = (rangeAvgTime * 3.0).toLong(),
                            minDecisionTimeMs = (rangeAvgTime * 0.3).toLong(),
                            timeViolations100ms = rangeViolations,
                            timeViolations200ms = rangeViolations / 3,
                            timeComplianceRate = rangeComplianceRate,
                            parameterCategory = "Planet Count",
                            parameterRange = rangeName
                        )
                    )
                }
            }
        }
        
        return timePerformances
    }
    
    /**
     * Analyze head-to-head matchup performance
     */
    private fun analyzeHeadToHeadMatchups(): List<HeadToHeadResult> {
        val allAgents = detailedGameResults.flatMap { listOf(it.agent1Type, it.agent2Type) }.distinct()
        val headToHeadResults = mutableListOf<HeadToHeadResult>()
        
        // Generate all unique agent pairs
        for (i in allAgents.indices) {
            for (j in i + 1 until allAgents.size) {
                val agent1 = allAgents[i]
                val agent2 = allAgents[j]
                
                val matchupGames = detailedGameResults.filter { 
                    (it.agent1Type == agent1 && it.agent2Type == agent2) ||
                    (it.agent1Type == agent2 && it.agent2Type == agent1)
                }
                
                if (matchupGames.isNotEmpty()) {
                    var agent1Wins = 0
                    var agent2Wins = 0
                    var draws = 0
                    
                    for (game in matchupGames) {
                        when {
                            game.winner == Player.Neutral -> draws++
                            (game.winner == Player.Player1 && game.agent1Type == agent1) ||
                            (game.winner == Player.Player2 && game.agent2Type == agent1) -> agent1Wins++
                            else -> agent2Wins++
                        }
                    }
                    
                    val agent1WinRate = if (matchupGames.size > 0) {
                        (agent1Wins.toDouble() / matchupGames.size) * 100.0
                    } else 0.0
                    
                    headToHeadResults.add(
                        HeadToHeadResult(
                            agent1Type = agent1,
                            agent2Type = agent2,
                            agent1Wins = agent1Wins,
                            agent2Wins = agent2Wins,
                            draws = draws,
                            totalGames = matchupGames.size,
                            agent1WinRate = agent1WinRate
                        )
                    )
                }
            }
        }
        
        return headToHeadResults
    }
    
    /**
     * Calculate overall competition readiness scores for each agent
     */
    private fun calculateCompetitionReadinessScores(
        timePerformances: List<AgentTimePerformance>,
        winRatePerformances: List<AgentParameterPerformance>
    ): Map<String, String> {
        val allAgents = detailedGameResults.flatMap { listOf(it.agent1Type, it.agent2Type) }.distinct()
        val readinessScores = mutableMapOf<String, String>()
        
        for (agentName in allAgents) {
            // Get overall time performance
            val overallTimePerf = timePerformances.find { 
                it.agentType == agentName && it.parameterCategory == "Overall" 
            }
            
            // Get overall win rate
            val overallWinRate = winRatePerformances.filter { it.agentName == agentName }
                .map { it.winRate }.average()
            
            // Calculate readiness score
            val timeScore = overallTimePerf?.let { timePerf ->
                when {
                    timePerf.timeComplianceRate >= 99.0 && timePerf.avgDecisionTimeMs <= 50.0 -> 5
                    timePerf.timeComplianceRate >= 95.0 && timePerf.avgDecisionTimeMs <= 75.0 -> 4
                    timePerf.timeComplianceRate >= 90.0 && timePerf.avgDecisionTimeMs <= 90.0 -> 3
                    timePerf.timeComplianceRate >= 80.0 -> 2
                    else -> 1
                }
            } ?: 3
            
            val winRateScore = when {
                overallWinRate >= 70.0 -> 5
                overallWinRate >= 60.0 -> 4
                overallWinRate >= 50.0 -> 3
                overallWinRate >= 40.0 -> 2
                else -> 1
            }
            
            val combinedScore = (timeScore + winRateScore) / 2.0
            
            val readinessRating = when {
                combinedScore >= 4.5 -> "🌟 TOURNAMENT READY"
                combinedScore >= 3.5 -> "🎯 COMPETITION READY"
                combinedScore >= 2.5 -> "⚠️ NEEDS IMPROVEMENT"
                else -> "❌ MAJOR WORK REQUIRED"
            }
            
            readinessScores[agentName] = readinessRating
        }
        
        return readinessScores
    }
    
    /**
     * Generate comprehensive parameter analysis report
     */
    fun generateParameterAnalysisReport(result: ParameterAnalysisResult): String {
        val report = StringBuilder()
        val timestamp = result.analysisTimestamp
        
        report.appendLine("🔍 DETAILED PARAMETER-PERFORMANCE ANALYSIS REPORT")
        report.appendLine("=" .repeat(80))
        report.appendLine("Generated: $timestamp")
        report.appendLine("Total games analyzed: ${result.totalGamesAnalyzed}")
        report.appendLine("Parameter variance mode: $parameterVarianceMode")
        report.appendLine()
        
        // Group performances by agent
        val performancesByAgent = result.agentPerformances.groupBy { it.agentName }
        
        for ((agentName, agentPerformances) in performancesByAgent) {
            report.appendLine("🤖 AGENT: $agentName")
            report.appendLine("-".repeat(60))
            
            // Group by category
            val performancesByCategory = agentPerformances.groupBy { it.parameterCategory }
            
            for ((category, categoryPerformances) in performancesByCategory) {
                report.appendLine("📊 $category Performance:")
                
                for (performance in categoryPerformances.sortedByDescending { it.winRate }) {
                    report.appendLine("  ${performance.parameterRange}:")
                    report.appendLine("    Games: ${performance.gamesPlayed}")
                    report.appendLine("    Win Rate: ${"%.1f".format(performance.winRate)}% (${performance.wins}W-${performance.losses}L-${performance.draws}D)")
                    report.appendLine("    Avg Duration: ${"%.1f".format(performance.avgGameDuration)}s")
                    report.appendLine("    Rating: ${performance.getPerformanceRating()}")
                    report.appendLine()
                }
            }
            
            // Find agent's best and worst parameter conditions
            val bestPerformance = agentPerformances.filter { it.gamesPlayed >= 3 }.maxByOrNull { it.winRate }
            val worstPerformance = agentPerformances.filter { it.gamesPlayed >= 3 }.minByOrNull { it.winRate }
            
            if (bestPerformance != null && worstPerformance != null) {
                report.appendLine("🏆 Best Performance: ${bestPerformance.parameterCategory} - ${bestPerformance.parameterRange}")
                report.appendLine("   Win Rate: ${"%.1f".format(bestPerformance.winRate)}% (${bestPerformance.gamesPlayed} games)")
                report.appendLine()
                report.appendLine("⚠️ Needs Improvement: ${worstPerformance.parameterCategory} - ${worstPerformance.parameterRange}")
                report.appendLine("   Win Rate: ${"%.1f".format(worstPerformance.winRate)}% (${worstPerformance.gamesPlayed} games)")
                report.appendLine()
            }
            
            report.appendLine("=" .repeat(60))
            report.appendLine()
        }
        
        // Overall parameter insights
        report.appendLine("🔬 PARAMETER INSIGHTS")
        report.appendLine("-".repeat(40))
        
        val paramInsights = generateParameterInsights(result)
        for (insight in paramInsights) {
            report.appendLine("• $insight")
        }
        
        return report.toString()
    }
    
    private fun generateParameterInsights(result: ParameterAnalysisResult): List<String> {
        val insights = mutableListOf<String>()
        val performancesByCategory = result.agentPerformances.groupBy { it.parameterCategory }
        
        for ((category, performances) in performancesByCategory) {
            // Find the parameter range where agents generally perform best/worst
            val rangePerformances = performances.groupBy { it.parameterRange }
                .mapValues { (_, perfs) -> perfs.map { it.winRate }.average() }
            
            val bestRange = rangePerformances.maxByOrNull { it.value }
            val worstRange = rangePerformances.minByOrNull { it.value }
            
            if (bestRange != null && worstRange != null && bestRange.key != worstRange.key) {
                insights.add("$category: Agents generally perform best in '${bestRange.key}' range (${"%.1f".format(bestRange.value)}% avg win rate)")
                insights.add("$category: Most challenging range appears to be '${worstRange.key}' (${"%.1f".format(worstRange.value)}% avg win rate)")
            }
        }
        
        return insights
    }
    
    /**
     * Save parameter analysis report to file
     */
    fun saveParameterAnalysisReport(result: ParameterAnalysisResult): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val resultsDir = "results/parameter_analysis/"
        val filename = "parameter_analysis_$timestamp.txt"
        val fullPath = resultsDir + filename
        val reportContent = generateParameterAnalysisReport(result)
        
        try {
            val dir = File(resultsDir)
            if (!dir.exists()) dir.mkdirs() // Ensure the directory exists
            
            File(fullPath).writeText(reportContent)
            println("📄 Parameter analysis report saved to: $fullPath")
            return fullPath
        } catch (e: Exception) {
            println("❌ Failed to save parameter analysis report: ${e.message}")
            return ""
        }
    }
    
    /**
     * Save CSV data for detailed analysis
     */
    fun saveParameterAnalysisCSV(result: ParameterAnalysisResult): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val resultsDir = "results/parameter_analysis/"
        val filename = "parameter_data_$timestamp.csv"
        val fullPath = resultsDir + filename
        
        try {
            val dir = File(resultsDir)
            if (!dir.exists()) dir.mkdirs()
            
            val csvContent = StringBuilder()
            csvContent.appendLine("Agent,Category,Range,Games,Wins,Losses,Draws,WinRate,AvgDuration")
            
            for (performance in result.agentPerformances) {
                csvContent.appendLine("${performance.agentName},${performance.parameterCategory},${performance.parameterRange},${performance.gamesPlayed},${performance.wins},${performance.losses},${performance.draws},${"%.2f".format(performance.winRate)},${"%.2f".format(performance.avgGameDuration)}")
            }
            
            File(fullPath).writeText(csvContent.toString())
            println("📊 Parameter analysis CSV saved to: $fullPath")
            return fullPath
        } catch (e: Exception) {
            println("❌ Failed to save parameter analysis CSV: ${e.message}")
            return ""
        }
    }
    
    private fun analyzeAgentByPlanetCount(agentName: String): List<AgentParameterPerformance> {
        val ranges = listOf(
            "Small (10-15)" to { planets: Int -> planets in 10..15 },
            "Medium (16-22)" to { planets: Int -> planets in 16..22 },
            "Large (23-30)" to { planets: Int -> planets in 23..30 }
        )
        
        return ranges.map { (rangeName, predicate) ->
            analyzeAgentInParameterRange(
                agentName = agentName,
                category = "Planet Count",
                rangeName = rangeName,
                gameFilter = { predicate(it.gameParams.numPlanets) }
            )
        }.filter { it.gamesPlayed > 0 }
    }
    
    private fun analyzeAgentByTransporterSpeed(agentName: String): List<AgentParameterPerformance> {
        val ranges = listOf(
            "Slow (2.0-3.0)" to { speed: Double -> speed in 2.0..3.0 },
            "Medium (3.1-4.0)" to { speed: Double -> speed in 3.1..4.0 },
            "Fast (4.1-5.0)" to { speed: Double -> speed in 4.1..5.0 }
        )
        
        return ranges.map { (rangeName, predicate) ->
            analyzeAgentInParameterRange(
                agentName = agentName,
                category = "Transporter Speed",
                rangeName = rangeName,
                gameFilter = { predicate(it.gameParams.transporterSpeed) }
            )
        }.filter { it.gamesPlayed > 0 }
    }
    
    private fun analyzeAgentByGrowthRate(agentName: String): List<AgentParameterPerformance> {
        val ranges = listOf(
            "Low (0.02-0.08)" to { growth: Double -> growth in 0.02..0.08 },
            "Medium (0.09-0.15)" to { growth: Double -> growth in 0.09..0.15 },
            "High (0.16-0.20)" to { growth: Double -> growth in 0.16..0.20 }
        )
        
        return ranges.map { (rangeName, predicate) ->
            analyzeAgentInParameterRange(
                agentName = agentName,
                category = "Growth Rate",
                rangeName = rangeName,
                gameFilter = { predicate(it.gameParams.maxGrowthRate) }
            )
        }.filter { it.gamesPlayed > 0 }
    }
    
    private fun analyzeAgentByNeutralRatio(agentName: String): List<AgentParameterPerformance> {
        val ranges = listOf(
            "Low Neutral (0.20-0.28)" to { ratio: Double -> ratio in 0.20..0.28 },
            "Medium Neutral (0.29-0.32)" to { ratio: Double -> ratio in 0.29..0.32 },
            "High Neutral (0.33-0.40)" to { ratio: Double -> ratio in 0.33..0.40 }
        )
        
        return ranges.map { (rangeName, predicate) ->
            analyzeAgentInParameterRange(
                agentName = agentName,
                category = "Neutral Ratio",
                rangeName = rangeName,
                gameFilter = { predicate(it.gameParams.initialNeutralRatio) }
            )
        }.filter { it.gamesPlayed > 0 }
    }
    
    private fun analyzeAgentByGameDuration(agentName: String): List<AgentParameterPerformance> {
        val ranges = listOf(
            "Short (400-800)" to { ticks: Int -> ticks in 400..800 },
            "Medium (801-1200)" to { ticks: Int -> ticks in 801..1200 },
            "Long (1201-1500)" to { ticks: Int -> ticks in 1201..1500 }
        )
        
        return ranges.map { (rangeName, predicate) ->
            analyzeAgentInParameterRange(
                agentName = agentName,
                category = "Game Duration",
                rangeName = rangeName,
                gameFilter = { predicate(it.gameParams.maxTicks) }
            )
        }.filter { it.gamesPlayed > 0 }
    }
    
    private fun analyzeAgentInParameterRange(
        agentName: String,
        category: String,
        rangeName: String,
        gameFilter: (GameResult) -> Boolean
    ): AgentParameterPerformance {
        val relevantGames = detailedGameResults.filter { gameResult ->
            gameFilter(gameResult) && (gameResult.agent1Type == agentName || gameResult.agent2Type == agentName)
        }
        
        var wins = 0
        var losses = 0
        var draws = 0
        var totalDuration = 0.0
        
        for (game in relevantGames) {
            totalDuration += game.gameDurationSeconds
            
            when {
                game.winner == Player.Neutral -> draws++
                (game.winner == Player.Player1 && game.agent1Type == agentName) ||
                (game.winner == Player.Player2 && game.agent2Type == agentName) -> wins++
                else -> losses++
            }
        }
        
        val totalGames = relevantGames.size
        val winRate = if (totalGames > 0) (wins.toDouble() / totalGames) * 100.0 else 0.0
        val avgDuration = if (totalGames > 0) totalDuration / totalGames else 0.0
        
        return AgentParameterPerformance(
            agentName = agentName,
            parameterCategory = category,
            parameterRange = rangeName,
            gamesPlayed = totalGames,
            wins = wins,
            losses = losses,
            draws = draws,
            winRate = winRate,
            avgGameDuration = avgDuration
        )
    }
    
    // Helper function to create agent factories from a list of agent classes
    companion object {
        fun createLeagueWithAgentClasses(
            agentClasses: List<KClass<out PlanetWarsAgent>>, 
            gamesPerPair: Int = 100,
            baseGameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 400),
            parameterVarianceMode: ParameterVarianceMode = ParameterVarianceMode.COMPETITION_RANGES,
            parallelism: Int = Runtime.getRuntime().availableProcessors(),
            verboseGameLogs: Boolean = false,
            showParameterVariance: Boolean = true,
            randomSeed: Long? = null
        ): RoundRobinLeagueImproved {
            // Create factories for each agent class
            val factories = agentClasses.map { agentClass ->
                { 
                    // Try to instantiate using primary constructor with no args
                    agentClass.primaryConstructor?.call() ?: 
                    // Fallback to instance creation if that fails
                    agentClass.java.getDeclaredConstructor().newInstance()
                }
            }
            
            return RoundRobinLeagueImproved(
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
        
        /**
         * Create a competition-ready league that tests agents against GECCO 2025 official parameter ranges
         */
        fun createCompetitionLeague(
            agentFactories: List<() -> PlanetWarsAgent>,
            gamesPerPair: Int = 100,
            parallelism: Int = Runtime.getRuntime().availableProcessors(),
            verboseGameLogs: Boolean = false,
            randomSeed: Long? = null
        ): RoundRobinLeagueImproved {
            return RoundRobinLeagueImproved(
                agentFactories = agentFactories,
                gamesPerPair = gamesPerPair,
                baseGameParams = GameParams(), // Not used in competition mode
                parameterVarianceMode = ParameterVarianceMode.COMPETITION_RANGES,
                parallelism = parallelism,
                verboseGameLogs = verboseGameLogs,
                showParameterVariance = true,
                randomSeed = randomSeed,
                enableDetailedParameterAnalysis = true // Enable detailed analysis for competition
            )
        }
    }
}

/**
 * Main function to run the competition mode only - simplified for GECCO 2025
 */
fun main(args: Array<String>) = runBlocking {
    println("🎯 GECCO 2025 Competition League - Parameter Analysis")
    println("=".repeat(60))
    
    // Ensure results directory structure exists
    ensureResultsDirectoryStructure()
    
    // Configuration - fixed for competition mode only
    val gamesPerPair = 50
    val parallelism = 10
    
    // Create the list of agent factories
    val agentFactories = listOf<() -> PlanetWarsAgent>(
//        { StrategicAgentDynamic()},
//        { StrategicAgent() },
//        { BocsimackoAgent() },
//        { SimpleEvoAgent() },
        { TeamTitansAgent()},
        { StrategicAgentDynamic()},

    )
    
    println("Games per pair: $gamesPerPair")
    println("Agents: ${agentFactories.map { it().getAgentType() }}")
    
    // Run competition mode only
    runCompetitionMode(agentFactories, gamesPerPair, parallelism)
}

/**
 * Competition mode: Official GECCO 2025 parameter ranges
 */
suspend fun runCompetitionMode(
    agentFactories: List<() -> PlanetWarsAgent>, 
    gamesPerPair: Int, 
    parallelism: Int
) {
    println("\n🏆 COMPETITION MODE - GECCO 2025 Official Parameter Ranges")
    
    val league = RoundRobinLeagueImproved.createCompetitionLeague(
        agentFactories = agentFactories,
        gamesPerPair = gamesPerPair,
        parallelism = parallelism,
        verboseGameLogs = false,
        randomSeed = System.currentTimeMillis() // Random for variety
    )
    
    val timeTaken = measureTimeMillis {
        val results = league.runRoundRobinParallel()
        
        // Enhanced competition analysis
        analyzeCompetitionReadiness(results)
        
        // Generate detailed parameter analysis
        println("\n🔍 Generating detailed parameter-performance analysis...")
        val parameterAnalysis = league.analyzeParameterPerformance()
        
        if (parameterAnalysis.totalGamesAnalyzed > 0) {
            // Save detailed analysis to file
            val reportFilename = league.saveParameterAnalysisReport(parameterAnalysis)
            val csvFilename = league.saveParameterAnalysisCSV(parameterAnalysis)
            
            // Print summary to console
            val performancesByAgent = parameterAnalysis.agentPerformances.groupBy { it.agentName }
            
            println("\n📊 PARAMETER-PERFORMANCE SUMMARY")
            println("=" .repeat(60))
            
            for ((agentName, agentPerformances) in performancesByAgent) {
                println("\n🤖 $agentName:")
                
                // Find best and worst conditions
                val validPerformances = agentPerformances.filter { it.gamesPlayed >= 3 }
                val bestCondition = validPerformances.maxByOrNull { it.winRate }
                val worstCondition = validPerformances.minByOrNull { it.winRate }
                
                if (bestCondition != null && worstCondition != null) {
                    println("  🏆 Best: ${bestCondition.parameterCategory} - ${bestCondition.parameterRange}")
                    println("  ⚠️  Worst: ${worstCondition.parameterCategory} - ${worstCondition.parameterRange}")
                    
                    // Show parameter-specific insights
                    val planetPerf = agentPerformances.filter { it.parameterCategory == "Planet Count" }.sortedByDescending { it.winRate }
                    val speedPerf = agentPerformances.filter { it.parameterCategory == "Transporter Speed" }.sortedByDescending { it.winRate }
                    
                    if (planetPerf.isNotEmpty()) {
                        println("  🌍 Best planet size: ${planetPerf.first().parameterRange}")
                    }
                    if (speedPerf.isNotEmpty()) {
                        println("  🚀 Best speed range: ${speedPerf.first().parameterRange}")
                    }
                }
            }
            
            println("\n📄 Detailed analysis saved to:")
            println("  📋 Report: $reportFilename")
            println("  📊 CSV Data: $csvFilename")
        } else {
            println("⚠️ No parameter analysis data available")
        }
        
        saveAndDisplayResults(results, "GECCO 2025 Competition Preparation")
    }
    
    println("\n🎯 Competition preparation complete in ${timeTaken / 1000.0} seconds")
    println("📊 Agents tested against full GECCO 2025 parameter ranges!")
}

/**
 * Analyze agent competition readiness based on performance patterns
 */
private fun analyzeCompetitionReadiness(results: Map<String, LeagueEntry>) {
    println("\n=== COMPETITION READINESS ANALYSIS ===")
    
    val sortedResults = results.toList().sortedByDescending { it.second.points }
    
    for ((index, entry) in sortedResults.withIndex()) {
        val agent = entry.second
        val winRate = agent.points / agent.nGames * 100
        
        val readinessLevel = when {
            winRate >= 70.0 -> "🥇 EXCELLENT - Ready for competition"
            winRate >= 60.0 -> "🥈 GOOD - Competitive performance"
            winRate >= 50.0 -> "🥉 AVERAGE - Needs improvement"
            else -> "❌ POOR - Significant work needed"
        }
        
        val consistencyNote = if (agent.nGames > 50) {
            when {
                winRate >= 65.0 -> " (Highly consistent)"
                winRate >= 55.0 -> " (Reasonably consistent)"
                else -> " (Inconsistent performance)"
            }
        } else ""
        
        println("${index + 1}. ${agent.agentName}: ${"%.1f".format(winRate)}% win rate - $readinessLevel$consistencyNote")
    }
    
    println("\n📊 Performance tested across OFFICIAL GECCO 2025 parameter ranges:")
    println("  ✅ Planet count variance: 10-30 (3x scaling)")
    println("  ✅ Neutral ratio: ~30% (25%-35% range)")
    println("  ✅ Growth rate variance: 0.05-0.2 (4x range)")
    println("  ✅ Speed variance: 2.0-5.0 (2.5x range)")
    println("  ✅ Game duration variance: 400-1500 ticks")
    println("\n🎯 Competition Readiness Score: ${calculateOverallReadiness(results)}")
}

/**
 * Calculate overall competition readiness score
 */
private fun calculateOverallReadiness(results: Map<String, LeagueEntry>): String {
    val avgWinRate = results.values.map { it.points / it.nGames * 100 }.average()
    val topPerformerRate = results.values.maxOfOrNull { it.points / it.nGames * 100 } ?: 0.0
    
    return when {
        topPerformerRate >= 75.0 && avgWinRate >= 55.0 -> "🌟 EXCELLENT - Tournament ready"
        topPerformerRate >= 65.0 && avgWinRate >= 50.0 -> "🎯 GOOD - Competition ready with refinement"
        topPerformerRate >= 55.0 -> "⚠️ AVERAGE - Needs significant improvement"
        else -> "❌ POOR - Major work required"
    }
}

/**
 * Save results and display summary
 */
private fun saveAndDisplayResults(results: Map<String, LeagueEntry>, testName: String) {
    // Create timestamped directory for this test run
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val testNameFormatted = testName.replace(" ", "_").replace("/", "-")
    val resultsDir = "results/tournaments/$testNameFormatted/$timestamp/"
    
    // Use the League utils to print the results with organized directory structure
    println("\n=== $testName Results ===")
    val writer = LeagueWriter(outputDir = resultsDir, filename = "league_results.md")
    val leagueResult = LeagueResult(results.values.toList())
    val markdownContent = writer.generateMarkdownTable(leagueResult)
    writer.saveMarkdownToFile(markdownContent)
    
    // Also save a summary text file
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

    // Print sorted results directly to console
    println("\nFinal Rankings:")
    val sortedResults = results.toList().sortedByDescending { it.second.points }.toMap()
    for ((index, entry) in sortedResults.values.withIndex()) {
        val winRate = entry.points / entry.nGames * 100
        println("${index + 1}. ${entry.agentName} : ${"%.1f".format(winRate)}% win rate (${entry.points.toInt()}/${entry.nGames} games)")
    }
    
    println("\n📁 All results saved to: $resultsDir")
}

/**
 * Ensure results directory structure exists
 */
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

/**
 * Create a comprehensive results package with all data
 */
private fun createResultsPackage(
    results: Map<String, LeagueEntry>, 
    testName: String, 
    parameterAnalysis: ParameterAnalysisResult? = null,
    league: RoundRobinLeagueImproved? = null
): String {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val testNameFormatted = testName.replace(" ", "_").replace("/", "-")
    val packageDir = "results/complete_analysis/$testNameFormatted/$timestamp/"
    
    try {
        val dir = File(packageDir)
        if (!dir.exists()) dir.mkdirs()
        
        // Save league results
        val writer = LeagueWriter(outputDir = packageDir, filename = "league_results.md")
        val leagueResult = LeagueResult(results.values.toList())
        writer.saveMarkdownToFile(writer.generateMarkdownTable(leagueResult))
        
        // Save parameter analysis if available
        parameterAnalysis?.let { analysis ->
            league?.let { leagueInstance ->
                val reportContent = leagueInstance.generateParameterAnalysisReport(analysis)
                File(packageDir + "parameter_analysis.txt").writeText(reportContent)
            }
            
            // Save CSV
            val csvContent = StringBuilder()
            csvContent.appendLine("Agent,Category,Range,Games,Wins,Losses,Draws,WinRate,AvgDuration")
            for (performance in analysis.agentPerformances) {
                csvContent.appendLine("${performance.agentName},${performance.parameterCategory},${performance.parameterRange},${performance.gamesPlayed},${performance.wins},${performance.losses},${performance.draws},${"%.2f".format(performance.winRate)},${"%.2f".format(performance.avgGameDuration)}")
            }
            File(packageDir + "parameter_data.csv").writeText(csvContent.toString())
        }
        
        // Create README
        val readmeContent = """
# $testName - Analysis Results

Generated: ${LocalDateTime.now()}

## Files in this package:
- `league_results.md` - Tournament results in markdown format
- `tournament_summary.txt` - Plain text summary
${if (parameterAnalysis != null) {
    """- `parameter_analysis.txt` - Detailed parameter-performance analysis
- `parameter_data.csv` - Raw data for further analysis"""
} else ""}

## Summary:
${results.values.sortedByDescending { it.points }.take(3).mapIndexed { index, entry ->
    val winRate = entry.points / entry.nGames * 100
    "${index + 1}. ${entry.agentName}: ${"%.1f".format(winRate)}% win rate"
}.joinToString("\n")}

Total games analyzed: ${results.values.sumOf { it.nGames }}
${parameterAnalysis?.let { "Parameter analysis games: ${it.totalGamesAnalyzed}" } ?: ""}
        """.trimIndent()
        
        File(packageDir + "README.md").writeText(readmeContent)
        
        println("📦 Complete analysis package created: $packageDir")
        return packageDir
    } catch (e: Exception) {
        println("❌ Failed to create results package: ${e.message}")
        return ""
    }
}
// Helper function to print all game parameters

