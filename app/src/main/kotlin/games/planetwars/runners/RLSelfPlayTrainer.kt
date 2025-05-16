package games.planetwars.runners

import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.rl.RLAgent
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import java.io.File
import java.io.FileFilter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * A trainer class for reinforcement learning agents using self-play.
 * 
 * Self-play allows the agent to learn by playing against previous versions of itself,
 * which can lead to more robust strategies and continual improvement.
 */
class RLSelfPlayTrainer(
    private val trainingEpisodes: Int = 5000,
    private val snapshotFrequency: Int = 100,
    private val opponentPoolSize: Int = 10,
    private val evalFrequency: Int = 200,
    private val evalGames: Int = 20,
    private val gameParams: GameParams = GameParams(numPlanets = 20),
    private val modelDirectory: String = "models/rl_selfplay",
    private val initialExplorationRate: Double = 0.3,
    private val finalExplorationRate: Double = 0.05,
    private val startEpisode: Int = 0 // Add parameter to start from a specific episode
) {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val trainingStartTime = LocalDateTime.now().format(formatter)
    private val logDirectory = "$modelDirectory/logs"
    private val snapshotsDirectory = "$modelDirectory/snapshots"
    private val checkpointFile = "$modelDirectory/checkpoint.txt"
    
    // This will store paths to the saved model snapshots
    private val opponentPool = mutableListOf<String>()
    
    private var currentEpisode = startEpisode
    private var totalWins = 0
    private var totalGames = 0
    private var recentResults = mutableListOf<Boolean>() // Track recent game results
    
    /**
     * Run the full self-play training process.
     */
    fun trainAgent() {
        println("Starting RL self-play training at $trainingStartTime")
        println("Training for $trainingEpisodes episodes (starting from episode $startEpisode)")
        println("Taking snapshots every $snapshotFrequency episodes")
        println("Evaluating every $evalFrequency episodes with $evalGames games")
        
        // Create necessary directories
        File(modelDirectory).mkdirs()
        File(logDirectory).mkdirs()
        File(snapshotsDirectory).mkdirs()
        
        // Log training configuration
        logConfiguration()
        
        // Create our main RL agent for training
        val mainAgent = RLAgent(
            modelDirectory = modelDirectory,
            isTraining = true,
            explorationRate = initialExplorationRate
        )
        
        // Load existing models if continuing training
        if (startEpisode > 0) {
            println("Continuing training from episode $startEpisode")
            // Load the latest model
            val latestSnapshotPath = "$snapshotsDirectory/model_ep${startEpisode}.bin"
            if (File(latestSnapshotPath).exists()) {
                mainAgent.loadSpecificModel(latestSnapshotPath)
                println("Loaded model from $latestSnapshotPath")
            } else {
                println("Warning: Could not find model for episode $startEpisode, starting with a new model")
            }
            
            // Load opponent pool
            loadOpponentPool()
        } else {
            // Take initial snapshot for episode 0
            takeSnapshot(mainAgent, 0)
        }
        
        // Display current opponent pool
        println("Current opponent pool size: ${opponentPool.size}")
        
        // Main training loop
        for (episode in (startEpisode + 1)..trainingEpisodes) {
            currentEpisode = episode
            
            // Adjust exploration rate using a decay schedule
            val explorationRate = calculateExplorationRate(episode)
            mainAgent.setExplorationRate(explorationRate)
            
            // Select opponent from pool or use main agent if pool is empty
            val opponent = selectOpponent()
            
            // Run a self-play episode
            val result = runSelfPlayEpisode(mainAgent, opponent)
            
            // Track results for win rate calculation
            if (result != null) {
                totalGames++
                if (result) totalWins++
                
                // Keep track of recent results (last 100 games)
                recentResults.add(result)
                if (recentResults.size > 100) {
                    recentResults.removeAt(0)
                }
            }
            
            // Take snapshot periodically
            if (episode % snapshotFrequency == 0) {
                takeSnapshot(mainAgent, episode)
                saveCheckpoint(episode) // Save checkpoint
            }
            
            // Evaluate periodically
            if (episode % evalFrequency == 0 || episode == trainingEpisodes) {
                evaluateAgent(mainAgent)
            }
            
            // Log progress more frequently
            if (episode % 10 == 0) {
                val recentWinRate = if (recentResults.isNotEmpty()) 
                    recentResults.count { it }.toDouble() / recentResults.size * 100 
                else 0.0
                
                val overallWinRate = if (totalGames > 0)
                    totalWins.toDouble() / totalGames * 100
                else 0.0
                
                println("Episode $episode/$trainingEpisodes:")
                println("  Exploration rate: $explorationRate")
                println("  Recent win rate: $recentWinRate% (last ${recentResults.size} games)")
                println("  Overall win rate: $overallWinRate% ($totalWins/$totalGames)")
                println("  Opponent pool size: ${opponentPool.size}")
            }
        }
        
        // Final snapshot
        takeSnapshot(mainAgent, trainingEpisodes)
        
        // Create a copy in the base model directory for easy access by league
        val finalModelPath = "$modelDirectory/final_model.bin"
        File("$snapshotsDirectory/model_ep${trainingEpisodes}.bin").copyTo(File(finalModelPath), overwrite = true)
        println("Final model saved to $finalModelPath")
        
        println("Self-play training completed!")
        println("Models saved to: $modelDirectory")
        println("To use in round robin league, reference: $finalModelPath")
    }
    
    /**
     * Run a single training episode with self-play.
     * @return true if main agent won, false if lost, null if draw
     */
    private fun runSelfPlayEpisode(mainAgent: RLAgent, opponent: PlanetWarsAgent): Boolean? {
        // Create a game runner
        val gameRunner = GameRunner(mainAgent, opponent, gameParams)
        
        // Run the game
        val forwardModel = gameRunner.runGame()
        
        // Determine the winner
        val winner = forwardModel.getLeader()
        return when (winner) {
            Player.Player1 -> true   // Main agent won
            Player.Player2 -> false  // Opponent won
            else -> null             // Draw or neutral
        }
        
        // The RLAgent automatically learns during gameplay through its getAction() and processGameOver() methods
    }
    
    /**
     * Save a checkpoint to allow resuming training later
     */
    private fun saveCheckpoint(episode: Int) {
        val checkpointData = "episode=$episode\ntime=${LocalDateTime.now()}\n"
        File(checkpointFile).writeText(checkpointData)
        println("Checkpoint saved at episode $episode")
    }
    
    /**
     * Load the opponent pool from the snapshots directory
     */
    private fun loadOpponentPool() {
        opponentPool.clear()
        val snapshotsDir = File(snapshotsDirectory)
        
        if (snapshotsDir.exists() && snapshotsDir.isDirectory) {
            // Get all model files and sort them by episode number
            val modelFiles = snapshotsDir.listFiles(FileFilter { f -> 
                f.name.startsWith("model_ep") && f.name.endsWith(".bin") 
            })?.sortedBy { 
                val epNumber = it.name.removePrefix("model_ep").removeSuffix(".bin").toIntOrNull() ?: 0
                epNumber
            } ?: emptyList()
            
            // Add most recent ones to the pool based on pool size
            for (i in (modelFiles.size - opponentPoolSize).coerceAtLeast(0) until modelFiles.size) {
                opponentPool.add(modelFiles[i].absolutePath)
            }
        }
        
        println("Loaded ${opponentPool.size} models to opponent pool")
    }
    
    /**
     * Select an opponent from the pool of previous agent versions.
     * If the pool is empty, create a default non-learning agent.
     */
    private fun selectOpponent(): PlanetWarsAgent {
        if (opponentPool.isEmpty()) {
            // Return a non-learning copy of the agent if no snapshots are available
            return RLAgent(
                modelDirectory = modelDirectory,
                isTraining = false,
                explorationRate = 0.1
            )
        }
        
        // Select opponent strategy:
        // 1. With 70% probability, choose a random opponent from the pool
        // 2. With 30% probability, choose the most recent opponent (harder)
        val useRecentOpponent = Random.nextDouble() < 0.3
        
        val snapshotPath = if (useRecentOpponent) {
            opponentPool.last()
        } else {
            opponentPool[Random.nextInt(opponentPool.size)]
        }
        
        // Load the selected opponent
        val opponent = RLAgent(
            modelDirectory = File(snapshotPath).parent,
            isTraining = false,
            explorationRate = 0.05 // Small exploration for variety
        )
        
        // Make sure to load the specific model
        opponent.loadSpecificModel(snapshotPath)
        
        return opponent
    }
    
    /**
     * Take a snapshot of the current agent and add it to the opponent pool.
     */
    private fun takeSnapshot(agent: RLAgent, episode: Int) {
        val snapshotPath = "$snapshotsDirectory/model_ep${episode}.bin"
        
        // Save the current model as a snapshot
        agent.saveSpecificModel(snapshotPath)
        
        // Add to pool
        opponentPool.add(snapshotPath)
        
        // If pool exceeds the desired size, remove oldest snapshots
        while (opponentPool.size > opponentPoolSize) {
            opponentPool.removeAt(0)
        }
        
        println("Snapshot taken at episode $episode, pool size: ${opponentPool.size}")
    }
    
    /**
     * Evaluate the current agent against all opponents in the pool.
     */
    private fun evaluateAgent(mainAgent: RLAgent) {
        println("\nEvaluating agent at episode $currentEpisode...")
        
        // If pool is empty, nothing to evaluate against
        if (opponentPool.isEmpty()) {
            println("No opponents in pool yet. Skipping evaluation.")
            return
        }
        
        var totalWins = 0
        var totalGames = 0
        
        // Run evaluation against each opponent in the pool
        for (opponentPath in opponentPool) {
            val opponent = RLAgent(
                modelDirectory = File(opponentPath).parent,
                isTraining = false,
                explorationRate = 0.0
            )
            opponent.loadSpecificModel(opponentPath)
            
            // Run evaluation games
            val wins = evaluateAgainstOpponent(mainAgent, opponent)
            val winRate = wins.toDouble() / evalGames
            
            val opponentEpisode = opponentPath.substringAfterLast("_ep").substringBefore(".bin")
            println("Against snapshot from episode $opponentEpisode: $wins/$evalGames (${winRate * 100}%)")
            
            totalWins += wins
            totalGames += evalGames
        }
        
        // Calculate overall win rate
        val overallWinRate = totalWins.toDouble() / totalGames
        println("Overall Results: $totalWins/$totalGames (${overallWinRate * 100}%)")
        
        // Log results
        val resultsFile = File("$logDirectory/evaluation_results.csv")
        if (!resultsFile.exists()) {
            resultsFile.writeText("Episode,TotalWins,TotalGames,WinRate\n")
        }
        resultsFile.appendText("$currentEpisode,$totalWins,$totalGames,$overallWinRate\n")
        
        println("Evaluation completed\n")
    }
    
    /**
     * Evaluate the agent against a specific opponent.
     * 
     * @return Number of wins.
     */
    private fun evaluateAgainstOpponent(agent: RLAgent, opponent: PlanetWarsAgent): Int {
        // Create a non-training version of the agent for evaluation
        val evalAgent = RLAgent(
            modelDirectory = modelDirectory,
            isTraining = false,
            explorationRate = 0.0
        )
        evalAgent.loadLatestModel()
        
        val gameRunner = GameRunner(evalAgent, opponent, gameParams)
        val results = gameRunner.runGames(evalGames)
        return results[Player.Player1] ?: 0
    }
    
    /**
     * Calculate the exploration rate for a given episode using a decay schedule.
     */
    private fun calculateExplorationRate(episode: Int): Double {
        // Linear decay from initialExplorationRate to finalExplorationRate
        val progress = episode.toDouble() / trainingEpisodes
        return initialExplorationRate - progress * (initialExplorationRate - finalExplorationRate)
    }
    
    /**
     * Log the training configuration to file.
     */
    private fun logConfiguration() {
        val configFile = File("$logDirectory/training_config.txt")
        configFile.writeText("=== RL Self-Play Training Configuration ===\n")
        configFile.appendText("Started at: $trainingStartTime\n")
        configFile.appendText("Training episodes: $trainingEpisodes\n")
        configFile.appendText("Snapshot frequency: $snapshotFrequency\n")
        configFile.appendText("Opponent pool size: $opponentPoolSize\n")
        configFile.appendText("Evaluation frequency: $evalFrequency\n")
        configFile.appendText("Evaluation games: $evalGames\n")
        configFile.appendText("Game parameters: ${gameParams.numPlanets} planets, ${gameParams.maxTicks} max ticks\n")
        configFile.appendText("Initial exploration rate: $initialExplorationRate\n")
        configFile.appendText("Final exploration rate: $finalExplorationRate\n")
    }
}

/**
 * Main function to run the self-play trainer.
 */
fun main() {
    val trainer = RLSelfPlayTrainer(
        trainingEpisodes = 2000,
        snapshotFrequency = 100,
        opponentPoolSize = 10,
        evalFrequency = 200
    )
    
    trainer.trainAgent()
} 