package games.planetwars.view

import games.planetwars.agents.evo.SimpleEvoAgent
import games.planetwars.agents.mcts.MCTSAgent
import games.planetwars.agents.DoNothingAgent
import games.planetwars.core.GameParams
import games.planetwars.runners.GameRunner
import games.planetwars.core.GameStateFactory
import xkg.jvm.AppLauncher
import games.planetwars.core.Player

fun main() {
    // Create game parameters with more planets and longer gameplay for interesting matches
    val gameParams = GameParams(
        numPlanets = 20, 
        maxTicks = 1000,
        initialNeutralRatio = 0.5,   // Half the planets start neutral
        minGrowthRate = 0.03,        // Slightly higher min growth rate
        maxGrowthRate = 0.12         // Slightly higher max growth rate
    )
    
    // Create initial game state
    val gameState = GameStateFactory(gameParams).createGame()
    
    // Create our improved MCTS agent with performance-optimized parameters
    val mctsAgent = MCTSAgent(
        numIterations = 800,              // Reduced iterations for faster decisions
        explorationConstant = 1.5,        // Slightly higher exploration to counter SimpleEvoAgent
        maxSimDepth = 10,                 // Reduced lookahead for faster simulations
        adaptiveDepthRate = 0.5,          // Adaptive depth increases with game progress
        opponentModelWeight = 0.4,        // Higher weight on opponent modeling
        useProgressiveWidening = true,    // Use progressive widening to handle high branching factor
        progressiveWideningBase = 0.5,
        progressiveWideningExponent = 0.5,
        useEarlyGameGrowthStrategy = true, // Focus on growth in early game
        smartTargetSelection = true,       // Use stage-based target selection
        useFirstPlayUrgency = true,        // Encourages exploration of unvisited nodes
        firstPlayUrgencyValue = 10.0,      // Value for unexplored nodes
        timeLimitMillis = 50               // Hard time limit of 50ms per decision
    )
    
    // Create the SimpleEvoAgent to compete against
    val evoAgent = SimpleEvoAgent(
        useShiftBuffer = true,
        nEvals = 30,
        sequenceLength = 400,
        opponentModel = DoNothingAgent(),
        probMutation = 0.8,
    )
    
    // Let the MCTS agent play as Player1
    val gameRunner = GameRunner(mctsAgent, evoAgent, gameParams)
    
    // Set up the game title
    val title = "${mctsAgent.getAgentType()} : Planet Wars : ${evoAgent.getAgentType()}"
    
    // Launch the game visualization
    AppLauncher(
        preferredWidth = gameParams.width,
        preferredHeight = gameParams.height,
        app = GameView(params = gameParams, gameState = gameState, gameRunner = gameRunner),
        title = title,
        frameRate = 50.0,
    ).launch()
} 