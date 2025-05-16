package games.planetwars.view

import games.planetwars.agents.random.BetterRandomAgent
import games.planetwars.agents.random.CarefulRandomAgent
import games.planetwars.agents.random.PureRandomAgent
import games.planetwars.core.GameParams
import games.planetwars.runners.GameRunner
import games.planetwars.core.GameStateFactory
import xkg.jvm.AppLauncher
import games.planetwars.agents.rl.RLAgent
import games.planetwars.runners.SamplePlayerLists

import games.planetwars.agents.evo.SimpleEvoAgent
import games.planetwars.agents.DoNothingAgent


fun main() {
    val gameParams = GameParams(numPlanets = 20, maxTicks = 1000)
    val gameState = GameStateFactory(gameParams).createGame()
    val agent2 = SimpleEvoAgent(
        useShiftBuffer = true,
        nEvals = 30,
        sequenceLength = 400,
        opponentModel = DoNothingAgent(),
        probMutation = 0.8,
    )
//    val agent1 = PureRandomAgent()
    // val agent1 = CarefulRandomAgent()
//    val agent1 = games.planetwars.agents.DoNothingAgent()
//    val agent1 = games.planetwars.agents.BetterRandomAgent()

    val agent1 = SamplePlayerLists().getTrainedAgent()
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
