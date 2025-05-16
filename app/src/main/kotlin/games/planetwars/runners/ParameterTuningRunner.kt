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
import games.planetwars.agents.random.SelfTuningHeuristicAgent
/**
 * Runner for parameter tuning using stored optimization state
 */
fun main() {
    // Set up the optimization experiment
    val experimentName = "tuned_vs_better_random"
    val opponents = listOf(
        BetterRandomAgent(),
        CarefulRandomAgent(),
        AdaptiveHeuristicAgent(),
        SelfTuningHeuristicAgent(),
        SimpleEvoAgent(
            useShiftBuffer = true,
            nEvals = 30,
            sequenceLength = 400,
            opponentModel = DoNothingAgent(),
            probMutation = 0.8,
        ),
    )
    
    // Choose the opponent we want to optimize against
    val opponent = opponents[0] // BetterRandomAgent
    
    // Base configuration to start from
    val baseParams = HeuristicParameters()
    
    // Create history with experiment-specific files
    val history = ParameterHistory(
        historyFile = "results/parameter_optimization/${experimentName}_history.csv",
        topResultsFile = "results/parameter_optimization/${experimentName}_top_results.csv"
    )
    
    // Create optimizer with settings for quick initial tests
    // (increase generations and population size for better results)
    val optimizer = ParameterOptimizer(
        baseParams = baseParams,
        opponent = opponent,
        evaluationGames = 20, // Number of games to evaluate each parameter set
        gameParams = GameParams(numPlanets = 20, maxTicks = 300),
        history = history
    )
    
    // Configure evolutionary optimization strategy
    val strategy = EvolutionaryStrategy(
        populationSize = 10,
        generations = 5,
        eliteCount = 2,
        mutationRate = 0.3,
        mutationStrength = 0.2
    )
    
    println("Starting parameter optimization against ${opponent.getAgentType()}")
    println("Base parameters: $baseParams")
    
    // Run the optimization
    val optimizedParams = optimizer.optimize(strategy)
    
    println("\nOptimization complete!")
    println("Optimized parameters: $optimizedParams")
    
    // Print top results
    optimizer.printTopResults()
    
    // Test the optimized agent against various opponents
    testOptimizedAgent(optimizedParams, opponents)
}

/**
 * Test the optimized agent against multiple opponents to see how it performs
 */
private fun testOptimizedAgent(optimizedParams: HeuristicParameters, opponents: List<games.planetwars.agents.PlanetWarsAgent>) {
    println("\n--- Testing Optimized Agent Against Multiple Opponents ---")
    
    val gameParams = GameParams(numPlanets = 20, maxTicks = 300)
    val optimizedAgent = TunableHeuristicAgent(optimizedParams)
    val testGames = 100
    
    // Test against each opponent
    for (opponent in opponents) {
        println("\nTesting against: ${opponent.getAgentType()}")
        
        // Test playing as Player1
        val runner1 = GameRunner(optimizedAgent, opponent, gameParams)
        val results1 = runner1.runGames(testGames)
        val winRate1 = results1[Player.Player1]?.toDouble() ?: 0.0 / testGames * 100
        
        // Test playing as Player2
        val runner2 = GameRunner(opponent, optimizedAgent, gameParams)
        val results2 = runner2.runGames(testGames)
        val winRate2 = results2[Player.Player2]?.toDouble() ?: 0.0 / testGames * 100
        
        // Report results
        println("  As Player1: ${results1[Player.Player1]} / $testGames (${winRate1}%)")
        println("  As Player2: ${results2[Player.Player2]} / $testGames (${winRate2}%)")
        println("  Overall: ${results1[Player.Player1]!! + results2[Player.Player2]!!} / ${testGames * 2} " + 
                "(${(results1[Player.Player1]!! + results2[Player.Player2]!!) / (testGames * 2.0) * 100}%)")
    }
    
    // Now test against base (unoptimized) agent
    val baseAgent = AdaptiveHeuristicAgent()
    println("\nTesting against base AdaptiveHeuristicAgent (unoptimized):")
    
    val runnerVsBase = GameRunner(optimizedAgent, baseAgent, gameParams)
    val resultsVsBase = runnerVsBase.runGames(testGames)
    val winRateVsBase = resultsVsBase[Player.Player1]?.toDouble() ?: 0.0 / testGames * 100
    
    println("  Win rate vs base agent: ${resultsVsBase[Player.Player1]} / $testGames (${winRateVsBase}%)")
}

/**
 * Alternative main function to just run a comparison of optimized vs base agent
 */
fun testOptimizedVsBase() {
    // Load the best parameters from a previous optimization run
    val history = ParameterHistory(
        historyFile = "results/parameter_optimization/tuned_vs_better_random_history.csv",
        topResultsFile = "results/parameter_optimization/tuned_vs_better_random_top_results.csv"
    )
    
    val bestParams = history.getBestParameters() ?: HeuristicParameters()
    val optimizedAgent = TunableHeuristicAgent(bestParams)
    val baseAgent = AdaptiveHeuristicAgent()
    
    val gameParams = GameParams(numPlanets = 20, maxTicks = 300)
    val testGames = 100
    
    // Run a head-to-head comparison
    val runner = GameRunner(optimizedAgent, baseAgent, gameParams)
    val results = runner.runGames(testGames)
    
    println("Optimized vs Base Agent:")
    println("  Optimized wins: ${results[Player.Player1]}")
    println("  Base wins: ${results[Player.Player2]}")
    println("  Win rate: ${results[Player.Player1]?.toDouble() ?: 0.0 / testGames * 100}%")
} 