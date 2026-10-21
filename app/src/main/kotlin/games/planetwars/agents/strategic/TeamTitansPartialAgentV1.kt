package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PartialObservationPlayer
import games.planetwars.core.DefaultHiddenInfoSampler
import games.planetwars.core.GameParams
import games.planetwars.core.GameState
import games.planetwars.core.GameStateReconstructor
import games.planetwars.core.Observation
import games.planetwars.core.Player
import kotlin.math.max
import kotlin.math.min

/**
 * Partial-observability agent adapted from [TeamTitansAgentV3].
 *
 * Guesses the hidden information with a [GameStateReconstructor], simulates candidate moves on that
 * guess, and looks less far ahead the more enemy ship counts are hidden.
 */
class TeamTitansPartialAgentV1(
    val timeLimitMillis: Long = 90L
) : PartialObservationPlayer() {

    private lateinit var reconstructor: GameStateReconstructor
    private var maxHorizon: Int = 100

    // A planet further than this from all our other planets counts as isolated.
    private var isolationThreshold: Double = 0.0

    // Built on the first move, once the planet positions are known.
    private var distances: PlanetDistances? = null

    override fun prepareToPlayAs(player: Player, params: GameParams, opponent: Player?): PartialObservationPlayer {
        super.prepareToPlayAs(player, params, opponent)
        reconstructor = GameStateReconstructor(DefaultHiddenInfoSampler(params))
        maxHorizon = cautiousHorizon(params)
        distances = null
        isolationThreshold = params.width / 4.0
        return this
    }

    override fun getAgentType(): String = "TeamTitansPartialAgentV1"

    override fun getAction(observation: Observation): Action {
        val deadline = System.currentTimeMillis() + timeLimitMillis
        val distances = distances
            ?: PlanetDistances(params.numPlanets, observation.observedPlanets.associate { it.id to it.position })
                .also { this.distances = it }
        val estimatedState = reconstructor.reconstruct(observation)
        val horizon = (min(maxHorizon, params.maxTicks - observation.gameTick) * horizonScale(observation)).toInt()

        fun score(action: Action): Double {
            if (horizon <= 0) return evaluate(observation, estimatedState, distances)
            return evaluate(observation, simulate(estimatedState, params, player, action, horizon), distances)
        }

        var bestAction = Action.DO_NOTHING
        var bestScore = score(bestAction)

        val sources = observation.observedPlanets.filter {
            it.owner == player && it.transporter == null && it.nShips != null && it.nShips > 0
        }
        for (source in sources) {
            if (System.currentTimeMillis() > deadline) break

            for (target in observation.observedPlanets) {
                if (source.id == target.id) continue

                for (fleetSize in candidateFleetSizes(source.nShips!!)) {
                    val action = Action(player, source.id, target.id, fleetSize)
                    val score = score(action)
                    if (score > bestScore) {
                        bestScore = score
                        bestAction = action
                    }
                }
            }
        }
        return bestAction
    }

    /** Like V3's [planningHorizon], but shorter because the future is less certain. */
    private fun cautiousHorizon(params: GameParams): Int {
        var horizon = (90 * 0.8).toInt()

        horizon = when {
            params.numPlanets >= 23 -> min(horizon, 60)
            params.numPlanets <= 15 -> max(horizon, 80)
            else -> max(horizon, 70)
        }

        horizon = when {
            params.transporterSpeed >= 4.1 -> min(horizon, 60)
            params.transporterSpeed <= 3.0 -> max(horizon, 90)
            else -> max(horizon, 70)
        }

        horizon = when {
            params.maxGrowthRate >= 0.16 -> min(horizon, 70)
            params.maxGrowthRate <= 0.08 -> max(horizon, 80)
            else -> horizon
        }

        return horizon.coerceIn(40, 100)
    }

    /** Shrinks the look-ahead as more enemy ship counts are hidden from us. */
    private fun horizonScale(observation: Observation): Double {
        val hiddenEnemyPlanets = observation.observedPlanets.count {
            it.owner == player.opponent() && it.nShips == null
        }
        return when {
            hiddenEnemyPlanets >= 5 -> 0.6
            hiddenEnemyPlanets >= 3 -> 0.7
            hiddenEnemyPlanets >= 1 -> 0.8
            else -> 0.9
        }
    }

    /**
     * Scores a position from our point of view. Higher is better.
     *
     * Note: this reads only [observation], never [state], so every candidate move gets the same score.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun evaluate(observation: Observation, state: GameState, distances: PlanetDistances): Double {
        val myPlanets = observation.observedPlanets.filter { it.owner == player }
        val enemyPlanets = observation.observedPlanets.filter { it.owner == player.opponent() }
        val neutralPlanets = observation.observedPlanets.filter { it.owner == Player.Neutral }
        var score = 0.0

        // Ships, guessing hidden enemy planets hold the average of the visible ones
        // (or our own average if none are visible). Down-weighted for the uncertainty.
        val myShips = myPlanets.mapNotNull { it.nShips }.sum()
        val knownEnemyShips = enemyPlanets.mapNotNull { it.nShips }
        val avgEnemyShips = if (knownEnemyShips.isNotEmpty()) knownEnemyShips.average() else myShips / myPlanets.size.toDouble()
        val estimatedEnemyShips = knownEnemyShips.sum() + enemyPlanets.count { it.nShips == null } * avgEnemyShips
        score += (myShips - estimatedEnemyShips) * 0.8

        // Production. Growth rates are always visible, so this gets more weight than in V3.
        score += (myPlanets.sumOf { it.growthRate } - enemyPlanets.sumOf { it.growthRate }) * 15.0

        for (planet in myPlanets) {
            // Small bonus for strong planets right next to the enemy.
            val nearestEnemy = enemyPlanets.minOfOrNull { distances.between(planet.id, it.id) } ?: Double.MAX_VALUE
            if (nearestEnemy < 10.0) {
                score += (1.0 / nearestEnemy) * (planet.nShips ?: 0.0) * 0.005
            }

            // Penalty for productive planets far from the rest of our planets.
            if (myPlanets.size > 1) {
                val nearestFriend = myPlanets.filter { it.id != planet.id }
                    .minOfOrNull { distances.between(planet.id, it.id) } ?: Double.MAX_VALUE
                if (nearestFriend > isolationThreshold) {
                    score -= planet.growthRate * 3.0
                }
            }
        }

        // Bonus for neutral planets we can reach before the enemy.
        for (neutral in neutralPlanets) {
            val nearestMine = myPlanets.minOfOrNull { distances.between(neutral.id, it.id) } ?: Double.MAX_VALUE
            val nearestEnemy = enemyPlanets.minOfOrNull { distances.between(neutral.id, it.id) } ?: Double.MAX_VALUE
            if (nearestMine < nearestEnemy) {
                score += neutral.growthRate * 2.0 * (1.0 / nearestMine)
            }
        }

        score += (myPlanets.size - enemyPlanets.size) * 8.0

        // Wiping out a side decides the game.
        if (enemyPlanets.isEmpty() && myPlanets.isNotEmpty()) score += 10000.0
        if (myPlanets.isEmpty() && enemyPlanets.isNotEmpty()) score -= 10000.0

        return score
    }
}
