package games.planetwars.runners

import competition_entry.GreedyHeuristicAgent
import games.planetwars.agents.DoNothingAgent
import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.evo.SimpleEvoAgent
import games.planetwars.agents.random.BetterRandomAgent
import games.planetwars.agents.random.CarefulRandomAgent
import games.planetwars.agents.random.PureRandomAgent
import games.planetwars.agents.strategic.TeamTitansAgentV1
import games.planetwars.agents.strategic.TeamTitansAgentV3
import games.planetwars.agents.strategic.TeamTitansAgentV1
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import games.planetwars.agents.strategic.TeamTitansAgentV3


fun main() {
    val agents = SamplePlayerLists().getRandomTrio()
    agents.add(GreedyHeuristicAgent())
//    val agents = SamplePlayerLists().getFullList()
//    agents.add(DoNothingAgent())
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

// Helper function to print all game parameters
fun printGameParams(params: GameParams) {
    println("\n----- Game Parameters -----")
    println("numPlanets: ${params.numPlanets}")
    println("initialNeutralRatio: ${params.initialNeutralRatio}")
    println("maxTicks: ${params.maxTicks}")
    println("minInitialShipsPerPlanet: ${params.minInitialShipsPerPlanet}")
    println("maxInitialShipsPerPlanet: ${params.maxInitialShipsPerPlanet}")
    println("minGrowthRate: ${params.minGrowthRate}")
    println("maxGrowthRate: ${params.maxGrowthRate}")
    println("transporterSpeed: ${params.transporterSpeed}")
    println("--------------------------\n")
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
//            PureRandomAgent(),
            TeamTitansAgentV1(),
            TeamTitansAgentV3(),
            SimpleEvoAgent(),
            BetterRandomAgent(),
            CarefulRandomAgent(),
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

data class RoundRobinLeague(
    val agents: List<PlanetWarsAgent>,
    val gamesPerPair: Int = 100,
    val gameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 200),
) {
    fun runPair(agent1: PlanetWarsAgent, agent2: PlanetWarsAgent): Map<Player, Int> {
        println("\nRunning ${gamesPerPair} games: ${agent1.getAgentType()} vs ${agent2.getAgentType()}")
        printGameParams(gameParams)

        val startTime = System.currentTimeMillis()
        val gameRunner = GameRunner(agent1, agent2, gameParams)

        // Run each game individually to log results
        val aggregateResults = mutableMapOf(Player.Player1 to 0, Player.Player2 to 0, Player.Neutral to 0)
        for (gameNumber in 1..gamesPerPair) {
            val gameStartTime = System.currentTimeMillis()
            val finalModel = gameRunner.runGame()
            val winner = finalModel.getLeader()
            val gameElapsedTime = (System.currentTimeMillis() - gameStartTime) / 1000.0

            // Update aggregate results
            aggregateResults[winner] = aggregateResults[winner]!! + 1

            // Log individual game result
            println("Game $gameNumber: Winner=${winner} (${if (winner == Player.Player1) agent1.getAgentType() else if (winner == Player.Player2) agent2.getAgentType() else "Draw"}) [${gameElapsedTime}s]")
        }

        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0
        println("Match summary - P1(${agent1.getAgentType()})=${aggregateResults[Player.Player1]} | P2(${agent2.getAgentType()})=${aggregateResults[Player.Player2]} | Draws=${aggregateResults[Player.Neutral]} (${elapsedTime}s)")
        return aggregateResults
    }

    fun runRoundRobin(): Map<String, LeagueEntry> {
        val t = System.currentTimeMillis()
        println("\n----- Starting Round Robin Tournament -----")
        println("Agents: ${agents.map { it.getAgentType() }}")
        println("Games per matchup: $gamesPerPair")
        printGameParams(gameParams)

        val scores = mutableMapOf<String, LeagueEntry>()
        for (agent in agents) {
            // make a new league entry for each agent in a map indexed by agent type
            scores[agent.getAgentType()] = LeagueEntry(agent.getAgentType())
        }

        // Calculate total number of matchups for progress tracking
        val totalMatchups = agents.size * (agents.size - 1)
        var completedMatchups = 0

        // play each agent against every other agent as Player1 and Player2
        // but not against themselves
        for (i in 0 until agents.size) {
            for (j in 0 until agents.size) {
                if (i == j) {
                    continue
                }
                val agent1 = agents[i]
                val agent2 = agents[j]

                val result = runPair(agent1, agent2)

                // update the league scores for each agent
                val leagueEntry1 = scores[agent1.getAgentType()]!!
                val leagueEntry2 = scores[agent2.getAgentType()]!!
                leagueEntry1.points += result[Player.Player1]!!
                leagueEntry2.points += result[Player.Player2]!!
                leagueEntry1.nGames += gamesPerPair
                leagueEntry2.nGames += gamesPerPair

                // Update progress
                completedMatchups++
                val progressPercent = (completedMatchups * 100.0 / totalMatchups).toInt()
                println("Progress: $completedMatchups/$totalMatchups matchups ($progressPercent%)")

                // Print current standings after each matchup
                if (completedMatchups % (totalMatchups / 4).coerceAtLeast(1) == 0 || completedMatchups == totalMatchups) {
                    println("\n----- Current Standings -----")
                    val currentRankings = scores.toList().sortedByDescending { it.second.points }.toMap()
                    for ((index, entry) in currentRankings.values.withIndex()) {
                        println("${index + 1}. ${entry.agentName}: ${entry.points} points (${entry.nGames} games)")
                    }
                    println("--------------------------\n")
                }
            }
        }

        val totalTime = (System.currentTimeMillis() - t) / 1000.0
        println("\n----- Round Robin Tournament Complete -----")
        println("Total time: $totalTime seconds (${totalTime / 60} minutes)")
        return scores
    }
}