package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PartialObservationAgent
import games.planetwars.agents.PartialObservationPlayer
import games.planetwars.core.*
import kotlin.math.*

class TeamTitansPartialAgentV2(
    val timeLimitMillis: Long = 90L
) : PartialObservationPlayer() {

    private var dynamicMaxHorizon: Int = 100
    private lateinit var distanceMatrix: Array<Array<Double>>
    private var distancesInitialized = false
    private var isolationThreshold: Double = 0.0

    override fun prepareToPlayAs(player: Player, params: GameParams, opponent: Player?): PartialObservationAgent {
        super.prepareToPlayAs(player, params, opponent)
        this.dynamicMaxHorizon = calculateDynamicHorizon(this.params)
        this.distancesInitialized = false
        this.isolationThreshold = this.params.width / 4.0
        return this
    }

    override fun getAgentType(): String = "TeamTitansPartialAgentV2"

    private fun calculateDynamicHorizon(params: GameParams): Int {
        var horizon = 90
        when {
            params.numPlanets >= 23 -> horizon = min(horizon, 70)
            params.numPlanets <= 15 -> horizon = max(horizon, 100)
            else -> horizon = max(horizon, 90)
        }
        when {
            params.transporterSpeed >= 4.1 -> horizon = min(horizon, 70)
            params.transporterSpeed <= 3.0 -> horizon = max(horizon, 110)
            else -> horizon = max(horizon, 90)
        }
        when {
            params.maxGrowthRate >= 0.16 -> horizon = min(horizon, 90)
            params.maxGrowthRate <= 0.08 -> horizon = max(horizon, 100)
        }
        if (params.initialNeutralRatio <= 0.28 || params.initialNeutralRatio >= 0.33) {
            horizon = max(horizon, 95)
        } else {
            horizon = min(horizon, 85)
        }
        when {
            params.maxTicks <= 800 -> horizon = min(horizon, 70)
            params.maxTicks >= 1201 -> horizon = max(horizon, 110)
            else -> horizon = max(horizon, 90)
        }
        return horizon.coerceIn(50, 130)
    }

    override fun getAction(observation: Observation): Action {
        val startTime = System.currentTimeMillis()

        // Convert observation to a plausible game state for simulation.
        val gameState = observationToGameState(observation, player, params)

        if (!distancesInitialized) {
            val planets = gameState.planets
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
        val effectiveHorizon = min(dynamicMaxHorizon, params.maxTicks - gameState.gameTick)
        return generateAndEvaluateMoves(gameState, effectiveHorizon, startTime)
    }

    private fun generateAndEvaluateMoves(
        gameState: GameState,
        horizon: Int,
        startTime: Long
    ): Action {
        var bestAction = Action.doNothing()
        var bestScore = evaluateGameState(gameState, Action.doNothing(), horizon)

        val sourcePlanets = gameState.planets.filter { it.owner == player && it.nShips > 0 && it.transporter == null }
        val targetPlanets = gameState.planets

        val shipOptions = { nShips: Double ->
            listOf(nShips / 2.0, nShips, max(1.0, nShips / 4.0))
                .map { max(1.0, floor(it)) }
                .filter { it <= nShips && it >= 1.0 }
                .distinct()
        }

        for (src in sourcePlanets) {
            for (dst in targetPlanets) {
                if (System.currentTimeMillis() - startTime > timeLimitMillis) return bestAction
                if (src.id == dst.id) continue

                for (numShipsToSend in shipOptions(src.nShips)) {
                    if (numShipsToSend < 1.0) continue

                    val action = Action(player, src.id, dst.id, numShipsToSend)
                    if (src.nShips < numShipsToSend) continue

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
        for (step in 1 until horizon) {
            if (model.isTerminal()) break
            model.step(mapOf(player to Action.doNothing(), player.opponent() to Action.doNothing()))
        }
        return evaluatePosition(model.state)
    }

    private fun getPlanetDistance(planet1Id: Int, planet2Id: Int): Double {
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
        score += (myGrowth - oppGrowth) * 10.0

        val evalLookahead = 20
        val myFutureShips = myPlanets.sumOf { it.nShips + it.growthRate * evalLookahead }
        val oppFutureShips = oppPlanets.sumOf { it.nShips + it.growthRate * evalLookahead }
        score += (myFutureShips - oppFutureShips) * 0.5

        for (myPlanet in myPlanets) {
            val nearestEnemyDist = oppPlanets.minOfOrNull { getPlanetDistance(myPlanet.id, it.id) } ?: Double.MAX_VALUE
            if (nearestEnemyDist < 10.0 && nearestEnemyDist > 0) {
                score += (1.0 / nearestEnemyDist) * myPlanet.nShips * 0.01
            }
            if (myPlanets.size > 1) {
                val nearestFriendDist = myPlanets.filter { it.id != myPlanet.id }
                    .minOfOrNull { getPlanetDistance(myPlanet.id, it.id) } ?: Double.MAX_VALUE
                if (nearestFriendDist > isolationThreshold) {
                    score -= myPlanet.growthRate * 2.0
                }
            }
        }
        score += (myPlanets.size - oppPlanets.size) * 5.0

        if (oppPlanets.isEmpty() && myPlanets.isNotEmpty()) {
            score += 10000.0
        }
        if (myPlanets.isEmpty() && oppPlanets.isNotEmpty()) {
            score -= 10000.0
        }
        return score
    }

    // Helper to convert Observation to a plausible GameState using only visible info
    private fun observationToGameState(observation: Observation, player: Player, params: GameParams): GameState {
        val planets = observation.observedPlanets.mapIndexed { idx: Int, obs: PlanetObservation ->
            Planet(
                owner = obs.owner,
                nShips = obs.nShips ?: 0.0, // Hidden ships become 0
                position = obs.position,
                growthRate = obs.growthRate,
                radius = obs.radius,
                transporter = null, // Could reconstruct visible transporters if needed
                id = idx
            )
        }
        return GameState(planets, observation.gameTick)
    }
}