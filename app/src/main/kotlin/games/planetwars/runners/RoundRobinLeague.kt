package games.planetwars.runners

import games.planetwars.agents.DoNothingAgent
import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.evo.SimpleEvoAgent
//import games.planetwars.agents.mcts.MCTSAgent
import games.planetwars.agents.random.BetterRandomAgent
import games.planetwars.agents.random.CarefulRandomAgent
import games.planetwars.agents.random.PureRandomAgent
import  games.planetwars.agents.random.StrategicHeuristicAgent
import games.planetwars.agents.rl.RLAgent
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import java.io.File
import java.io.FileFilter

fun main(args: Array<String>) {
    // Get the agent list based on whether we have a trained model
    val agents = SamplePlayerLists().getListWithTrainedAgent()
    
    val league = RoundRobinLeague(agents, gamesPerPair = 10)
    val results = league.runRoundRobin()
    
    // use the League utils to print the results
    println(results)
    val writer = LeagueWriter()
    val leagueResult = LeagueResult(results.values.toList())
    val markdownContent = writer.generateMarkdownTable(leagueResult)
    writer.saveMarkdownToFile(markdownContent)

    // print sorted results directly to console
    val sortedResults = results.toList().sortedByDescending { it.second.points }.toMap()
    for (entry in sortedResults.values) {
        println("${entry.agentName} : ${entry.points} : ${entry.nGames}")
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


    /**
     * Find the latest trained model in the results directory
     */
    fun findLatestTrainedModel(): String? {
        val resultsDir = File("results/rl_selfplay")
        if (!resultsDir.exists() || !resultsDir.isDirectory) return null

        // Find the most recent directory
        val latestDir = resultsDir.listFiles(FileFilter { f -> f.isDirectory })
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
            ?: return null

        // Look for final model first
        val finalModel = File("${latestDir.absolutePath}/models/final_model.bin")
        if (finalModel.exists()) return finalModel.absolutePath

        // Look for the latest snapshot
        val snapshotsDir = File("${latestDir.absolutePath}/models/snapshots")
        if (!snapshotsDir.exists() || !snapshotsDir.isDirectory) return null

        return snapshotsDir.listFiles(FileFilter { f -> f.name.endsWith(".bin") })
            ?.sortedByDescending {
                val epNumber = it.name.removePrefix("model_ep").removeSuffix(".bin").toIntOrNull() ?: 0
                epNumber
            }
            ?.firstOrNull()
            ?.absolutePath
    }

    fun getFullList(): MutableList<PlanetWarsAgent> {
        return mutableListOf(
            PureRandomAgent(),
            BetterRandomAgent(),
            CarefulRandomAgent(),
            SimpleEvoAgent(
                useShiftBuffer = true,
                nEvals = 30,
                sequenceLength = 400,
                opponentModel = DoNothingAgent(),
                probMutation = 0.8,
            ),
            //MCTSAgent(),
            StrategicHeuristicAgent()
        )
    }

    fun getTrainedAgent(): PlanetWarsAgent {
        val modelPath = findLatestTrainedModel()
        val modelDirectory = File(modelPath).parent
        if (modelPath == null) {
            println("No trained model found")
            throw RuntimeException("No trained model found")
        }
        return RLAgent(
            modelDirectory = modelDirectory,
            isTraining = false,
            explorationRate = 0.0
        ).apply {
            loadSpecificModel(modelPath)
        }
    }

    
    fun getListWithTrainedAgent(): MutableList<PlanetWarsAgent> {
        val modelPath = findLatestTrainedModel()
        // If a model path is specified or found, print it
        if (modelPath != null) {
            println("Using trained model: $modelPath")
        } else {
            println("No trained model specified or found. Using default agents.")
            return getFullList()
        }
        val modelDirectory = File(modelPath).parent
        
        return mutableListOf(
            // PureRandomAgent(),
            // BetterRandomAgent(),
            // CarefulRandomAgent(),
            SimpleEvoAgent(
                useShiftBuffer = true,
                nEvals = 30,
                sequenceLength = 400,
                opponentModel = DoNothingAgent(),
                probMutation = 0.8,
            ),
            // Add our trained RL Agent
//            RLAgent(
//                modelDirectory = modelDirectory,
//                isTraining = false,
//                explorationRate = 0.0
//            ).apply {
//                loadSpecificModel(modelPath)
//            } ,
            //MCTSAgent(),
            StrategicHeuristicAgent()
        )
    }
    
    fun getRLTestList(): MutableList<PlanetWarsAgent> {
        return mutableListOf(
            BetterRandomAgent(),
            CarefulRandomAgent(),
            RLAgent(
                modelDirectory = "models/rl",
                isTraining = false,
                explorationRate = 0.0
            )
        )
    }
}

data class RoundRobinLeague(
    val agents: List<PlanetWarsAgent>,
    val gamesPerPair: Int = 10,
    val gameParams: GameParams = GameParams(numPlanets = 20),
) {
    fun runPair(agent1: PlanetWarsAgent, agent2: PlanetWarsAgent): Map<Player, Int> {
        val gameRunner = GameRunner(agent1, agent2, gameParams)
        return gameRunner.runGames(gamesPerPair)
    }

    fun runRoundRobin(): Map<String, LeagueEntry> {
        val t = System.currentTimeMillis()
        val scores = mutableMapOf<String, LeagueEntry>()
        for (agent in agents) {
            // make a new league entry for each agent in a map indexed by agent type
            scores[agent.getAgentType()] = LeagueEntry(agent.getAgentType())
        }
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
            }
        }
        println("Round Robin took ${(System.currentTimeMillis() - t) / 1000} seconds")
        return scores
    }
}