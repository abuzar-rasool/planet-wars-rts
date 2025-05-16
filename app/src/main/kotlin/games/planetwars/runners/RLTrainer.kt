package games.planetwars.runners

import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.rl.RLAgent
import games.planetwars.agents.random.BetterRandomAgent
import games.planetwars.agents.random.CarefulRandomAgent
import games.planetwars.agents.random.PureRandomAgent
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A runner class for training RL agents in the Planet Wars game.
 */
class RLTrainer(
    private val trainingEpisodes: Int = 1000,
    private val evalFrequency: Int = 50,
    private val evalGames: Int = 20,
    private val gameParams: GameParams = GameParams(numPlanets = 20),
    private val modelDirectory: String = "models/rl",
    private val opponentPool: List<PlanetWarsAgent> = listOf(
        PureRandomAgent(),
        BetterRandomAgent(),
        CarefulRandomAgent()
    )
) {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val trainingStartTime = LocalDateTime.now().format(formatter)
    private val logDirectory = "$modelDirectory/logs"
    private var currentEpisode = 0
    
    /**
     * Run the full training process for an RL agent.
     */
    fun trainAgent() {
        println("Starting RL agent training at $trainingStartTime")
        println("Training for $trainingEpisodes episodes")
        println("Evaluating every $evalFrequency episodes with $evalGames games")
        
        // Create directories
        File(modelDirectory).mkdirs()
        File(logDirectory).mkdirs()
        
        // Create our RL agent for training
        val rlAgent = RLAgent(
            modelDirectory = modelDirectory,
            isTraining = true
        )
        
        // Main training loop
        for (episode in 1..trainingEpisodes) {
            currentEpisode = episode
            
            // Select a random opponent for this episode
            val opponent = opponentPool.random()
            
            // Run a training episode
            runTrainingEpisode(rlAgent, opponent)
            
            // Evaluate periodically
            if (episode % evalFrequency == 0 || episode == trainingEpisodes) {
                evaluateAgent(rlAgent)
            }
            
            // Print progress
            if (episode % 10 == 0) {
                println("Completed episode $episode/$trainingEpisodes")
            }
        }
        
        println("Training completed!")
    }
    
    /**
     * Run a single training episode (game).
     */
    private fun runTrainingEpisode(rlAgent: RLAgent, opponent: PlanetWarsAgent) {
        // Create a game runner
        val gameRunner = GameRunner(rlAgent, opponent, gameParams)
        
        // Run the game
        val finalState = gameRunner.runGame().state
        
        // The RLAgent automatically learns during gameplay through its getAction() and processGameOver() methods
    }
    
    /**
     * Evaluate the agent's performance against the opponent pool.
     */
    private fun evaluateAgent(rlAgent: RLAgent) {
        println("\nEvaluating agent at episode $currentEpisode...")
        
        // Create a non-training version of the agent for evaluation
        val evalAgent = RLAgent(
            modelDirectory = modelDirectory,
            isTraining = false
        )
        
        var totalWins = 0
        var totalGames = 0
        
        // Evaluate against each opponent
        opponentPool.forEach { opponent ->
            val wins = evaluateAgainstOpponent(evalAgent, opponent)
            val winRate = wins.toDouble() / evalGames
            
            println("Against ${opponent.getAgentType()}: $wins/$evalGames (${winRate * 100}%)")
            
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
    private fun evaluateAgainstOpponent(rlAgent: PlanetWarsAgent, opponent: PlanetWarsAgent): Int {
        val gameRunner = GameRunner(rlAgent, opponent, gameParams)
        val results = gameRunner.runGames(evalGames)
        return results[Player.Player1] ?: 0
    }
}

/**
 * Main function to start RL agent training.
 */
fun main() {
    val trainer = RLTrainer(
        trainingEpisodes = 500,
        evalFrequency = 50,
        evalGames = 10
    )
    
    trainer.trainAgent()
} 