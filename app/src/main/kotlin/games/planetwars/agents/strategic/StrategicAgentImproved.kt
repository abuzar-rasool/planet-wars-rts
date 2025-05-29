package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.*
import kotlin.math.*

/**
 * Improved strategic agent:
 * - Computes horizon using actual travel time based on dynamic GameParams
 * - Uses an opening-phase beam search (branching factor) to limit candidates
 * - Caches evaluated states to avoid redundant forward simulations
 * - Respects dynamic game parameters (e.g. maxTicks, transporterSpeed)
 */
class StrategicAgentImproved(
    val openingSearchDepth: Int = 10,
    val openingBranchingFactor: Int = 10,
    val maxHorizon: Int = 50,
    val timeLimitMillis: Long = 100L
) : PlanetWarsPlayer() {

    private var currentTurn = 0
    private var bestMoveSoFar: Pair<Action, Double>? = null
    private val futureCache = mutableMapOf<String, Double>()

    override fun getAgentType(): String =
        "StrategicAgentImproved-D${openingSearchDepth}-B${openingBranchingFactor}-H${maxHorizon}"

    override fun getAction(gameState: GameState): Action {
        futureCache.clear()
        bestMoveSoFar = null
        val startTime = System.currentTimeMillis()

        // Dynamic horizon: bounded by both maxHorizon and remaining game ticks
        val horizon = computeHorizon(gameState)
        val myPlanetCount = gameState.planets.count { it.owner == player }

        val action = if (myPlanetCount < 3) {
            // Opening phase: beam search with branching factor
            generateAndEvaluateMovesOpening(gameState, horizon, startTime)
        } else {
            // Mid/late game: full move evaluation with caching
            generateAndEvaluateMoves(gameState, horizon, startTime)
        }

        currentTurn++
        return action
    }

    private fun computeHorizon(gameState: GameState): Int {
        val remainingTicks = params.maxTicks - gameState.gameTick
        var horizon = min(maxHorizon, remainingTicks)
        val safetyMargin = computeSafetyMargin(gameState)

        val breakEvenTurns = gameState.planets
            .filter { it.owner == Player.Neutral && it.growthRate > 0.0 }
            .mapNotNull { planet ->
                // Turns to recoup initial ships via growth
                val turnsToBreakEven = ceil(planet.nShips / planet.growthRate).toInt()
                // Actual travel time from nearest owned planet
                val minDist = gameState.planets
                    .filter { it.owner == player }
                    .minOfOrNull { distanceBetween(it, planet) } ?: return@mapNotNull null
                val travelTurns = ceil(minDist / params.transporterSpeed).toInt()
                val total = turnsToBreakEven + travelTurns
                if (isSafeToInvest(safetyMargin, planet.nShips.toInt(), travelTurns, planet.growthRate.toInt())) total else null
            }
            .sorted()

        if (breakEvenTurns.isNotEmpty()) {
            val target = if (breakEvenTurns.size >= 3) breakEvenTurns[2] else breakEvenTurns.last()
            horizon = min(horizon, max(30, target))
        }
        return horizon
    }

    private fun computeSafetyMargin(gameState: GameState): IntArray {
        val myShips = gameState.planets
            .filter { it.owner == player }
            .sumOf { it.nShips }.toInt()
        return IntArray(maxHorizon) { myShips / 2 }
    }

    private fun isSafeToInvest(
        margins: IntArray,
        nShips: Int,
        travelTurns: Int,
        growth: Int
    ): Boolean {
        val turnsToBreakEven = ceil(nShips.toDouble() / growth).toInt()
        val limit = min(turnsToBreakEven + 2 * travelTurns, margins.size)
        for (i in 0 until limit) {
            val required = nShips - (growth * max(0, i - travelTurns))
            if (margins[i] < required) return false
        }
        return true
    }

    private fun distanceBetween(p1: Planet, p2: Planet): Double {
        val dx = p1.position.x - p2.position.x
        val dy = p1.position.y - p2.position.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun generateAndEvaluateMovesOpening(
        gameState: GameState,
        horizon: Int,
        startTime: Long
    ): Action {
        // Generate all plausible actions
        val sourcePlanets = gameState.planets.filter { it.owner == player && it.nShips > 0 }
        val targetPlanets = gameState.planets.filter { it.owner != player }
        val allActions = mutableListOf<Action>()
        allActions.add(Action.doNothing())
        for (src in sourcePlanets) for (dst in targetPlanets) {
            if (src.id == dst.id) continue
            val options = listOf(src.nShips / 2.0, src.nShips, max(1.0, src.nShips / 4.0))
            for (num in options) allActions.add(Action(player, src.id, dst.id, num))
        }

        // Quick score with horizon=1 to pick top candidates
        val topCandidates = allActions
            .map { it to evaluateGameState(gameState, it, 1) }
            .sortedByDescending { it.second }
            .take(openingBranchingFactor)

        var bestAction = Action.doNothing()
        var bestScore = Double.NEGATIVE_INFINITY
        for ((action, _) in topCandidates) {
            if (System.currentTimeMillis() - startTime > timeLimitMillis) break
            val score = evaluateGameState(gameState, action, horizon)
            if (score > bestScore) {
                bestScore = score
                bestAction = action
                bestMoveSoFar = action to score
            }
        }
        return bestMoveSoFar?.first ?: bestAction
    }

    private fun generateAndEvaluateMoves(
        gameState: GameState,
        horizon: Int,
        startTime: Long
    ): Action {
        var bestAction = Action.doNothing()
        var bestScore = evaluateGameState(gameState, Action.doNothing(), horizon)
        bestMoveSoFar = bestAction to bestScore

        val sourcePlanets = gameState.planets.filter { it.owner == player && it.nShips > 0 }
        val targetPlanets = gameState.planets.filter { it.owner != player }
        for (src in sourcePlanets) for (dst in targetPlanets) {
            if (System.currentTimeMillis() - startTime > timeLimitMillis) return bestMoveSoFar!!.first
            if (src.id == dst.id) continue
            val options = listOf(src.nShips / 2.0, src.nShips, max(1.0, src.nShips / 4.0))
            for (num in options) {
                val action = Action(player, src.id, dst.id, num)
                val score = evaluateGameState(gameState, action, horizon)
                if (score > bestScore) {
                    bestScore = score
                    bestAction = action
                    bestMoveSoFar = action to score
                }
            }
        }
        return bestMoveSoFar!!.first
    }

    private fun evaluateGameState(
        gameState: GameState,
        action: Action,
        horizon: Int
    ): Double {
        val key = "${gameState.gameTick}-${action.sourcePlanetId}-${action.destinationPlanetId}-${action.numShips.toInt()}-$horizon"
        futureCache[key]?.let { return it }

        val model = ForwardModel(gameState.deepCopy(), params)
        if (action != Action.DO_NOTHING) {
            model.step(mapOf(player to action, player.opponent() to Action.doNothing()))
        }
        for (step in 1 until horizon) {
            if (model.isTerminal()) break
            val opp = computeSimpleOpponentAction(model.state)
            model.step(mapOf(player to Action.doNothing(), player.opponent() to opp))
        }
        val score = evaluatePosition(model.state)
        futureCache[key] = score
        return score
    }

    private fun computeSimpleOpponentAction(gameState: GameState): Action {
        val opponent = player.opponent()
        val sources = gameState.planets.filter { it.owner == opponent && it.nShips > 0 }
        if (sources.isEmpty()) return Action.doNothing()
        val targets = gameState.planets.filter { it.owner != opponent }
        if (targets.isEmpty()) return Action.doNothing()
        val src = sources.maxByOrNull { it.nShips } ?: return Action.doNothing()
        val dst = targets.minByOrNull { it.nShips } ?: return Action.doNothing()
        return Action(opponent, src.id, dst.id, src.nShips / 2.0)
    }

    private fun evaluatePosition(gameState: GameState): Double {
        var score = 0.0
        val myShips = gameState.planets.filter { it.owner == player }.sumOf { it.nShips }
        val oppShips = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.nShips }
        score += (myShips - oppShips)
        val myGrowth = gameState.planets.filter { it.owner == player }.sumOf { it.growthRate }
        val oppGrowth = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.growthRate }
        score += (myGrowth - oppGrowth) * 10.0
        for (planet in gameState.planets.filter { it.owner == player }) {
            val nearest = gameState.planets.filter { it.owner == player.opponent() }
                .minOfOrNull { distanceBetween(planet, it) } ?: Double.MAX_VALUE
            if (nearest < 10.0) score += (1.0 / nearest) * planet.nShips * 0.01
        }
        return score
    }
} 