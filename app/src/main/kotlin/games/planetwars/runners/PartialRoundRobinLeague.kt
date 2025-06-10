package games.planetwars.runners

import games.planetwars.agents.PartialObservationAgent
import games.planetwars.agents.random.PartialObservationPureRandomAgent
import games.planetwars.agents.random.PartialObservationBetterRandomAgent
import games.planetwars.agents.strategic.TeamTitansPartialAgentV2
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Partial Observation Round Robin League
 * Runs a round robin tournament between all partial observation agents.
 */
data class PartialRoundRobinLeague(
    val agentFactories: List<() -> PartialObservationAgent>,
    val gamesPerPair: Int = 10,
    val gameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 400),
    val parallelism: Int = Runtime.getRuntime().availableProcessors()
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(parallelism)

    suspend fun runRoundRobin(): Map<String, LeagueEntry> = coroutineScope {
        val agentTypes = agentFactories.map { it().getAgentType() }
        println("\n----- Starting Partial Observation Round Robin Tournament -----")
        println("Agents: $agentTypes")
        println("Games per matchup: $gamesPerPair")
        println("Parallelism: $parallelism threads")
        println("Game parameters: $gameParams")

        val scores = ConcurrentHashMap<String, LeagueEntry>()
        for (agentType in agentTypes) {
            scores[agentType] = LeagueEntry(agentType)
        }

        val totalMatchups = agentFactories.size * (agentFactories.size - 1)
        val completedMatchups = AtomicInteger(0)

        val matchups = mutableListOf<Pair<Int, Int>>()
        for (i in agentFactories.indices) {
            for (j in agentFactories.indices) {
                if (i != j) {
                    matchups.add(Pair(i, j))
                }
            }
        }

        matchups.chunked(parallelism).forEach { chunk ->
            val chunkJobs = chunk.map { (i, j) ->
                async(dispatcher) {
                    val agentFactory1 = agentFactories[i]
                    val agentFactory2 = agentFactories[j]
                    val agent1Type = agentFactory1().getAgentType()
                    val agent2Type = agentFactory2().getAgentType()
                    val results = runPair(agentFactory1, agentFactory2)
                    scores[agent1Type]!!.points += results[Player.Player1]!!
                    scores[agent2Type]!!.points += results[Player.Player2]!!
                    scores[agent1Type]!!.nGames += gamesPerPair
                    scores[agent2Type]!!.nGames += gamesPerPair
                    val current = completedMatchups.incrementAndGet()
                    val progressPercent = (current * 100.0 / totalMatchups).toInt()
                    println("Progress: $current/$totalMatchups matchups ($progressPercent%)")
                }
            }
            runBlocking { chunkJobs.awaitAll() }
        }
        scores.toMap()
    }

    private fun runPair(agentFactory1: () -> PartialObservationAgent, agentFactory2: () -> PartialObservationAgent): Map<Player, Int> {
        val aggregateResults = mutableMapOf(Player.Player1 to 0, Player.Player2 to 0, Player.Neutral to 0)
        repeat(gamesPerPair) {
            val agent1 = agentFactory1()
            val agent2 = agentFactory2()
            val runner = PartialObservationGameRunner(agent1, agent2, gameParams)
            val finalModel = runner.runGame()
            val winner = finalModel.getLeader()
            aggregateResults[winner] = aggregateResults[winner]!! + 1
        }
        return aggregateResults
    }
}

// Use LeagueEntry from LeagueUtils if available, otherwise define here
// data class LeagueEntry(
//     val agentName: String,
//     var points: Int = 0,
//     var nGames: Int = 0
// )

// NOTE: If you have multiple main functions in your project, you may need to rename this main function to avoid conflicts.
fun main() = runBlocking {
    val agentFactories = listOf<() -> PartialObservationAgent>(
        { PartialObservationPureRandomAgent() },
        { PartialObservationBetterRandomAgent() },
        { TeamTitansPartialAgentV2() }
    )
    val gamesPerPair = 50
    val gameParams = GameParams(numPlanets = 20, maxTicks = 400)
    val league = PartialRoundRobinLeague(agentFactories, gamesPerPair, gameParams)
    val results = league.runRoundRobin()
    println("\n=== Partial Observation League Results ===")
    val sortedResults = results.toList().sortedByDescending { it.second.points }
    for ((index, entry) in sortedResults.withIndex()) {
        val (agentName, leagueEntry) = entry
        val winRate = if (leagueEntry.nGames > 0) 100.0 * leagueEntry.points / leagueEntry.nGames else 0.0
        println("${index + 1}. $agentName: ${leagueEntry.points} points, ${leagueEntry.nGames} games, ${"%.1f".format(winRate)}% win rate")
    }
} 