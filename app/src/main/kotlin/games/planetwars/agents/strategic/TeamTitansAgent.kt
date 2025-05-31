package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.*
import kotlin.math.*

/**
 * Team Titans Agent: A strategic agent with dynamically computed maxHorizon.
 * - Adapts its planning horizon based on game parameters and performance data.
 * - Aims to improve adaptability across different game conditions.
 * - Based on StrategicAgentDynamic with minor baseline horizon adjustment.
 */
class TeamTitansAgent(
    // Default time limit, can be overridden
    val timeLimitMillis: Long = 90L
) : PlanetWarsPlayer() {

    // Dynamically determined maxHorizon for the current game
    private var dynamicMaxHorizon: Int = 100 // Default, will be set in prepareToPlayAs

    private lateinit var distanceMatrix: Array<Array<Double>>
    private var distancesInitialized = false
    private var isolationThreshold: Double = 0.0

    override fun prepareToPlayAs(player: Player, params: GameParams, opponent: String?): String {
        super.prepareToPlayAs(player, params, opponent) // Essential for PlanetWarsPlayer
        
        this.dynamicMaxHorizon = calculateDynamicHorizon(this.params)
        
        this.distancesInitialized = false // Reset for each new game/match
        this.isolationThreshold = this.params.width / 4.0 // Pre-calculate
        return getAgentType() // Return the agent type
    }

    override fun getAgentType(): String = "TeamTitansAgent"

    private fun calculateDynamicHorizon(params: GameParams): Int {
        var horizon = 90 // Start with a baseline from good performing H80-H120 agents (adjusted from 80)

        // Adjust based on Planet Count
        // Data suggests:
        // - H50 good for Large (23-30)
        // - H100 good for Medium (16-22) and Small (10-15)
        // - H200, H300 generally worse for large maps.
        // Let's try to favor smaller horizons for larger maps.
        when {
            params.numPlanets >= 23 -> horizon = min(horizon, 70) // More planets, shorter horizon to manage complexity
            params.numPlanets <= 15 -> horizon = max(horizon, 100) // Fewer planets, can afford longer horizon
            else -> horizon = max(horizon, 90) // Medium
        }

        // Adjust based on Transporter Speed
        // Data suggests:
        // - H50 very good for Fast (4.1-5.0)
        // - H100 very good for Slow (2.0-3.0)
        // - H60, H70, H80, H90 also good for Fast
        // Games are quicker with fast transporters, longer horizon might be less useful or too slow.
        when {
            params.transporterSpeed >= 4.1 -> horizon = min(horizon, 70) // Fast speed, shorter horizon
            params.transporterSpeed <= 3.0 -> horizon = max(horizon, 110) // Slow speed, can look further
            else -> horizon = max(horizon, 90) // Medium speed
        }
        
        // Adjust based on Growth Rate
        // Data suggests H100 generally strong across growth rates, but H50 also competitive.
        // High growth might mean faster game changes, shorter horizon?
        // Low growth might mean slower game, longer horizon useful?
        // H80, H90, H100, H110 (60-70% WR) seem good overall.
        // Let's be cautious here, maybe slight adjustment.
        when {
            params.maxGrowthRate >= 0.16 -> horizon = min(horizon, 90) // High growth
            params.maxGrowthRate <= 0.08 -> horizon = max(horizon, 100) // Low growth
        }

        // Adjust based on Neutral Ratio
        // Data for H100 shows good performance across neutral ratios.
        // ~0.3 is competition standard. H100 has ~73% WR.
        // Low neutral (more enemy planets initially) might benefit from deeper search.
        // High neutral (more expansion targets) might also benefit from deeper search.
        // Let's favor slightly longer horizon if not medium.
        if (params.initialNeutralRatio <= 0.28 || params.initialNeutralRatio >= 0.33) {
             horizon = max(horizon, 95)
        } else { // Medium neutral (0.29-0.32)
             horizon = min(horizon, 85)
        }
        
        // Adjust based on Game Duration (maxTicks)
        // Shorter games might need quicker, possibly shallower search.
        // Longer games can afford deeper search.
        // H100 good for Long games. H50 for short/medium.
        // Max Ticks: Competition: 200-2000. Data shows ranges like 400-800, 801-1200, 1201-1500
        when {
            params.maxTicks <= 800 -> horizon = min(horizon, 70)    // Short games
            params.maxTicks >= 1201 -> horizon = max(horizon, 110)  // Long games
            else -> horizon = max(horizon, 90) // Medium duration games
        }

        // Clamp horizon to a reasonable range, e.g., 30 to 150, to avoid extremes.
        // The game rules state a 100ms timeout. Very large horizons will timeout.
        // From data, H50 to H120 seem to be the sweet spot. H150+ performance drops.
        // H300 and H200 perform worse than H100 and H50 in many cases.
        return horizon.coerceIn(50, 130)
    }


    override fun getAction(gameState: GameState): Action {
        val startTime = System.currentTimeMillis()

        if (!distancesInitialized) {
            val planets = gameState.planets
            // Ensure distanceMatrix is sized based on params.numPlanets,
            // as planet IDs go up to params.numPlanets - 1.
            distanceMatrix = Array(params.numPlanets) { Array(params.numPlanets) { 0.0 } }
            for (p1 in planets) {
                for (p2 in planets) {
                    if (p1.id < params.numPlanets && p2.id < params.numPlanets) { // Bounds check for safety
                        if (p1.id == p2.id) {
                            distanceMatrix[p1.id][p2.id] = 0.0
                        } else {
                            val dx = p1.position.x - p2.position.x
                            val dy = p1.position.y - p2.position.y
                            distanceMatrix[p1.id][p2.id] = sqrt(dx * dx + dy * dy)
                        }
                    }
                }
            }
            distancesInitialized = true
        }
        // Use the dynamically calculated horizon
        val effectiveHorizon = min(dynamicMaxHorizon, params.maxTicks - gameState.gameTick)
        
        return generateAndEvaluateMoves(gameState, effectiveHorizon, startTime)
    }

    // generateAndEvaluateMoves, evaluateGameState, evaluatePosition, distanceBetween
    // are taken directly from the simplified StrategicAgentImproved

    private fun generateAndEvaluateMoves(
        gameState: GameState,
        horizon: Int,
        startTime: Long
    ): Action {
        var bestAction = Action.doNothing()
        // Evaluate doNothing with the *actual* horizon being used for this turn.
        var bestScore = evaluateGameState(gameState, Action.doNothing(), horizon) 

        val sourcePlanets = gameState.planets.filter { it.owner == player && it.nShips > 0 && it.transporter == null }
        val targetPlanets = gameState.planets // Can target any planet, including own for consolidation (though eval would penalize)

        val shipOptions = { nShips: Double ->
            // Ensure options are valid (>=1 ship, <= available ships)
            // And that we don't send tiny fractions that are effectively zero
            listOf(nShips / 2.0, nShips, max(1.0, nShips / 4.0))
                .map { max(1.0, floor(it)) } // Send whole or half ships, at least 1
                .filter { it <= nShips && it >= 1.0 }
                .distinct()
        }

        for (src in sourcePlanets) {
            for (dst in targetPlanets) {
                if (System.currentTimeMillis() - startTime > timeLimitMillis) return bestAction
                if (src.id == dst.id) continue // Don't send to self like this

                for (numShipsToSend in shipOptions(src.nShips)) {
                    if (numShipsToSend < 1.0) continue // Must send at least 1 ship

                    val action = Action(player, src.id, dst.id, numShipsToSend)
                    
                    // Action validation (as per game_rules.mdc)
                    if (src.nShips < numShipsToSend) continue // Already implicitly handled by shipOptions filter usually
                    // if (src.transporter != null) continue // Already handled by sourcePlanets filter

                    val score = evaluateGameState(gameState, action, horizon)
                    if (score > bestScore) {
                        bestScore = score
                        bestAction = action
                    }
                }
            }
        }
        return bestAction
    }

    private fun evaluateGameState(
        gameState: GameState,
        action: Action,
        horizon: Int
    ): Double {
        // If horizon is 0 or negative, just evaluate current state post-action (if any)
        if (horizon <= 0) {
            val tempState = gameState.deepCopy()
            if (action != Action.DO_NOTHING) {
                 val modelForAction = ForwardModel(tempState, params)
                 modelForAction.step(mapOf(player to action, player.opponent() to Action.doNothing()))
                 return evaluatePosition(modelForAction.state)
            }
            return evaluatePosition(tempState)
        }

        val model = ForwardModel(gameState.deepCopy(), params)
        
        if (action != Action.DO_NOTHING) {
            model.step(mapOf(player to action, player.opponent() to Action.doNothing()))
        }
        
        for (step in 1 until horizon) { // Iterate up to horizon-1 steps for a total of horizon simulation depth
            if (model.isTerminal()) break
            // In this simplified model, opponent does nothing during our simulation.
            // This was the approach in StrategicAgentImproved after simplification.
            model.step(mapOf(player to Action.doNothing(), player.opponent() to Action.doNothing()))
        }
        
        return evaluatePosition(model.state)
    }

    private fun getPlanetDistance(planet1Id: Int, planet2Id: Int): Double {
        // Assumes distanceMatrix is initialized and IDs are valid and within bounds.
        // Bounds were checked during initialization.
        return distanceMatrix[planet1Id][planet2Id]
    }

    private fun evaluatePosition(gameState: GameState): Double {
        var score = 0.0
        
        val myPlanets = gameState.planets.filter { it.owner == player }
        val oppPlanets = gameState.planets.filter { it.owner == player.opponent() }

        val myShips = myPlanets.sumOf { it.nShips }
        val oppShips = oppPlanets.sumOf { it.nShips }
        score += (myShips - oppShips)
        
        val myGrowth = myPlanets.sumOf { it.growthRate }
        val oppGrowth = oppPlanets.sumOf { it.growthRate }
        score += (myGrowth - oppGrowth) * 10.0 // Growth is important

        // Production potential: sum of (ships + growth * remaining_horizon_for_eval)
        // This gives a rough idea of future ship counts if no actions are taken.
        // Let's use a fixed lookahead for this part of evaluation, e.g., 20 ticks, 
        // as the main horizon is already variable.
        val evalLookahead = 20
        val myFutureShips = myPlanets.sumOf { it.nShips + it.growthRate * evalLookahead }
        val oppFutureShips = oppPlanets.sumOf { it.nShips + it.growthRate * evalLookahead }
        score += (myFutureShips - oppFutureShips) * 0.5 // Add a smaller weight for this future projection


        // Strategic positioning: bonus for planets near enemies, penalty for isolated planets.
        // This was in the original improved agent, keeping it.
        for (myPlanet in myPlanets) {
            val nearestEnemyDist = oppPlanets.minOfOrNull { getPlanetDistance(myPlanet.id, it.id) } ?: Double.MAX_VALUE
            if (nearestEnemyDist < 10.0 && nearestEnemyDist > 0) { // if very close
                score += (1.0 / nearestEnemyDist) * myPlanet.nShips * 0.01 // Bonus for forward positions
            }

            // Penalty if too isolated from own planets (encourages cohesion)
            if (myPlanets.size > 1) {
                val nearestFriendDist = myPlanets.filter { it.id != myPlanet.id}
                                           .minOfOrNull { getPlanetDistance(myPlanet.id,it.id) } ?: Double.MAX_VALUE
                if (nearestFriendDist > isolationThreshold) { // Use pre-calculated threshold
                    score -= myPlanet.growthRate * 2.0 // Penalize isolation of productive planets
                }
            }
        }
        
        // Bonus for having more planets
        score += (myPlanets.size - oppPlanets.size) * 5.0

        // If opponent has no planets, it's a huge win
        if (oppPlanets.isEmpty() && myPlanets.isNotEmpty()) {
            score += 10000.0
        }
        // If I have no planets, it's a huge loss
        if (myPlanets.isEmpty() && oppPlanets.isNotEmpty()) {
            score -= 10000.0
        }

        return score
    }
} 