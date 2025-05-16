package games.planetwars.runners

import games.planetwars.agents.DoNothingAgent
import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.evo.SimpleEvoAgent
import games.planetwars.agents.mcts.MCTSAgent
import games.planetwars.agents.random.BetterRandomAgent
import games.planetwars.agents.random.CarefulRandomAgent
import games.planetwars.agents.random.PureRandomAgent
import games.planetwars.core.GameParams
import games.planetwars.core.Player

/**
 * Benchmarks the MCTS agent against various opponents
 */
fun main() {
    // Create MCTS agent with performance-optimized parameters
    val mctsAgent = MCTSAgent(
        numIterations = 100,              // Reduced iterations for better time compliance
        explorationConstant = 2.5,        // Increased exploration
        maxSimDepth = 6,                  // Reduced simulation depth
        adaptiveDepthRate = 0.9,          // More adaptive depth
        opponentModelWeight = 0.8,        // Increased opponent modeling
        useProgressiveWidening = true,
        progressiveWideningBase = 0.8,    // More aggressive widening
        progressiveWideningExponent = 0.3,
        useEarlyGameGrowthStrategy = true,
        smartTargetSelection = true,
        useFirstPlayUrgency = true,
        firstPlayUrgencyValue = 25.0,     // Higher urgency for exploration
        useTranspositionTable = true,
        timeLimitMillis = 10              // Reduced time limit
    )
    
    // Create list of opponent agents to benchmark against
    val opponents = listOf(
        PureRandomAgent(),
        BetterRandomAgent(),
        CarefulRandomAgent(),
        SimpleEvoAgent(
            useShiftBuffer = true,
            nEvals = 30,
            sequenceLength = 400,
            opponentModel = DoNothingAgent(),
            probMutation = 0.8,
        )
    )
    
    // Game parameters for benchmark testing
    val gameParams = GameParams(
        numPlanets = 20,
        maxTicks = 1000,
        initialNeutralRatio = 0.5,
        minGrowthRate = 0.03,
        maxGrowthRate = 0.12
    )
    
    // Number of games to run for each opponent
    val gamesPerOpponent = 10
    
    // Results tracking
    val results = mutableMapOf<String, MutableMap<String, Int>>()
    
    println("Starting benchmark: ${mctsAgent.getAgentType()} vs various agents")
    println("Running $gamesPerOpponent games per opponent")
    println("-----------------------------------------")
    
    // Run games against each opponent
    for (opponent in opponents) {
        val opponentType = opponent.getAgentType()
        println("Testing against: $opponentType")
        
        // Track wins for this opponent
        val matchResults = mutableMapOf(
            "wins" to 0,
            "losses" to 0,
            "draws" to 0
        )
        
        // Run games with MCTS as player 1
        val runner = GameRunner(mctsAgent, opponent, gameParams)
        val p1Results = runner.runGames(gamesPerOpponent / 2)
        
        // Run games with MCTS as player 2
        val reverseRunner = GameRunner(opponent, mctsAgent, gameParams)
        val p2Results = reverseRunner.runGames(gamesPerOpponent / 2)
        
        // Combine results (counting MCTS wins)
        matchResults["wins"] = p1Results[Player.Player1]!! + p2Results[Player.Player2]!!
        matchResults["losses"] = p1Results[Player.Player2]!! + p2Results[Player.Player1]!!
        
        // Calculate draws (if any)
        val totalGames = matchResults["wins"]!! + matchResults["losses"]!!
        matchResults["draws"] = gamesPerOpponent - totalGames
        
        // Store results
        results[opponentType] = matchResults
        
        // Print results for this opponent
        val winRate = (matchResults["wins"]!! * 100.0) / gamesPerOpponent
        println("  Wins: ${matchResults["wins"]}, Losses: ${matchResults["losses"]}, Draws: ${matchResults["draws"]}")
        println("  Win rate: ${"%.1f".format(winRate)}%")
        println("-----------------------------------------")
    }
    
    // Print overall summary
    println("\nOverall results for ${mctsAgent.getAgentType()}:")
    var totalWins = 0
    var totalGames = 0
    
    results.forEach { (opponent, scores) ->
        totalWins += scores["wins"]!!
        totalGames += gamesPerOpponent
    }
    
    val overallWinRate = (totalWins * 100.0) / totalGames
    println("Total win rate across all opponents: ${"%.1f".format(overallWinRate)}%")
    
    // Compare specifically against SimpleEvoAgent
    val evoResults = results[opponents.last().getAgentType()]
    if (evoResults != null) {
        val evoWinRate = (evoResults["wins"]!! * 100.0) / gamesPerOpponent
        println("\nAgainst SimpleEvoAgent:")
        println("Win rate: ${"%.1f".format(evoWinRate)}%")
    }
} 