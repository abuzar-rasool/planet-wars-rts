package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.core.ForwardModel
import games.planetwars.core.GameParams
import games.planetwars.core.GameState
import games.planetwars.core.Player
import util.Vec2d
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Building blocks shared by the TeamTitans agents.
 *
 * The agents all follow the same idea: try a handful of candidate moves, simulate each one
 * forward with the game's ForwardModel, score the resulting position, and play the best move.
 */

/** Distances between every pair of planets. Planets never move, so this is built once per game. */
internal class PlanetDistances(numPlanets: Int, positionsById: Map<Int, Vec2d>) {
    private val matrix = Array(numPlanets) { DoubleArray(numPlanets) }

    init {
        for ((id1, pos1) in positionsById) {
            for ((id2, pos2) in positionsById) {
                if (id1 < numPlanets && id2 < numPlanets) {
                    matrix[id1][id2] = pos1.distance(pos2)
                }
            }
        }
    }

    fun between(planet1Id: Int, planet2Id: Int): Double = matrix[planet1Id][planet2Id]
}

/** Fleet sizes worth trying from a planet: half, all, or a quarter of its ships (whole ships, at least 1). */
internal fun candidateFleetSizes(availableShips: Double): List<Double> =
    listOf(availableShips / 2.0, availableShips, max(1.0, availableShips / 4.0))
        .map { max(1.0, floor(it)) }
        .filter { it <= availableShips }
        .distinct()

/**
 * Plays [action] for [player] (if it is a real move), then lets the game run for up to
 * [horizon] - 1 more ticks with nobody acting, stopping early if the game ends.
 * Returns the resulting state; [state] is left unchanged.
 */
internal fun simulate(state: GameState, params: GameParams, player: Player, action: Action, horizon: Int): GameState {
    val model = ForwardModel(state.deepCopy(), params)
    if (action != Action.DO_NOTHING) {
        model.step(mapOf(player to action, player.opponent() to Action.DO_NOTHING))
    }
    for (tick in 1 until horizon) {
        if (model.isTerminal()) break
        model.step(mapOf(player to Action.DO_NOTHING, player.opponent() to Action.DO_NOTHING))
    }
    return model.state
}

/**
 * How many ticks ahead to simulate, tuned from league results across game settings.
 * In general: longer look-ahead for slow, small, long games; shorter for fast, large, short ones.
 * Each rule only raises or lowers the running value, so rule order matters.
 */
internal fun planningHorizon(params: GameParams): Int {
    var horizon = 90

    // Map size: H50 did best on large maps (23+ planets), H100 on small and medium ones.
    horizon = when {
        params.numPlanets >= 23 -> min(horizon, 70)
        params.numPlanets <= 15 -> max(horizon, 100)
        else -> max(horizon, 90)
    }

    // Fleet speed: fast games favour short look-ahead, slow games long look-ahead.
    horizon = when {
        params.transporterSpeed >= 4.1 -> min(horizon, 70)
        params.transporterSpeed <= 3.0 -> max(horizon, 110)
        else -> max(horizon, 90)
    }

    // Growth rate: high growth changes the game quickly, low growth slowly.
    horizon = when {
        params.maxGrowthRate >= 0.16 -> min(horizon, 90)
        params.maxGrowthRate <= 0.08 -> max(horizon, 100)
        else -> horizon
    }

    // Neutral ratio: unusual ratios (away from the ~0.3 standard) benefit from deeper search.
    horizon = if (params.initialNeutralRatio <= 0.28 || params.initialNeutralRatio >= 0.33) {
        max(horizon, 95)
    } else {
        min(horizon, 85)
    }

    // Game length: short games need quick decisions, long games can afford deeper search.
    horizon = when {
        params.maxTicks <= 800 -> min(horizon, 70)
        params.maxTicks >= 1201 -> max(horizon, 110)
        else -> max(horizon, 90)
    }

    // Beyond ~130 ticks results got worse and searches risk the 100ms move limit.
    return horizon.coerceIn(50, 130)
}

/**
 * The TeamTitansV3 search, used by [TeamTitansAgentV3] and [TeamTitansPartialAgentV2].
 *
 * Tries sending half, all, or a quarter of the ships from each idle planet to every other planet,
 * simulates each option [planningHorizon] ticks ahead, and returns the move that leads to the
 * best-scoring position. Doing nothing is the baseline a move has to beat.
 */
internal class TitansPlanner(private val player: Player, private val params: GameParams) {
    private val maxHorizon = planningHorizon(params)

    // A planet further than this from all our other planets counts as isolated.
    private val isolationThreshold = params.width / 4.0

    // Built on the first move, once the planet positions are known.
    private var distances: PlanetDistances? = null

    fun bestAction(state: GameState, startTime: Long, timeLimitMillis: Long): Action {
        val distances = distances
            ?: PlanetDistances(params.numPlanets, state.planets.associate { it.id to it.position })
                .also { this.distances = it }
        val horizon = min(maxHorizon, params.maxTicks - state.gameTick)
        val deadline = startTime + timeLimitMillis

        var bestAction = Action.DO_NOTHING
        var bestScore = evaluate(simulate(state, params, player, bestAction, horizon), distances)

        val sources = state.planets.filter { it.owner == player && it.nShips > 0 && it.transporter == null }
        for (source in sources) {
            for (target in state.planets) {
                if (System.currentTimeMillis() > deadline) return bestAction
                if (source.id == target.id) continue

                for (fleetSize in candidateFleetSizes(source.nShips)) {
                    val action = Action(player, source.id, target.id, fleetSize)
                    val score = evaluate(simulate(state, params, player, action, horizon), distances)
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
    private fun evaluate(state: GameState, distances: PlanetDistances): Double {
        val myPlanets = state.planets.filter { it.owner == player }
        val oppPlanets = state.planets.filter { it.owner == player.opponent() }
        var score = 0.0

        // Ships and production.
        score += myPlanets.sumOf { it.nShips } - oppPlanets.sumOf { it.nShips }
        score += (myPlanets.sumOf { it.growthRate } - oppPlanets.sumOf { it.growthRate }) * 10.0

        // Ship counts 20 ticks from now if nothing changes.
        val myFutureShips = myPlanets.sumOf { it.nShips + it.growthRate * 20 }
        val oppFutureShips = oppPlanets.sumOf { it.nShips + it.growthRate * 20 }
        score += (myFutureShips - oppFutureShips) * 0.5

        for (planet in myPlanets) {
            // Small bonus for strong planets right next to the enemy.
            val nearestEnemy = oppPlanets.minOfOrNull { distances.between(planet.id, it.id) } ?: Double.MAX_VALUE
            if (nearestEnemy < 10.0 && nearestEnemy > 0) {
                score += (1.0 / nearestEnemy) * planet.nShips * 0.01
            }

            // Penalty for productive planets far from the rest of our planets.
            if (myPlanets.size > 1) {
                val nearestFriend = myPlanets.filter { it.id != planet.id }
                    .minOfOrNull { distances.between(planet.id, it.id) } ?: Double.MAX_VALUE
                if (nearestFriend > isolationThreshold) {
                    score -= planet.growthRate * 2.0
                }
            }
        }

        score += (myPlanets.size - oppPlanets.size) * 5.0

        // Wiping out a side decides the game.
        if (oppPlanets.isEmpty() && myPlanets.isNotEmpty()) score += 10000.0
        if (myPlanets.isEmpty() && oppPlanets.isNotEmpty()) score -= 10000.0

        return score
    }
}
