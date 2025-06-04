package games.planetwars.view

import games.planetwars.agents.evo.SimpleEvoAgent
import games.planetwars.agents.strategic.TeamTitansAgentV3
import games.planetwars.core.GameParams
import games.planetwars.runners.GameRunner
import games.planetwars.core.GameStateFactory
import xkg.jvm.AppLauncher

fun main() {
    val gameParams = GameParams(numPlanets = 40, maxTicks = 500)
    val gameState = GameStateFactory(gameParams).createGame()
    val agent2 = SimpleEvoAgent()
//    val agent1 = PureRandomAgent()
    val agent1 = TeamTitansAgentV3()
//    val agent1 = games.planetwars.agents.DoNothingAgent()
//    val agent1 = games.planetwars.agents.BetterRandomAgent()
    val gameRunner = GameRunner(agent1, agent2, gameParams)

    val title = "${agent1.getAgentType()} : Planet Wars : ${agent2.getAgentType()}"
    AppLauncher(
        preferredWidth = gameParams.width,
        preferredHeight = gameParams.height,
        app = GameView(params = gameParams, gameState = gameState, gameRunner = gameRunner),
        title = title,
        frameRate = 50.0,
    ).launch()
}
