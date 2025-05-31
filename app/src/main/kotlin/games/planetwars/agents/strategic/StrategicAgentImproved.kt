package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.*
import kotlin.math.*

/**
 * Simplified strategic agent:
 * - Removed dead code and redundant complexity
 * - Keeps core strategic evaluation logic
 * - Uses simple horizon calculation
 * - Single move generation approach
 */
class StrategicAgentImproved(
    val maxHorizon: Int = 100   ,
    val timeLimitMillis: Long = 90L
) : PlanetWarsPlayer() {

    override fun getAgentType(): String = "StrategicAgentImproved-H${maxHorizon}"

    override fun getAction(gameState: GameState): Action {
        val startTime = System.currentTimeMillis()
        val horizon = computeHorizon(gameState)
        
        return generateAndEvaluateMoves(gameState, horizon, startTime)
    }

    private fun computeHorizon(gameState: GameState): Int {
        val remainingTicks = params.maxTicks - gameState.gameTick
        return min(maxHorizon, remainingTicks)
    }

    private fun generateAndEvaluateMoves(
        gameState: GameState,
        horizon: Int,
        startTime: Long
    ): Action {
        var bestAction = Action.doNothing()
        var bestScore = evaluateGameState(gameState, Action.doNothing(), horizon)

        val sourcePlanets = gameState.planets.filter { it.owner == player && it.nShips > 0 }
        val targetPlanets = gameState.planets.filter { it.owner != player }
        
        // Pre-calculate ship options to avoid duplication
        val shipOptions = { nShips: Double -> 
            listOf(nShips / 2.0, nShips, max(1.0, nShips / 4.0))
        }

        for (src in sourcePlanets) {
            for (dst in targetPlanets) {
                if (System.currentTimeMillis() - startTime > timeLimitMillis) return bestAction
                if (src.id == dst.id) continue
                
                for (numShips in shipOptions(src.nShips)) {
                    val action = Action(player, src.id, dst.id, numShips)
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
        val model = ForwardModel(gameState.deepCopy(), params)
        
        // Apply the action
        if (action != Action.DO_NOTHING) {
            model.step(mapOf(player to action, player.opponent() to Action.doNothing()))
        }
        
        // Simulate forward without opponent actions (simplified)
        for (step in 1 until horizon) {
            if (model.isTerminal()) break
            model.step(mapOf(player to Action.doNothing(), player.opponent() to Action.doNothing()))
        }
        
        return evaluatePosition(model.state)
    }

    private fun evaluatePosition(gameState: GameState): Double {
        var score = 0.0
        
        // Ship count difference
        val myShips = gameState.planets.filter { it.owner == player }.sumOf { it.nShips }
        val oppShips = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.nShips }
        score += (myShips - oppShips)
        
        // Growth rate difference (weighted heavily)
        val myGrowth = gameState.planets.filter { it.owner == player }.sumOf { it.growthRate }
        val oppGrowth = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.growthRate }
        score += (myGrowth - oppGrowth) * 10.0
        
        // Positional bonus for planets near enemies
        for (planet in gameState.planets.filter { it.owner == player }) {
            val nearestEnemy = gameState.planets.filter { it.owner == player.opponent() }
                .minOfOrNull { distanceBetween(planet, it) } ?: Double.MAX_VALUE
            if (nearestEnemy < 10.0) {
                score += (1.0 / nearestEnemy) * planet.nShips * 0.01
            }
        }
        
        return score
    }

    private fun distanceBetween(p1: Planet, p2: Planet): Double {
        val dx = p1.position.x - p2.position.x
        val dy = p1.position.y - p2.position.y
        return sqrt(dx * dx + dy * dy)
    }
} 