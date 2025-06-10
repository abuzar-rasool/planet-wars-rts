package games.planetwars.runners

import games.planetwars.core.GameParams
import games.planetwars.core.Player
import games.planetwars.core.ForwardModel

abstract class BaseRoundRobinLeague<T>(
    protected val agents: List<T>,
    protected val gamesPerPair: Int = 100,
    protected val gameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 200),
) {
    protected abstract fun createGameRunner(agent1: T, agent2: T): Any
    protected abstract fun runGame(gameRunner: Any): ForwardModel
    protected abstract fun getAgentType(agent: T): String
    protected abstract fun prepareAgent(agent: T, player: Player)

    protected fun runPair(agent1: T, agent2: T): Map<Player, Int> {
        println("\nRunning ${gamesPerPair} games: ${getAgentType(agent1)} vs ${getAgentType(agent2)}")
        printGameParams(gameParams)

        val startTime = System.currentTimeMillis()
        val gameRunner = createGameRunner(agent1, agent2)

        // Run each game individually to log results
        val aggregateResults = mutableMapOf(Player.Player1 to 0, Player.Player2 to 0, Player.Neutral to 0)
        for (gameNumber in 1..gamesPerPair) {
            val gameStartTime = System.currentTimeMillis()
            val finalModel = runGame(gameRunner)
            val winner = finalModel.getLeader()
            val gameElapsedTime = (System.currentTimeMillis() - gameStartTime) / 1000.0

            // Update aggregate results
            aggregateResults[winner] = aggregateResults[winner]!! + 1

            // Log individual game result
            println("Game $gameNumber: Winner=${winner} (${if (winner == Player.Player1) getAgentType(agent1) else if (winner == Player.Player2) getAgentType(agent2) else "Draw"}) [${gameElapsedTime}s]")
        }

        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0
        println("Match summary - P1(${getAgentType(agent1)})=${aggregateResults[Player.Player1]} | P2(${getAgentType(agent2)})=${aggregateResults[Player.Player2]} | Draws=${aggregateResults[Player.Neutral]} (${elapsedTime}s)")
        return aggregateResults
    }

    fun runRoundRobin(): Map<String, LeagueEntry> {
        val t = System.currentTimeMillis()
        println("\n----- Starting Round Robin Tournament -----")
        println("Agents: ${agents.map { getAgentType(it) }}")
        println("Games per matchup: $gamesPerPair")
        printGameParams(gameParams)

        val scores = mutableMapOf<String, LeagueEntry>()
        for (agent in agents) {
            scores[getAgentType(agent)] = LeagueEntry(getAgentType(agent))
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

                // Prepare agents for this matchup
                prepareAgent(agent1, Player.Player1)
                prepareAgent(agent2, Player.Player2)

                val result = runPair(agent1, agent2)

                // update the league scores for each agent
                val leagueEntry1 = scores[getAgentType(agent1)]!!
                val leagueEntry2 = scores[getAgentType(agent2)]!!
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