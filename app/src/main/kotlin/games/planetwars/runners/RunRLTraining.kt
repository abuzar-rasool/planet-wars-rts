package games.planetwars.runners

import games.planetwars.agents.evo.SimpleEvoAgent
import games.planetwars.agents.random.BetterRandomAgent
import games.planetwars.agents.random.CarefulRandomAgent
import games.planetwars.agents.random.PureRandomAgent
import games.planetwars.agents.rl.RLAgent
import games.planetwars.core.GameParams
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Main entry point for training and evaluating RL agents.
 * This script provides options for different training scenarios.
 */
fun main() {
    // Format timestamp for logging
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    val timestamp = LocalDateTime.now().format(formatter)
    
    println("=== Planet Wars Reinforcement Learning ===")
    println("Started at: $timestamp")
    
    // Create results directory
    val resultsDir = "results/rl_training/$timestamp"
    File(resultsDir).mkdirs()
    
    // Set up training parameters
    val trainingParams = mapOf(
        "numPlanets" to 20,
        "maxTicks" to 300,
        "trainingEpisodes" to 1000,
        "evaluationFrequency" to 50,
        "evaluationGames" to 10,
        "saveFrequency" to 100,
        "batchSize" to 32,
        "explorationRate" to 0.1,
        "learningRate" to 0.001,
        "discountFactor" to 0.99
    )
    
    // Save parameters to results directory
    val paramsFile = File("$resultsDir/training_params.txt")
    paramsFile.writeText("=== Training Parameters ===\n")
    trainingParams.forEach { (key, value) ->
        paramsFile.appendText("$key: $value\n")
    }
    
    // Set up the training environment
    val modelDir = "$resultsDir/models"
    val gameParams = GameParams(
        numPlanets = trainingParams["numPlanets"] as Int,
        maxTicks = trainingParams["maxTicks"] as Int
    )
    
    // Define opponent agents for training
    val trainingOpponents = listOf(
        PureRandomAgent(),
        BetterRandomAgent(),
        CarefulRandomAgent()
    )
    
    // Define stronger opponent agents for final evaluation
    val evaluationOpponents = listOf(
        BetterRandomAgent(),
        CarefulRandomAgent(),
        SimpleEvoAgent(
            useShiftBuffer = true,
            nEvals = 30,
            sequenceLength = 400,
            probMutation = 0.8
        )
    )
    
    // Create and configure the RL trainer
    val trainer = RLTrainer(
        trainingEpisodes = trainingParams["trainingEpisodes"] as Int,
        evalFrequency = trainingParams["evaluationFrequency"] as Int,
        evalGames = trainingParams["evaluationGames"] as Int,
        gameParams = gameParams,
        modelDirectory = modelDir,
        opponentPool = trainingOpponents
    )
    
    // Start the training process
    println("Starting training...")
    trainer.trainAgent()
    
    // Final evaluation against stronger opponents
    println("\n=== Final Evaluation Against Stronger Opponents ===")
    val finalEvalAgent = RLAgent(
        modelDirectory = modelDir,
        isTraining = false,
        explorationRate = 0.0
    )
    
    evaluationOpponents.forEach { opponent ->
        val gameRunner = GameRunner(finalEvalAgent, opponent, gameParams)
        val results = gameRunner.runGames(20)
        
        val wins = results[games.planetwars.core.Player.Player1] ?: 0
        val losses = results[games.planetwars.core.Player.Player2] ?: 0
        val winRate = wins.toDouble() / (wins + losses)
        
        println("Against ${opponent.getAgentType()}: $wins wins, $losses losses (${winRate * 100}% win rate)")
    }
    
    // Log completion
    println("\nTraining and evaluation completed!")
    println("Results saved to: $resultsDir")
    
    // Create example command to run a league with the trained agent
    println("\nTo evaluate the trained agent in a league, run:")
    println("java -cp <classpath> games.planetwars.runners.RoundRobinLeagueKt")
} 