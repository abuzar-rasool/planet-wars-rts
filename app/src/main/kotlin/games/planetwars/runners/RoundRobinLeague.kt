package games.planetwars.runners

import competition_entry.GreedyHeuristicAgent
import games.planetwars.agents.DoNothingAgent
import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.evo.SimpleEvoAgent
import games.planetwars.agents.random.BetterRandomAgent
import games.planetwars.agents.random.CarefulRandomAgent
import games.planetwars.agents.random.PureRandomAgent
import games.planetwars.agents.random.SmarterAgent
import games.planetwars.agents.strategic.TeamTitansAgentV1
import games.planetwars.agents.strategic.TeamTitansAgentV3
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import games.planetwars.core.ForwardModel

fun main() {
    val agents = SamplePlayerLists().getFullList()
    println("Starting Round Robin League with ${agents.size} agents")
    val gameParams = GameParams(numPlanets = 20, maxTicks = 200)
    printGameParams(gameParams)

    val league = RoundRobinLeague(agents, gamesPerPair = 100, gameParams = gameParams)
    val results = league.runRoundRobin()
    // use the League utils to print the results
    println(results)
    val writer = LeagueWriter()
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

class SamplePlayerLists {
    fun getRandomTrio(): MutableList<PlanetWarsAgent> {
        return mutableListOf(
            PureRandomAgent(),
            BetterRandomAgent(),
            CarefulRandomAgent(),
        )
    }

    fun getFullList(): MutableList<PlanetWarsAgent> {
        return mutableListOf(
            TeamTitansAgentV1(),
            GreedyHeuristicAgent(),
            TeamTitansAgentV3(),
            SmarterAgent(),
            BetterRandomAgent(),
//            CarefulRandomAgent(),
            SimpleEvoAgent(
                useShiftBuffer = true,
                nEvals = 50,
                sequenceLength = 400,
                opponentModel = DoNothingAgent(),
                probMutation = 0.8,
            ),
        )
    }
}

class RoundRobinLeague(
    agents: List<PlanetWarsAgent>,
    gamesPerPair: Int = 100,
    gameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 200),
) : BaseRoundRobinLeague<PlanetWarsAgent>(agents, gamesPerPair, gameParams) {

    override fun createGameRunner(agent1: PlanetWarsAgent, agent2: PlanetWarsAgent): Any {
        return GameRunner(agent1, agent2, gameParams)
    }

    override fun runGame(gameRunner: Any): ForwardModel {
        return (gameRunner as GameRunner).runGame()
    }

    override fun getAgentType(agent: PlanetWarsAgent): String {
        return agent.getAgentType()
    }

    override fun prepareAgent(agent: PlanetWarsAgent, player: Player) {
        agent.prepareToPlayAs(player, gameParams, null)
    }
}