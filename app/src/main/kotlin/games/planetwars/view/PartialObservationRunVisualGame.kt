package games.planetwars.view

import games.planetwars.agents.random.PartialObservationBetterRandomAgent
import games.planetwars.agents.random.PartialObservationPureRandomAgent
import games.planetwars.core.GameParams
import games.planetwars.runners.PartialObservationGameRunner
import games.planetwars.core.GameStateFactory
import xkg.jvm.AppLauncher

fun main() {
    val gameParams = GameParams(numPlanets = 20, maxTicks = 500)
    val gameState = GameStateFactory(gameParams).createGame()

    // Use partial observation agents instead of full observation agents
    val agent1 = PartialObservationPureRandomAgent()
    val agent2 = PartialObservationBetterRandomAgent()
    
    // Use PartialObservationGameRunner instead of GameRunner
    val gameRunner = PartialObservationGameRunner(agent1, agent2, gameParams)

    val title = "${agent1.getAgentType()} : Planet Wars (Partial) : ${agent2.getAgentType()}"
    AppLauncher(
        preferredWidth = gameParams.width,
        preferredHeight = gameParams.height,
        app = PartialObservationGameView(params = gameParams, gameState = gameState, gameRunner = gameRunner),
        title = title,
        frameRate = 50.0,
    ).launch()
}
