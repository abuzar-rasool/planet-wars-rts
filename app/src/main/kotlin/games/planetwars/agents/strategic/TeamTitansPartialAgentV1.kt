package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PartialObservationPlayer
import games.planetwars.core.DefaultHiddenInfoSampler
import games.planetwars.core.ForwardModel
import games.planetwars.core.GameParams
import games.planetwars.core.GameState
import games.planetwars.core.GameStateReconstructor
import games.planetwars.core.Observation
import games.planetwars.core.PlanetObservation
import games.planetwars.core.Player
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Team Titans Partial Observation Agent
 * Adapts the successful strategies from TeamTitansV3 for partial observability:
 * - Uses GameStateReconstructor to estimate hidden information
 * - Adapts evaluation for uncertainty in enemy ship counts
 * - Maintains dynamic horizon adjustment from V3
 * - Adds risk assessment for partial information
 */
class TeamTitansPartialAgentV1(
    val timeLimitMillis: Long = 90L
) : PartialObservationPlayer() {

    // Core components
    private lateinit var sampler: DefaultHiddenInfoSampler
    private lateinit var reconstructor: GameStateReconstructor

    // Dynamic horizon (adapted from V3)
    private var dynamicMaxHorizon: Int = 100

    // Distance caching (adapted from V3)
    private lateinit var distanceMatrix: Array<Array<Double>>
    private var distancesInitialized = false
    private var isolationThreshold: Double = 0.0

    override fun prepareToPlayAs(player: Player, params: GameParams, opponent: Player?): PartialObservationPlayer {
        super.prepareToPlayAs(player, params, opponent)

        // Initialize reconstruction tools
        this.sampler = DefaultHiddenInfoSampler(params)
        this.reconstructor = GameStateReconstructor(sampler)

        // Set dynamic horizon (adapted from V3)
        this.dynamicMaxHorizon = calculateDynamicHorizon(params)

        // Reset distance tracking
        this.distancesInitialized = false
        this.isolationThreshold = params.width / 4.0

        return this
    }

    override fun getAgentType(): String = "TeamTitansPartialAgentV1"

    override fun getAction(observation: Observation): Action {
        val startTime = System.currentTimeMillis()

        // Initialize distance matrix if needed
        if (!distancesInitialized) {
            initializeDistanceMatrix(observation.observedPlanets)
        }

        // Reconstruct a possible game state
        val estimatedState = reconstructor.reconstruct(observation)

        // Use the dynamic horizon, but consider uncertainty
        val effectiveHorizon = calculateEffectiveHorizon(observation)

        return generateAndEvaluateMoves(observation, estimatedState, effectiveHorizon, startTime)
    }

    private fun initializeDistanceMatrix(planets: List<PlanetObservation>) {
        distanceMatrix = Array(params.numPlanets) { Array(params.numPlanets) { 0.0 } }
        for (p1 in planets) {
            for (p2 in planets) {
                if (p1.id < params.numPlanets && p2.id < params.numPlanets) {
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

    private fun calculateDynamicHorizon(params: GameParams): Int {
        var horizon = 90 // Baseline from V3

        // Adjust for partial observability - shorter horizon due to uncertainty
        horizon = (horizon * 0.8).toInt() // 20% reduction to account for uncertainty

        // Planet count adjustment
        when {
            params.numPlanets >= 23 -> horizon = min(horizon, 60) // Even shorter for large maps with hidden info
            params.numPlanets <= 15 -> horizon = max(horizon, 80) // Can look further in small maps
            else -> horizon = max(horizon, 70)
        }

        // Speed adjustment
        when {
            params.transporterSpeed >= 4.1 -> horizon = min(horizon, 60)
            params.transporterSpeed <= 3.0 -> horizon = max(horizon, 90)
            else -> horizon = max(horizon, 70)
        }

        // Growth rate adjustment
        when {
            params.maxGrowthRate >= 0.16 -> horizon = min(horizon, 70)
            params.maxGrowthRate <= 0.08 -> horizon = max(horizon, 80)
        }

        return horizon.coerceIn(40, 100) // Tighter bounds for partial observability
    }

    private fun calculateEffectiveHorizon(observation: Observation): Int {
        val baseHorizon = min(dynamicMaxHorizon, params.maxTicks - observation.gameTick)

        // Reduce horizon based on uncertainty level
        val uncertaintyFactor = calculateUncertaintyFactor(observation)
        return (baseHorizon * uncertaintyFactor).toInt()
    }

    private fun calculateUncertaintyFactor(observation: Observation): Double {
        // Count how many enemy planets we can't see ship counts for
        val unknownEnemyPlanets = observation.observedPlanets.count {
            it.owner == player.opponent() && it.nShips == null
        }

        // More unknown planets = more uncertainty = shorter effective horizon
        return when {
            unknownEnemyPlanets >= 5 -> 0.6  // High uncertainty
            unknownEnemyPlanets >= 3 -> 0.7  // Medium uncertainty
            unknownEnemyPlanets >= 1 -> 0.8  // Low uncertainty
            else -> 0.9                       // Minimal uncertainty
        }
    }

    private fun generateAndEvaluateMoves(
        observation: Observation,
        estimatedState: GameState,
        horizon: Int,
        startTime: Long
    ): Action {
        var bestAction = Action.Companion.doNothing()
        var bestScore = evaluateGameState(observation, estimatedState, Action.Companion.doNothing(), horizon)

        // Only consider planets we own and can see ships for
        val sourcePlanets = observation.observedPlanets.filter {
            it.owner == player &&
            it.transporter == null &&
            it.nShips != null &&
            it.nShips > 0
        }

        // Can target any planet
        val targetPlanets = observation.observedPlanets

        // Ship allocation options
        val shipOptions = { nShips: Double ->
            listOf(nShips / 2.0, nShips, max(1.0, nShips / 4.0))
                .map { max(1.0, floor(it)) }
                .filter { it <= nShips && it >= 1.0 }
                .distinct()
        }

        for (src in sourcePlanets) {
            if (System.currentTimeMillis() - startTime > timeLimitMillis) break

            val srcShips = src.nShips ?: continue // Should never be null due to filter

            for (dst in targetPlanets) {
                if (src.id == dst.id) continue

                for (numShipsToSend in shipOptions(srcShips)) {
                    val action = Action(player, src.id, dst.id, numShipsToSend)

                    // Evaluate with risk assessment
                    val score = evaluateGameState(observation, estimatedState, action, horizon)
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
        observation: Observation,
        estimatedState: GameState,
        action: Action,
        horizon: Int
    ): Double {
        if (horizon <= 0) {
            return evaluatePosition(observation, estimatedState)
        }

        val model = ForwardModel(estimatedState.deepCopy(), params)

        if (action != Action.Companion.DO_NOTHING) {
            model.step(mapOf(player to action, player.opponent() to Action.Companion.doNothing()))
        }

        // Simulate with uncertainty consideration
        for (step in 1 until horizon) {
            if (model.isTerminal()) break
            model.step(mapOf(player to Action.Companion.doNothing(), player.opponent() to Action.Companion.doNothing()))
        }

        return evaluatePosition(observation, model.state)
    }

    private fun evaluatePosition(observation: Observation, gameState: GameState): Double {
        var score = 0.0

        // Known information evaluation
        val myPlanets = observation.observedPlanets.filter { it.owner == player }
        val enemyPlanets = observation.observedPlanets.filter { it.owner == player.opponent() }
        val neutralPlanets = observation.observedPlanets.filter { it.owner == Player.Neutral }

        // Ship count evaluation (only for known ships)
        val myShips = myPlanets.mapNotNull { it.nShips }.sum()
        val knownEnemyShips = enemyPlanets.mapNotNull { it.nShips }.sum()

        // Assume unknown enemy planets have average ships of known ones
        val avgEnemyShips = if (enemyPlanets.any { it.nShips != null }) {
            enemyPlanets.mapNotNull { it.nShips }.average()
        } else {
            // If no known enemy ships, use a conservative estimate
            myShips / myPlanets.size.toDouble()
        }

        // Estimate total enemy ships including unknowns
        val estimatedEnemyShips = knownEnemyShips +
            (enemyPlanets.count { it.nShips == null } * avgEnemyShips)

        // Conservative ship score
        score += (myShips - estimatedEnemyShips) * 0.8 // Reduced weight due to uncertainty

        // Growth rate evaluation (always visible)
        val myGrowth = myPlanets.sumOf { it.growthRate }
        val enemyGrowth = enemyPlanets.sumOf { it.growthRate }
        score += (myGrowth - enemyGrowth) * 15.0 // Higher weight on known information

        // Strategic position evaluation
        for (myPlanet in myPlanets) {
            // Forward position bonus
            val nearestEnemyDist = enemyPlanets.minOfOrNull {
                getPlanetDistance(myPlanet.id, it.id)
            } ?: Double.MAX_VALUE

            if (nearestEnemyDist < 10.0) {
                // Reduce forward position bonus due to uncertainty
                score += (1.0 / nearestEnemyDist) * (myPlanet.nShips ?: 0.0) * 0.005
            }

            // Isolation penalty
            if (myPlanets.size > 1) {
                val nearestFriendDist = myPlanets
                    .filter { it.id != myPlanet.id }
                    .minOfOrNull { getPlanetDistance(myPlanet.id, it.id) } ?: Double.MAX_VALUE

                if (nearestFriendDist > isolationThreshold) {
                    score -= myPlanet.growthRate * 3.0 // Higher isolation penalty
                }
            }
        }

        // Neutral planet opportunity evaluation
        for (neutral in neutralPlanets) {
            val nearestMyPlanet = myPlanets.minOfOrNull {
                getPlanetDistance(neutral.id, it.id)
            } ?: Double.MAX_VALUE

            val nearestEnemyPlanet = enemyPlanets.minOfOrNull {
                getPlanetDistance(neutral.id, it.id)
            } ?: Double.MAX_VALUE

            // Bonus for neutral planets closer to us than enemy
            if (nearestMyPlanet < nearestEnemyPlanet) {
                score += neutral.growthRate * 2.0 * (1.0 / nearestMyPlanet)
            }
        }

        // Planet count bonuses
        score += (myPlanets.size - enemyPlanets.size) * 8.0

        // Terminal state evaluation
        if (enemyPlanets.isEmpty() && myPlanets.isNotEmpty()) {
            score += 10000.0
        }
        if (myPlanets.isEmpty() && enemyPlanets.isNotEmpty()) {
            score -= 10000.0
        }

        return score
    }

    private fun getPlanetDistance(planet1Id: Int, planet2Id: Int): Double {
        return distanceMatrix[planet1Id][planet2Id]
    }
}