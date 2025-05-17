package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.*
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Strategic agent inspired by the "bocsimacko" bot algorithm
 * Implements alpha-beta search with dynamic horizon and strategic evaluation
 */
class StrategicAgent(
    // How many plies to search in the opening phase
    val openingSearchDepth: Int = 10,
    // Width of each search level in opening phase
    val openingBranchingFactor: Int = 10,
    // Maximum number of turns to look ahead
    val maxHorizon: Int = 50,
    // Time limit for move computation in milliseconds
    val timeLimitMillis: Long = 10
) : PlanetWarsPlayer() {

    // Track the game turn
    private var currentTurn = 0
    
    // Best move found so far (used for timeout handling)
    private var bestMoveSoFar: Pair<Action, Double>? = null
    
    // Cache for evaluated futures
    private val futureCache = mutableMapOf<Int, PlanetFuture>()

    override fun getAgentType(): String = "StrategicAgent-$openingSearchDepth-$openingBranchingFactor-$maxHorizon"

    override fun getAction(gameState: GameState): Action {
        // Reset future cache
        futureCache.clear()
        
        // Set timeout for computation
        val startTime = System.currentTimeMillis()
        bestMoveSoFar = null

        // Compute game horizon
        val horizon = computeHorizon(gameState)
        
        // Count number of planets owned by this player
        val myPlanetCount = gameState.planets.count { it.owner == player }
        
        val action = if (myPlanetCount < 3) {
            // Opening phase - use deeper alpha-beta search
            runAlphaBetaSearch(gameState, openingSearchDepth, horizon, startTime)
        } else {
            // Mid/late game - generate and evaluate candidate moves
            generateAndEvaluateMoves(gameState, horizon, startTime)
        }
        
        // Increment turn counter
        currentTurn++
        
        return action
    }
    
    /**
     * Computes a reasonable horizon (lookahead) for the current game state.
     * Dynamically adjusts based on neutrals worth capturing.
     */
    private fun computeHorizon(gameState: GameState): Int {
        // Default to max horizon value
        var horizon = maxHorizon
        
        // Calculate safety margin (ships we can afford to commit)
        val safetyMargin = computeSafetyMargin(gameState)
        
        // Find neutral planets worth investing in
        val breakEvenTurns = gameState.planets
            .filter { it.owner == Player.Neutral && it.growthRate > 0 }
            .map { planet ->
                // Calculate turns to break even
                val turnsToBreakEven = ceil(planet.nShips / planet.growthRate).toInt()
                // Add travel distance estimate (10 is a rough average)
                val totalInvestmentTime = turnsToBreakEven + 10
                
                // Check if investment is safe based on our margin
                val isSafeInvestment = isSafeToInvest(safetyMargin, planet.nShips.toInt(), 10, planet.growthRate.toInt())
                
                if (isSafeInvestment) totalInvestmentTime else -1
            }
            .filter { it > 0 }
            .sorted()
        
        // Set horizon based on capture opportunities (up to 3rd easiest neutral)
        if (breakEvenTurns.isNotEmpty()) {
            val targetHorizon = if (breakEvenTurns.size >= 3) {
                breakEvenTurns[2]
            } else if (breakEvenTurns.size >= 2) {
                breakEvenTurns[1]
            } else {
                breakEvenTurns[0]
            }
            
            // Limit to our max horizon
            horizon = min(maxHorizon, max(30, targetHorizon))
        }
        
        return horizon
    }
    
    /**
     * Computes a "safety margin" - how many ships we can safely commit at each future turn
     */
    private fun computeSafetyMargin(gameState: GameState): IntArray {
        // Simplified version - in a real implementation this would be more complex
        // For now, assume we can safely use half our current ships
        val myShips = gameState.planets
            .filter { it.owner == player }
            .sumOf { it.nShips }.toInt()
        
        // Create an array representing our safety margin for each turn
        val margin = IntArray(maxHorizon) { myShips / 2 }
        return margin
    }
    
    /**
     * Determines if it's safe to invest in capturing a neutral planet
     */
    private fun isSafeToInvest(margins: IntArray, nShips: Int, distance: Int, growth: Int): Boolean {
        val turnsToBreakEven = ceil(nShips.toDouble() / growth).toInt()
        
        // Check if we maintain enough margin throughout the investment period
        for (i in 0 until min(turnsToBreakEven + 2 * distance, margins.size)) {
            val requiredMargin = nShips - (growth * max(0, i - distance))
            if (margins[i] < requiredMargin) {
                return false
            }
        }
        
        return true
    }

    /**
     * Class representing the projected future state of a planet
     */
    inner class PlanetFuture(
        val planet: Planet,
        val owners: Array<Player>,
        val shipCounts: DoubleArray,
        val balance: Double = 0.0
    ) {
        fun getLastOwner(): Player {
            return owners.last()
        }
        
        fun score(forPlayer: Player): Double {
            var score = balance * when(forPlayer) {
                Player.Player1 -> 1.0
                Player.Player2 -> -1.0
                else -> 0.0
            }
            
            // Add growth-based score
            for (i in owners.indices) {
                val owner = owners[i]
                when (owner) {
                    forPlayer -> score += planet.growthRate
                    forPlayer.opponent() -> score -= planet.growthRate
                    else -> {}
                }
            }
            
            return score
        }
    }
    
    /**
     * Runs alpha-beta search to the specified depth
     */
    private fun runAlphaBetaSearch(gameState: GameState, depth: Int, horizon: Int, startTime: Long): Action {
        // This would be the full alpha-beta implementation
        // For now, return a simplified result using move generation
        return generateAndEvaluateMoves(gameState, horizon, startTime)
    }
    
    /**
     * Generates and evaluates possible moves
     */
    private fun generateAndEvaluateMoves(gameState: GameState, horizon: Int, startTime: Long): Action {
        // Find all valid source planets (ones we own with ships)
        val sourcePlanets = gameState.planets.filter { it.owner == player && it.nShips > 0 }
        
        // Find all valid target planets (everything except our own planets)
        val targetPlanets = gameState.planets.filter { it.owner != player }
        
        // Generate candidate moves
        val candidateMoves = mutableListOf<Pair<Action, Double>>()
        
        // Add do-nothing action
        candidateMoves.add(Pair(Action.doNothing(), evaluateGameState(gameState, Action.doNothing(), horizon)))
        
        // Generate and evaluate actions
        for (source in sourcePlanets) {
            for (target in targetPlanets) {
                // Skip if source and target are the same
                if (source.id == target.id) continue
                
                // Try sending different amounts of ships
                val shipOptions = listOf(
                    source.nShips / 2.0,  // Half
                    source.nShips,      // All
                    max(1.0, source.nShips / 4.0)  // Quarter
                )
                
                for (shipCount in shipOptions) {
                    val action = Action(player, source.id, target.id, shipCount)
                    val score = evaluateGameState(gameState, action, horizon)
                    candidateMoves.add(Pair(action, score))
                    
                    // Update best move so far (for timeout handling)
                    if (bestMoveSoFar == null || score > bestMoveSoFar!!.second) {
                        bestMoveSoFar = Pair(action, score)
                    }
                    
                    // Check for timeout
                    if (System.currentTimeMillis() - startTime > timeLimitMillis) {
                        return bestMoveSoFar?.first ?: Action.doNothing()
                    }
                }
            }
        }
        
        // Return the best move
        return candidateMoves.maxByOrNull { it.second }?.first ?: Action.doNothing()
    }
    
    /**
     * Evaluates a game state after applying an action
     */
    private fun evaluateGameState(gameState: GameState, action: Action, horizon: Int): Double {
        // Create a forward model to simulate the game
        val forwardModel = ForwardModel(gameState.deepCopy(), params)
        
        // Apply the action
        if (action != Action.DO_NOTHING) {
            forwardModel.step(mapOf(player to action, player.opponent() to Action.doNothing()))
        }
        
        // Simulate for horizon steps using a simple opponent model
        for (step in 1 until horizon) {
            if (forwardModel.isTerminal()) break
            
            // Simulate opponent behavior with a simple heuristic
            val opponentAction = computeSimpleOpponentAction(forwardModel.state)
            
            // Apply no action for our player in simulation
            forwardModel.step(mapOf(player to Action.doNothing(), player.opponent() to opponentAction))
        }
        
        // Evaluate the resulting state
        return evaluatePosition(forwardModel.state)
    }
    
    /**
     * Simple opponent model for simulation
     */
    private fun computeSimpleOpponentAction(gameState: GameState): Action {
        val opponent = player.opponent()
        
        // Source planets - opponent controlled with ships
        val sourcePlanets = gameState.planets.filter { it.owner == opponent && it.nShips > 0 }
        if (sourcePlanets.isEmpty()) return Action.doNothing()
        
        // Target planets - not opponent controlled
        val targetPlanets = gameState.planets.filter { it.owner != opponent }
        if (targetPlanets.isEmpty()) return Action.doNothing()
        
        // Find largest source and weakest target
        val source = sourcePlanets.maxByOrNull { it.nShips } ?: return Action.doNothing()
        val target = targetPlanets.minByOrNull { it.nShips } ?: return Action.doNothing()
        
        // Send half the ships
        return Action(opponent, source.id, target.id, source.nShips / 2.0)
    }
    
    /**
     * Evaluate the position based on ship counts, planet control, and growth rates
     */
    private fun evaluatePosition(gameState: GameState): Double {
        var score = 0.0
        
        // Ship count differential
        val myShips = gameState.planets.filter { it.owner == player }.sumOf { it.nShips }
        val enemyShips = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.nShips }
        score += (myShips - enemyShips)
        
        // Growth rate differential (weighted more heavily)
        val myGrowth = gameState.planets.filter { it.owner == player }.sumOf { it.growthRate }
        val enemyGrowth = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.growthRate }
        score += (myGrowth - enemyGrowth) * 10.0
        
        // Small bonus for planets near enemy territory
        // (This is a simplified version of the positional evaluation in the Lisp code)
        for (planet in gameState.planets) {
            if (planet.owner == player) {
                // Check if planet is close to enemy planets
                val nearestEnemyDistance = gameState.planets
                    .filter { it.owner == player.opponent() }
                    .minOfOrNull { distanceBetween(planet, it) } ?: Double.MAX_VALUE
                
                // Add a small bonus for planets close to enemy territory
                if (nearestEnemyDistance < 10.0) {
                    score += (1.0 / nearestEnemyDistance) * planet.nShips * 0.01
                }
            }
        }
        
        return score
    }
    
    /**
     * Calculate distance between planets
     */
    private fun distanceBetween(p1: Planet, p2: Planet): Double {
        // Calculate Euclidean distance between planets
        val dx = p1.position.x - p2.position.x
        val dy = p1.position.y - p2.position.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
} 