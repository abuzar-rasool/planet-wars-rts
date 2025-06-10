package games.planetwars.agents.random

// package games.planetwars.agents.smart

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.GameParams
import games.planetwars.core.GameState
import games.planetwars.core.GameStateFactory
import games.planetwars.core.Planet
import games.planetwars.runners.GameRunner

// private val SmarterAgent.source: Any

class SmarterAgent : PlanetWarsPlayer() {

    override fun getAction(gameState: GameState): Action {
        // Filter for source planets owned by the agent
        val ownedPlanets = gameState.planets.filter { it.owner == player && it.nShips > 0 }

        if (ownedPlanets.isEmpty()) {
            // No action possible, fallback to some default behavior
            // For example, do nothing or select any planet
            val fallbackPlanet = gameState.planets.firstOrNull { it.nShips > 0 } // or any suitable fallback
            if (fallbackPlanet != null) {
                // Send half ships from fallback planet if possible
                return Action(player, fallbackPlanet.id, -1, fallbackPlanet.nShips / 2)
            } else {
                // No planets to act from, do nothing or return a default Action
                return Action(player, -1, -1, 0.toDouble() )
            }
        }

        // Select the strongest planet as the source (most ships)
        val source = ownedPlanets.maxByOrNull { it.nShips }!!

        // Filter target planets not owned by us
        val targetPlanets = gameState.planets.filter { it.owner != player && it.id != source.id }

        if (targetPlanets.isEmpty()) {
            // No valid target, fallback
            return Action(player, -1, -1, source.nShips/2) // added source.nShips/2
        }

        // Choose the weakest target (least ships)
        val target = targetPlanets.minByOrNull { it.nShips }!!

        val numShipsToSend = source.nShips / 2

        return Action(player, source.id, target.id, numShipsToSend)
    }

    override fun getAgentType(): String {
        return "Smarter Agent"
    }
}

// Test run
fun main() {
    val agent = SmarterAgent()
    val gameState = GameStateFactory(GameParams()).createGame()
    val action = agent.getAction(gameState)
    println("Agent Type: ${agent.getAgentType()}")
    println("Action: $action")

    val agent1 = SmarterAgent()
    val agent2 = CarefulRandomAgent()
    val gameParams = GameParams(numPlanets = 20, maxTicks = 1000) // Provide actual parameters here
    val gameRunner = GameRunner(agent1, agent2, gameParams)
    val finalModel = gameRunner.runGame()
    println("Game over! Winner: ${finalModel.getLeader()}")
    println(finalModel.statusString())
}