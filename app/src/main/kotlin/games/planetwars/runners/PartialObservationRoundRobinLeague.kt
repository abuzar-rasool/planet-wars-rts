package games.planetwars.runners

import games.planetwars.agents.PartialObservationAgent
import games.planetwars.agents.random.PartialObservationBetterRandomAgent
import games.planetwars.agents.random.PartialObservationPureRandomAgent
import games.planetwars.agents.strategic.TeamTitansPartialAgentV2
import games.planetwars.agents.strategic.TeamTitansPartialAgentV1
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import games.planetwars.core.ForwardModel

fun main() {
    val agents = SamplePartialObservationPlayerLists().getFullList()
    println("Starting Partial Observation Round Robin League with ${agents.size} agents")
    val gameParams = GameParams(numPlanets = 20, maxTicks = 200)
    printGameParams(gameParams)

    val league = PartialObservationRoundRobinLeague(agents, gamesPerPair = 100, gameParams = gameParams)
    val results = league.runRoundRobin()
    // use the League utils to print the results
    println(results)
    val writer = LeagueWriter(
        "results/sample/",
        "league-partial.md"
    )
    val leagueResult = LeagueResult(results.values.toList())
    val markdownContent = writer.generateMarkdownTable(leagueResult)
    writer.saveMarkdownToFile(markdownContent)
    println("Markdown results saved to file")

    // print sorted results directly to console
    println("\nFinal Rankings:")
    val sortedResults = results.toList().sortedByDescending { it.second.points }.toMap()
    for ((index, entry) in sortedResults.values.withIndex()) {
        println("${index + 1}. ${entry.agentName} : ${entry.points} points : ${entry.nGames} games")
    }
}

class SamplePartialObservationPlayerLists {
    fun getFullList(): MutableList<PartialObservationAgent> {
        return mutableListOf(
            PartialObservationPureRandomAgent(),
            PartialObservationBetterRandomAgent(),
            TeamTitansPartialAgentV2(),
            TeamTitansPartialAgentV1(),
        )
    }

    // Add more agent lists as needed for different testing scenarios
}

class PartialObservationRoundRobinLeague(
    agents: List<PartialObservationAgent>,
    gamesPerPair: Int = 100,
    gameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 200),
) : BaseRoundRobinLeague<PartialObservationAgent>(agents, gamesPerPair, gameParams) {

    override fun createGameRunner(agent1: PartialObservationAgent, agent2: PartialObservationAgent): Any {
        return PartialObservationGameRunner(agent1, agent2, gameParams)
    }

    override fun runGame(gameRunner: Any): ForwardModel {
        return (gameRunner as PartialObservationGameRunner).runGame()
    }

    override fun getAgentType(agent: PartialObservationAgent): String {
        return agent.getAgentType()
    }

    override fun prepareAgent(agent: PartialObservationAgent, player: Player) {
        agent.prepareToPlayAs(player, gameParams, null)
    }
} 