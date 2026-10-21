package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.GameState
import kotlin.math.max
import kotlin.math.min

/**
 * Simulation-based agent with a fixed look-ahead of up to [maxHorizon] ticks.
 *
 * Tries sending half, all, or a quarter of the ships from each of our planets to every planet we
 * don't own, simulates each option forward, and plays the one leading to the best position.
 */
class TeamTitansAgentV2(
    val maxHorizon: Int = 75,
    val timeLimitMillis: Long = 90L
) : PlanetWarsPlayer() {

    override fun getAgentType(): String = "TeamTitansAgentV2-H${maxHorizon}"

    override fun getAction(gameState: GameState): Action {
        val deadline = System.currentTimeMillis() + timeLimitMillis
        val horizon = min(maxHorizon, params.maxTicks - gameState.gameTick)

        var bestAction = Action.DO_NOTHING
        var bestScore = evaluate(simulate(gameState, params, player, bestAction, horizon))

        val sources = gameState.planets.filter { it.owner == player && it.nShips > 0 }
        val targets = gameState.planets.filter { it.owner != player }

        for (source in sources) {
            for (target in targets) {
                if (System.currentTimeMillis() > deadline) return bestAction
                if (source.id == target.id) continue

                for (fleetSize in listOf(source.nShips / 2.0, source.nShips, max(1.0, source.nShips / 4.0))) {
                    val action = Action(player, source.id, target.id, fleetSize)
                    val score = evaluate(simulate(gameState, params, player, action, horizon))
                    if (score > bestScore) {
                        bestScore = score
                        bestAction = action
                    }
                }
            }
        }
        return bestAction
    }

    /** Scores a position from our point of view. Higher is better. */
    private fun evaluate(gameState: GameState): Double {
        val myPlanets = gameState.planets.filter { it.owner == player }
        val oppPlanets = gameState.planets.filter { it.owner == player.opponent() }
        var score = 0.0

        score += myPlanets.sumOf { it.nShips } - oppPlanets.sumOf { it.nShips }
        score += (myPlanets.sumOf { it.growthRate } - oppPlanets.sumOf { it.growthRate }) * 10.0

        // Small bonus for strong planets right next to the enemy.
        for (planet in myPlanets) {
            val nearestEnemy = oppPlanets.minOfOrNull { planet.position.distance(it.position) } ?: Double.MAX_VALUE
            if (nearestEnemy < 10.0) {
                score += (1.0 / nearestEnemy) * planet.nShips * 0.01
            }
        }

        return score
    }
}
