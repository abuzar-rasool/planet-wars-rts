package games.planetwars.runners

import games.planetwars.agents.DoNothingAgent
import games.planetwars.agents.evo.EvolutionaryStrategy
import games.planetwars.agents.evo.ParameterHistory
import games.planetwars.agents.evo.ParameterOptimizer
import games.planetwars.agents.random.AdaptiveHeuristicAgent
import games.planetwars.agents.random.BetterRandomAgent
import games.planetwars.agents.random.CarefulRandomAgent
import games.planetwars.agents.random.HeuristicParameters
import games.planetwars.agents.random.TunableHeuristicAgent
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import games.planetwars.agents.random.HeavyRandomAgent
import games.planetwars.agents.evo.SimpleEvoAgent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import games.planetwars.agents.random.HybridStrategicEvoAgentCheeni
import games.planetwars.agents.random.StrategicHeuristicAgentAmmar
import games.planetwars.agents.random.SelfTuningHeuristicAgent

/**
 * Continuous parameter optimization system that runs until manually stopped.
 * Progress can be tracked in the results directory, and the system will save
 * the best parameters regularly.
 */
class ContinuousParameterOptimizer(
    private val baseDir: String = "results/continuous_optimization",
    private val initialGames: Int = 20,
    private val maxGames: Int = 100,
    private val initialPopulationSize: Int = 10,
    private val maxPopulationSize: Int = 30,
    private val initialGenerations: Int = 5,
    private val maxGenerations: Int = 20,
    private val cyclesPerPhase: Int = 3,
    private val testFrequency: Int = 5,
    private val useSelfTuningForComparison: Boolean = true
) {
    private val opponents = listOf(
        BetterRandomAgent(),
        CarefulRandomAgent(),
        AdaptiveHeuristicAgent(),
        HybridStrategicEvoAgentCheeni(),
        StrategicHeuristicAgentAmmar(),
        SelfTuningHeuristicAgent(),
        SimpleEvoAgent(
            useShiftBuffer = true,
            nEvals = 30,
            sequenceLength = 400,
            opponentModel = DoNothingAgent(),
            probMutation = 0.8,
        )
    )
    
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val startTime = LocalDateTime.now().format(formatter)
    private val optimizationDir = "$baseDir/${startTime}"
    private val summaryFile = "$optimizationDir/optimization_summary.txt"
    private val statusFile = "$optimizationDir/status.txt"
    
    private var currentPhase = OptimizationPhase.EXPLORATION
    private var currentCycle = 0
    private var bestWinRate = 0.0
    private var totalRuns = 0
    private var keepRunning = true
    private var lastTestResults = ""
    
    init {
        // Create directories
        File(optimizationDir).mkdirs()
        File(summaryFile).writeText("Continuous Optimization started at $startTime\n\n")
        updateStatus("Initializing...")
    }
    
    fun run() {
        // Start a watchdog thread that updates status every 10 seconds
        val statusThread = thread {
            while (keepRunning) {
                try {
                    updateStatus(
                        "Optimization running\n" +
                        "Phase: $currentPhase\n" +
                        "Cycle: ${currentCycle + 1}\n" +
                        "Total runs: $totalRuns\n" +
                        "Best win rate: ${String.format("%.2f", bestWinRate * 100)}%\n\n" +
                        "Last test results:\n$lastTestResults"
                    )
                    Thread.sleep(10000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        
        try {
            mainOptimizationLoop()
        } finally {
            keepRunning = false
            statusThread.join(1000)
            updateStatus("Optimization stopped")
        }
    }
    
    private fun mainOptimizationLoop() {
        // Main loop - will run until manually stopped
        while (keepRunning) {
            // Switch between phases and optimize against different opponents
            val opponent = opponents[currentCycle % opponents.size]
            
            // Adjust optimization parameters based on current phase
            val (games, populationSize, generations, mutationRate, mutationStrength) = getPhaseParameters()
            
            log("Starting optimization cycle ${currentCycle + 1}")
            log("Phase: $currentPhase")
            log("Opponent: ${opponent.getAgentType()}")
            log("Evaluation games: $games")
            log("Population size: $populationSize")
            log("Generations: $generations")
            log("Mutation rate: $mutationRate")
            log("Mutation strength: $mutationStrength")
            
            // Create unique experiment name for this cycle
            val experimentName = "cycle_${currentCycle + 1}_vs_${opponent.getAgentType().replace(" ", "_").lowercase()}"
            
            // Get best parameters so far to use as starting point
            val baseParams = getBestParametersSoFar() ?: HeuristicParameters()
            
            // Create history with experiment-specific files
            val history = ParameterHistory(
                historyFile = "$optimizationDir/${experimentName}_history.csv",
                topResultsFile = "$optimizationDir/${experimentName}_top_results.csv"
            )
            
            // Create optimizer
            val optimizer = ParameterOptimizer(
                baseParams = baseParams,
                opponent = opponent,
                evaluationGames = games,
                gameParams = GameParams(numPlanets = 20, maxTicks = 300),
                history = history
            )
            
            // Configure evolutionary optimization strategy
            val strategy = EvolutionaryStrategy(
                populationSize = populationSize,
                generations = generations,
                eliteCount = max(2, populationSize / 5),
                mutationRate = mutationRate,
                mutationStrength = mutationStrength
            )
            
            // Run the optimization
            val optimizedParams = optimizer.optimize(strategy)
            
            // CHANGED: Instead of using currentWinRate from history, always test against all opponents
            // and use the overall win rate to determine if we have a new best set of parameters
            val testResults = testAgainstAllOpponents(optimizedParams)
            lastTestResults = testResults
            log(testResults)
            
            // Extract the overall win rate from the test results
            val overallWinRateString = testResults.lines()
                .lastOrNull { it.startsWith("Overall Results:") }
                ?.let {
                    val regex = "Overall Results: (\\d+)/(\\d+) \\((\\d+)%\\)".toRegex()
                    val matchResult = regex.find(it)
                    matchResult?.groupValues?.get(3)?.toDoubleOrNull()?.div(100.0)
                } ?: 0.0
                
            log("Current overall win rate: ${String.format("%.2f", overallWinRateString * 100)}%")
            log("Previous best win rate: ${String.format("%.2f", bestWinRate * 100)}%")
            
            // Update best parameters if the overall win rate is better
            if (overallWinRateString > bestWinRate) {
                bestWinRate = overallWinRateString
                saveGlobalBestParameters(optimizedParams, bestWinRate)
                log("New best parameters saved with overall win rate: ${String.format("%.2f", bestWinRate * 100)}%")
            }
            
            // Update cycle counter and phase
            totalRuns++
            currentCycle = (currentCycle + 1) % cyclesPerPhase
            if (currentCycle == 0) {
                advancePhase()
            }
        }
    }
    
    private fun getPhaseParameters(): OptimizationParameters {
        // Return different parameters based on the current phase
        return when (currentPhase) {
            OptimizationPhase.EXPLORATION -> {
                // Broad search with more mutation
                val games = initialGames
                val populationSize = initialPopulationSize
                val generations = initialGenerations
                val mutationRate = 0.3
                val mutationStrength = 0.3
                
                OptimizationParameters(games, populationSize, generations, mutationRate, mutationStrength)
            }
            OptimizationPhase.EXPLOITATION -> {
                // Narrower search with less mutation
                val games = initialGames * 2
                val populationSize = initialPopulationSize + 5
                val generations = initialGenerations + 3
                val mutationRate = 0.2
                val mutationStrength = 0.15
                
                OptimizationParameters(games, populationSize, generations, mutationRate, mutationStrength)
            }
            OptimizationPhase.REFINEMENT -> {
                // Fine-tuning with minimal mutation
                val games = min(initialGames * 3, maxGames)
                val populationSize = min(initialPopulationSize + 10, maxPopulationSize)
                val generations = min(initialGenerations + 5, maxGenerations)
                val mutationRate = 0.1
                val mutationStrength = 0.05
                
                OptimizationParameters(games, populationSize, generations, mutationRate, mutationStrength)
            }
            OptimizationPhase.VERIFICATION -> {
                // Final testing with larger game counts
                val games = maxGames
                val populationSize = initialPopulationSize
                val generations = 3
                val mutationRate = 0.05
                val mutationStrength = 0.02
                
                OptimizationParameters(games, populationSize, generations, mutationRate, mutationStrength)
            }
        }
    }
    
    private fun advancePhase() {
        currentPhase = when (currentPhase) {
            OptimizationPhase.EXPLORATION -> OptimizationPhase.EXPLOITATION
            OptimizationPhase.EXPLOITATION -> OptimizationPhase.REFINEMENT
            OptimizationPhase.REFINEMENT -> OptimizationPhase.VERIFICATION
            OptimizationPhase.VERIFICATION -> OptimizationPhase.EXPLORATION
        }
        log("Advanced to phase: $currentPhase")
    }
    
    private fun getBestParametersSoFar(): HeuristicParameters? {
        val file = File("$optimizationDir/best_parameters.txt")
        if (!file.exists()) return null
        
        return try {
            // Parse the parameters from the saved file
            val lines = file.readLines()
            val paramMap = mutableMapOf<String, String>()
            
            for (line in lines) {
                if (line.contains("=")) {
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        paramMap[parts[0].trim()] = parts[1].trim()
                    }
                }
            }
            
            HeuristicParameters(
                reserveRatio = paramMap["reserveRatio"]?.toDoubleOrNull() ?: 0.2,
                availableShipsRatio = paramMap["availableShipsRatio"]?.toDoubleOrNull() ?: 0.8,
                defenseReinforceRatio = paramMap["defenseReinforceRatio"]?.toDoubleOrNull() ?: 0.75,
                defensePriorityBase = paramMap["defensePriorityBase"]?.toDoubleOrNull() ?: 1000.0,
                neutralHorizon = paramMap["neutralHorizon"]?.toIntOrNull() ?: 50,
                enemyGrowthWeight = paramMap["enemyGrowthWeight"]?.toDoubleOrNull() ?: 10.0,
                attackPriorityForLargestEnemy = paramMap["attackPriorityForLargestEnemy"]?.toDoubleOrNull() ?: 50.0,
                vulnerabilityPenalty = paramMap["vulnerabilityPenalty"]?.toDoubleOrNull() ?: 20.0,
                sourceMinShips = paramMap["sourceMinShips"]?.toIntOrNull() ?: 2,
                maxAttackDistance = paramMap["maxAttackDistance"]?.toIntOrNull() ?: 50,
                internalRedistributionDistance = paramMap["internalRedistributionDistance"]?.toIntOrNull() ?: 20,
                internalRedistributionShips = paramMap["internalRedistributionShips"]?.toIntOrNull() ?: 20,
                defensiveOverkillMargin = paramMap["defensiveOverkillMargin"]?.toIntOrNull() ?: 5,
                attackOverkillMargin = paramMap["attackOverkillMargin"]?.toIntOrNull() ?: 5
            )
        } catch (e: Exception) {
            log("Error loading best parameters: ${e.message}")
            null
        }
    }
    
    private fun saveGlobalBestParameters(params: HeuristicParameters, winRate: Double) {
        val file = File("$optimizationDir/best_parameters.txt")
        val timestamp = LocalDateTime.now().format(formatter)
        val content = """
            # Best parameters found at $timestamp
            # Overall win rate against all opponents: ${String.format("%.2f", winRate * 100)}%
            
            reserveRatio=${params.reserveRatio}
            availableShipsRatio=${params.availableShipsRatio}
            defenseReinforceRatio=${params.defenseReinforceRatio}
            defensePriorityBase=${params.defensePriorityBase}
            neutralHorizon=${params.neutralHorizon}
            enemyGrowthWeight=${params.enemyGrowthWeight}
            attackPriorityForLargestEnemy=${params.attackPriorityForLargestEnemy}
            vulnerabilityPenalty=${params.vulnerabilityPenalty}
            sourceMinShips=${params.sourceMinShips}
            maxAttackDistance=${params.maxAttackDistance}
            internalRedistributionDistance=${params.internalRedistributionDistance}
            internalRedistributionShips=${params.internalRedistributionShips}
            defensiveOverkillMargin=${params.defensiveOverkillMargin}
            attackOverkillMargin=${params.attackOverkillMargin}
        """.trimIndent()
        
        file.writeText(content)
        log("Saved new best parameters with overall win rate against all opponents: ${String.format("%.2f", winRate * 100)}%")
    }
    
    private fun testAgainstAllOpponents(params: HeuristicParameters): String {
        val gameParams = GameParams(numPlanets = 20, maxTicks = 300)
        val optimizedAgent = TunableHeuristicAgent(heuristicParams = params)
        val testGames = 50
        val results = StringBuilder("Test Results\n")
        results.append("=============\n")
        
        var totalWins = 0
        var totalGames = 0
        
        // Test against each opponent
        for (opponent in opponents) {
            results.append("vs ${opponent.getAgentType()}:\n")
            
            // Test as Player1
            val runner1 = GameRunner(optimizedAgent, opponent, gameParams)
            val scoreMap1 = runner1.runGames(testGames)
            val wins1 = scoreMap1[Player.Player1] ?: 0
            
            // Test as Player2  
            val runner2 = GameRunner(opponent, optimizedAgent, gameParams)
            val scoreMap2 = runner2.runGames(testGames)
            val wins2 = scoreMap2[Player.Player2] ?: 0
            
            val totalWinsVsOpponent = wins1 + wins2
            val totalGamesVsOpponent = testGames * 2
            
            results.append("  As P1: $wins1/$testGames (${wins1 * 100 / testGames}%)\n")
            results.append("  As P2: $wins2/$testGames (${wins2 * 100 / testGames}%)\n")
            results.append("  Overall: $totalWinsVsOpponent/$totalGamesVsOpponent (${totalWinsVsOpponent * 100 / totalGamesVsOpponent}%)\n")
            
            totalWins += totalWinsVsOpponent
            totalGames += totalGamesVsOpponent
        }
        
        // Test against base agent
        val baseAgent = AdaptiveHeuristicAgent()
        results.append("\nvs Base AdaptiveHeuristicAgent:\n")
        
        val runnerVsBase = GameRunner(optimizedAgent, baseAgent, gameParams)
        val resultsVsBase = runnerVsBase.runGames(testGames)
        val winsVsBase = resultsVsBase[Player.Player1] ?: 0
        
        results.append("  As P1: $winsVsBase/$testGames (${winsVsBase * 100 / testGames}%)\n")
        totalWins += winsVsBase
        totalGames += testGames
        
        // Optionally test against another agent if implemented
        if (useSelfTuningForComparison) {
            try {
                // Use another existing agent instead of SelfTuningHeuristicAgent
                val alternativeAgent = HeavyRandomAgent(delayMillis = 0)
                results.append("\nvs Alternative Agent (HeavyRandomAgent):\n")
                
                val runnerVsAlternative = GameRunner(optimizedAgent, alternativeAgent, gameParams)
                val resultsVsAlternative = runnerVsAlternative.runGames(testGames)
                val winsVsAlternative = resultsVsAlternative[Player.Player1] ?: 0
                
                results.append("  As P1: $winsVsAlternative/$testGames (${winsVsAlternative * 100 / testGames}%)\n")
                totalWins += winsVsAlternative
                totalGames += testGames
            } catch (e: Exception) {
                results.append("\nSkipped alternative agent comparison: ${e.message}\n")
            }
        }
        
        // Overall results
        results.append("\nOverall Results: $totalWins/$totalGames (${totalWins * 100 / totalGames}%)\n")
        
        // Save test results to file
        val testFile = File("$optimizationDir/test_results_${LocalDateTime.now().format(formatter)}.txt")
        testFile.writeText(results.toString())
        
        return results.toString()
    }
    
    private fun log(message: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val logMessage = "[$timestamp] $message\n"
        File(summaryFile).appendText(logMessage)
        println(message)
    }
    
    private fun updateStatus(status: String) {
        File(statusFile).writeText(status)
    }
    
    enum class OptimizationPhase {
        EXPLORATION,    // Wide search with high mutation
        EXPLOITATION,   // Narrower search with medium mutation
        REFINEMENT,     // Fine-tuning with low mutation
        VERIFICATION    // Testing the best parameters with large game counts
    }
    
    data class OptimizationParameters(
        val games: Int,
        val populationSize: Int,
        val generations: Int,
        val mutationRate: Double,
        val mutationStrength: Double
    )
}

/**
 * Main function to run the continuous optimizer
 */
fun main() {
    val optimizer = ContinuousParameterOptimizer()
    println("Starting continuous parameter optimization...")
    println("Results will be stored in ${optimizer.javaClass.getResource("/")?.path ?: "results/continuous_optimization"}")
    println("Press Ctrl+C to stop the optimization process")
    optimizer.run()
} 