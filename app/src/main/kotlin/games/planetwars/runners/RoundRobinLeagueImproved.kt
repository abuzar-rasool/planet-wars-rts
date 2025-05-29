package games.planetwars.runners

import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.evo.SimpleEvoAgent
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import games.planetwars.agents.strategic.StrategicHeuristicAgent
import games.planetwars.agents.strategic.StrategicAgent
import games.planetwars.agents.strategic.StrategicAgentImproved
import games.planetwars.agents.random.BetterRandomAgent
import games.planetwars.agents.random.CarefulRandomAgent
import games.planetwars.agents.random.PureRandomAgent
import games.planetwars.agents.strategic.BocsimackoAgent
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.KClass

/**
 * An improved version of RoundRobinLeague that uses coroutines for parallel execution
 */
data class RoundRobinLeagueImproved(
    val agentFactories: List<() -> PlanetWarsAgent>, // Use factories to create fresh instances
    val gamesPerPair: Int = 10,
    val gameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 200),
    val parallelism: Int = Runtime.getRuntime().availableProcessors(), // Default to number of available cores
    val verboseGameLogs: Boolean = false // Control verbose logging of individual games
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(parallelism)
    // Synchronize console output to prevent garbled logs
    private val outputLock = Any()
    
    /**
     * Run a matchup between two agents in parallel
     */
    suspend fun runPairParallel(agentFactory1: () -> PlanetWarsAgent, agentFactory2: () -> PlanetWarsAgent): Map<Player, Int> = coroutineScope {
        // Get agent types for logging (using temporary instances that won't be used for games)
        val agent1Type = agentFactory1().getAgentType()
        val agent2Type = agentFactory2().getAgentType()
        
        synchronized(outputLock) {
            println("\nRunning ${gamesPerPair} games in parallel: $agent1Type vs $agent2Type")
            if (!verboseGameLogs) {
                printGameParams(gameParams)
            }
        }
        
        val startTime = System.currentTimeMillis()
        val aggregateResults = ConcurrentHashMap<Player, AtomicInteger>().apply {
            put(Player.Player1, AtomicInteger(0))
            put(Player.Player2, AtomicInteger(0))
            put(Player.Neutral, AtomicInteger(0))
        }
        
        val jobs = List(gamesPerPair) { gameNumber ->
            async(dispatcher) {
                try {
                    // Create fresh instances for each game to avoid thread safety issues
                    val agent1 = agentFactory1()
                    val agent2 = agentFactory2()
                    
                    val gameStartTime = System.currentTimeMillis()
                    val gameRunner = GameRunner(agent1, agent2, gameParams)
                    val finalModel = gameRunner.runGame()
                    val winner = finalModel.getLeader()
                    val gameElapsedTime = (System.currentTimeMillis() - gameStartTime) / 1000.0
                    
                    // Update results atomically
                    aggregateResults[winner]!!.incrementAndGet()
                    
                    // Log individual game result if verbose logging is enabled
                    if (verboseGameLogs) {
                        synchronized(outputLock) {
                            println("Game $gameNumber: Winner=${winner} " +
                                    "(${if (winner == Player.Player1) agent1.getAgentType() else if (winner == Player.Player2) agent2.getAgentType() else "Draw"}) " +
                                    "[${gameElapsedTime}s]")
                        }
                    }
                } catch (e: Exception) {
                    synchronized(outputLock) {
                        println("ERROR in game $gameNumber: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
        
        // Wait for all games to finish
        jobs.awaitAll()
        
        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0
        synchronized(outputLock) {
            println("Match summary - P1($agent1Type)=${aggregateResults[Player.Player1]!!.get()} | " +
                    "P2($agent2Type)=${aggregateResults[Player.Player2]!!.get()} | " +
                    "Draws=${aggregateResults[Player.Neutral]!!.get()} (${elapsedTime}s)")
        }
        
        // Convert AtomicInteger to Int in the result map
        mapOf(
            Player.Player1 to aggregateResults[Player.Player1]!!.get(),
            Player.Player2 to aggregateResults[Player.Player2]!!.get(),
            Player.Neutral to aggregateResults[Player.Neutral]!!.get()
        )
    }
    
    /**
     * Run the entire round robin tournament
     */
    suspend fun runRoundRobinParallel(): Map<String, LeagueEntry> = coroutineScope {
        val t = System.currentTimeMillis()
        
        // Get agent types for display (using temporary instances that won't be used for games)
        val agentTypes = agentFactories.map { it().getAgentType() }
        
        synchronized(outputLock) {
            println("\n----- Starting Parallel Round Robin Tournament -----")
            println("Agents: $agentTypes")
            println("Games per matchup: $gamesPerPair")
            println("Parallelism: $parallelism threads")
            printGameParams(gameParams)
        }
        
        // Thread-safe scores map
        val scores = ConcurrentHashMap<String, LeagueEntry>()
        for (agentType in agentTypes) {
            scores[agentType] = LeagueEntry(agentType)
        }
        
        // Calculate total number of matchups for progress tracking
        val totalMatchups = agentFactories.size * (agentFactories.size - 1)
        val completedMatchups = AtomicInteger(0)
        
        // Create all matchup pairs
        val matchups = mutableListOf<Pair<Int, Int>>()
        for (i in agentFactories.indices) {
            for (j in agentFactories.indices) {
                if (i != j) {
                    matchups.add(Pair(i, j))
                }
            }
        }
        
        // Run each matchup (limiting to parallelism level for resource management)
        matchups.chunked(parallelism).forEach { chunk ->
            val chunkJobs = chunk.map { (i, j) ->
                async(dispatcher) {
                    try {
                        val agentFactory1 = agentFactories[i]
                        val agentFactory2 = agentFactories[j]
                        
                        // Get agent types
                        val agent1Type = agentFactory1().getAgentType()
                        val agent2Type = agentFactory2().getAgentType()
                        
                        val result = runPairParallel(agentFactory1, agentFactory2)
                        
                        // Update the league scores for each agent
                        synchronized(scores) {
                            val leagueEntry1 = scores[agent1Type]!!
                            val leagueEntry2 = scores[agent2Type]!!
                            leagueEntry1.points += result[Player.Player1]!!
                            leagueEntry2.points += result[Player.Player2]!!
                            leagueEntry1.nGames += gamesPerPair
                            leagueEntry2.nGames += gamesPerPair
                        }
                        
                        // Update progress
                        val current = completedMatchups.incrementAndGet()
                        val progressPercent = (current * 100.0 / totalMatchups).toInt()
                        synchronized(outputLock) {
                            println("Progress: $current/$totalMatchups matchups ($progressPercent%)")
                        }
                        
                        // Print current standings at intervals
                        if (current % (totalMatchups / 4).coerceAtLeast(1) == 0 || current == totalMatchups) {
                            printCurrentStandings(scores)
                        }
                    } catch (e: Exception) {
                        synchronized(outputLock) {
                            println("ERROR in matchup: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
            
            // Wait for this chunk to complete before starting the next chunk
            runBlocking { 
                chunkJobs.awaitAll()
            }
        }
        
        val totalTime = (System.currentTimeMillis() - t) / 1000.0
        synchronized(outputLock) {
            println("\n----- Parallel Round Robin Tournament Complete -----")
            println("Total time: $totalTime seconds (${totalTime / 60} minutes)")
        }
        
        scores.toMap()
    }
    
    private fun printCurrentStandings(scores: ConcurrentHashMap<String, LeagueEntry>) {
        synchronized(outputLock) {
            println("\n----- Current Standings -----")
            val currentRankings = scores.toList().sortedByDescending { it.second.points }.toMap()
            for ((index, entry) in currentRankings.values.withIndex()) {
                println("${index + 1}. ${entry.agentName}: ${entry.points} points (${entry.nGames} games)")
            }
            println("--------------------------\n")
        }
    }
    
    // Helper function to create agent factories from a list of agent classes
    companion object {
        fun createLeagueWithAgentClasses(
            agentClasses: List<KClass<out PlanetWarsAgent>>, 
            gamesPerPair: Int = 100,
            gameParams: GameParams = GameParams(numPlanets = 20, maxTicks = 200),
            parallelism: Int = Runtime.getRuntime().availableProcessors(),
            verboseGameLogs: Boolean = false
        ): RoundRobinLeagueImproved {
            // Create factories for each agent class
            val factories = agentClasses.map { agentClass ->
                { 
                    // Try to instantiate using primary constructor with no args
                    agentClass.primaryConstructor?.call() ?: 
                    // Fallback to instance creation if that fails
                    agentClass.java.getDeclaredConstructor().newInstance()
                }
            }
            
            return RoundRobinLeagueImproved(
                agentFactories = factories,
                gamesPerPair = gamesPerPair,
                gameParams = gameParams,
                parallelism = parallelism,
                verboseGameLogs = verboseGameLogs
            )
        }
    }
}

/**
 * Main function to run the improved round robin tournament
 */
fun main() = runBlocking {
    // Create the list of agent factories
    val agentFactories = listOf<() -> PlanetWarsAgent>(
        { StrategicAgent() },
        { StrategicAgentImproved() },
        { BocsimackoAgent() },
        { BetterRandomAgent() },
        { CarefulRandomAgent() },
        { PureRandomAgent() },
        { SimpleEvoAgent() },
    )
    
    println("Starting Parallel Round Robin League with ${agentFactories.size} agents")
    val gameParams = GameParams(numPlanets = 20, maxTicks = 400)
    printGameParams(gameParams)
    
    // Create league with agent factories to instantiate new agents for each game
    val league = RoundRobinLeagueImproved(
        agentFactories = agentFactories, 
        gamesPerPair = 40,
        gameParams = gameParams,
        parallelism = 4,
        verboseGameLogs = false
    )
    
    val timeTaken = measureTimeMillis {
        val results = league.runRoundRobinParallel()
        
        // Use the League utils to print the results
        println(results)
        val writer = LeagueWriter()
        val leagueResult = LeagueResult(results.values.toList())
        val markdownContent = writer.generateMarkdownTable(leagueResult)
        writer.saveMarkdownToFile(markdownContent)
        println("Markdown results saved to file")

        // Print sorted results directly to console
        println("\nFinal Rankings:")
        val sortedResults = results.toList().sortedByDescending { it.second.points }.toMap()
        for ((index, entry) in sortedResults.values.withIndex()) {
            println("${index + 1}. ${entry.agentName} : ${entry.points} points : ${entry.nGames} games")
        }
    }
    
    println("\nTotal execution time: ${timeTaken / 1000.0} seconds")
} 