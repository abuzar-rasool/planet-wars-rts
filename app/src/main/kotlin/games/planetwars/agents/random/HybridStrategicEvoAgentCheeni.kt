package games.planetwars.agents.random

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.*
import kotlin.random.Random

class HybridStrategicEvoAgentCheeni(
    var flipAtLeastOneValue: Boolean = true,
    var probMutation: Double = 0.5,
    var sequenceLength: Int = 20,
    var nEvals: Int = 10,
    var useShiftBuffer: Boolean = true,
    var epsilon: Double = 1e-6,
    var timeLimitMillis: Long = 20,
    var opponentModel: PlanetWarsAgent = games.planetwars.agents.DoNothingAgent(),
) : PlanetWarsPlayer() {
    private val random = Random
    private var bestSolution: ScoredSolution? = null

    data class ScoredSolution(val score: Double, val solution: FloatArray)

    override fun getAction(gameState: GameState): Action {
        if (bestSolution == null || !useShiftBuffer) {
            val solution = randomPoint()
            bestSolution = ScoredSolution(evalSeq(gameState, solution), solution)
        } else {
            val nextSeq = shiftLeftAndRandomAppend(bestSolution!!.solution, 2)
            bestSolution = ScoredSolution(evalSeq(gameState, nextSeq), nextSeq)
        }

        for (i in 0 until nEvals) {
            val mut = mutate(bestSolution!!.solution, probMutation)
            val mutScore = evalSeq(gameState, mut)
            if (mutScore >= bestSolution!!.score) {
                bestSolution = ScoredSolution(mutScore, mut)
            }
        }
        val action = getStrategicAction(gameState, bestSolution!!.solution[0], bestSolution!!.solution[1])
        return action
    }

    private fun mutate(v: FloatArray, mutProb: Double): FloatArray {
        val n = v.size
        val x = FloatArray(n)
        var ix = random.nextInt(n)
        if (!flipAtLeastOneValue) ix = -1
        for (i in 0 until n) {
            if (i == ix || random.nextDouble() < mutProb) {
                x[i] = random.nextFloat()
            } else {
                x[i] = v[i]
            }
        }
        return x
    }

    private fun randomPoint(): FloatArray {
        val p = FloatArray(sequenceLength)
        for (i in p.indices) {
            p[i] = random.nextFloat()
        }
        return p
    }

    private fun shiftLeftAndRandomAppend(v: FloatArray, shiftBy: Int): FloatArray {
        val p = FloatArray(v.size)
        for (i in 0 until p.size - shiftBy) {
            p[i] = v[i + shiftBy]
        }
        p[p.size - 1] = random.nextFloat()
        p[p.size - 2] = random.nextFloat()
        return p
    }

    // Use StrategicAgent's evaluation as the fitness function
    private fun evalSeq(state: GameState, seq: FloatArray): Double {
        var ix = 0
        val forwardModel = ForwardModel(state.deepCopy(), params)
        var score = 0.0
        while (ix < seq.size && !forwardModel.isTerminal()) {
            val from = seq[ix]
            val to = seq[ix + 1]
            val myAction = getStrategicAction(forwardModel.state, from, to)
            val opponentAction = opponentModel.getAction(forwardModel.state)
            val actions = mapOf(player to myAction, player.opponent() to opponentAction)
            forwardModel.step(actions)
            // Use StrategicAgent's evaluation of the resulting state
            score = evaluateGameState(forwardModel.state)
            ix += 2
        }
        return score
    }

    // Strategic action selection using the same logic as StrategicAgent, but parameterized by floats
    private fun getStrategicAction(gameState: GameState, from: Float, to: Float): Action {
        val myPlanets = gameState.planets.filter { it.owner == player && it.transporter == null }
        if (myPlanets.isEmpty()) return Action.doNothing()
        val otherPlanets = gameState.planets.filter { it.owner != player }
        if (otherPlanets.isEmpty()) return Action.doNothing()
        val source = myPlanets[(from * myPlanets.size).toInt().coerceIn(0, myPlanets.size - 1)]
        val target = otherPlanets[(to * otherPlanets.size).toInt().coerceIn(0, otherPlanets.size - 1)]
        val nShips = if (target.owner == player.opponent()) source.nShips * 0.8 else source.nShips * 0.7
        return Action(player, source.id, target.id, nShips)
    }

    // StrategicAgent's evaluation of a game state
    private fun evaluateGameState(gameState: GameState): Double {
        // Sum up the value of all owned planets, minus the value of opponent's planets
        val myValue = gameState.planets.filter { it.owner == player }.sumOf { evaluatePlanet(it) }
        val oppValue = gameState.planets.filter { it.owner == player.opponent() }.sumOf { evaluatePlanet(it) }
        val fleetScore = evaluateFleetDistribution(gameState)
        return myValue - oppValue + fleetScore
    }

    private fun evaluatePlanet(planet: Planet): Double {
        val baseValue = planet.growthRate * 150 + planet.nShips * 10 + planet.radius * 5
        val strategicValue = when (planet.owner) {
            player -> 1.0
            player.opponent() -> 2.0
            else -> 1.5
        }
        return baseValue * strategicValue
    }

    private fun evaluateFleetDistribution(gameState: GameState): Double {
        val myPlanets = gameState.planets.filter { it.owner == player }
        if (myPlanets.isEmpty()) return 0.0
        val totalShips = myPlanets.sumOf { it.nShips }
        val avgShipsPerPlanet = totalShips / myPlanets.size
        val distributionScore = myPlanets.sumOf { planet ->
            when {
                planet.nShips < avgShipsPerPlanet * 0.3 -> -20.0
                planet.nShips < avgShipsPerPlanet * 0.5 -> -10.0
                planet.nShips > avgShipsPerPlanet * 2.0 -> -5.0
                else -> 0.0
            }
        }
        val planetCountBonus = myPlanets.size * 10.0
        return distributionScore + planetCountBonus
    }

    override fun getAgentType(): String = "Hybrid Strategic Evo Agent Cheeni"
} 