package games.planetwars.runners

import games.planetwars.core.GameParams
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Main entry point for running self-play reinforcement learning training.
 */
fun main(args: Array<String>) {
    // Parse command line arguments
    val resume = true
    val useExistingDir = true
    
    // Format timestamp for logging
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    val timestamp = LocalDateTime.now().format(formatter)
    
    println("=== Planet Wars Self-Play Reinforcement Learning ===")
    println("Started at: $timestamp")
    
    // Determine which results directory to use
    val resultsDir = if (resume || useExistingDir) {
        // Look for the most recent results directory
        val resultsBaseDir = File("results/rl_selfplay")
        val existingDirs = resultsBaseDir.listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.lastModified() }
        
        if (existingDirs.isNullOrEmpty()) {
            println("No existing training directories found. Starting new training.")
            "results/rl_selfplay/$timestamp"
        } else {
            val mostRecentDir = existingDirs.first().absolutePath
            println("Using existing directory: $mostRecentDir")
            mostRecentDir
        }
    } else {
        // Create a new directory
        "results/rl_selfplay/$timestamp"
    }
    
    // Create directory if it doesn't exist
    File(resultsDir).mkdirs()
    
    // Set up training parameters
    val trainingParams = mapOf(
        "numPlanets" to 20,
        "maxTicks" to 300,
        "trainingEpisodes" to 20000,
        "snapshotFrequency" to 100,
        "opponentPoolSize" to 10,
        "evaluationFrequency" to 200,
        "evaluationGames" to 10,
        "initialExplorationRate" to 0.3,
        "finalExplorationRate" to 0.05
    )
    
    // Get starting episode if resuming
    var startEpisode = 0
    if (resume) {
        val checkpointFile = File("$resultsDir/models/checkpoint.txt")
        if (checkpointFile.exists()) {
            val checkpointContent = checkpointFile.readText()
            val episodeLine = checkpointContent.lines().firstOrNull { it.startsWith("episode=") }
            if (episodeLine != null) {
                startEpisode = episodeLine.substringAfter("episode=").toIntOrNull() ?: 0
                println("Resuming training from episode $startEpisode")
            }
        } else {
            println("No checkpoint file found. Starting from episode 0.")
        }
    }
    
    // Save parameters to results directory if not resuming
    if (!resume) {
        val paramsFile = File("$resultsDir/training_params.txt")
        paramsFile.writeText("=== Self-Play Training Parameters ===\n")
        trainingParams.forEach { (key, value) ->
            paramsFile.appendText("$key: $value\n")
        }
    }
    
    // Set up the training environment
    val modelDir = "$resultsDir/models"
    val gameParams = GameParams(
        numPlanets = trainingParams["numPlanets"] as Int,
        maxTicks = trainingParams["maxTicks"] as Int
    )
    
    // Create and configure the RL self-play trainer
    val trainer = RLSelfPlayTrainer(
        trainingEpisodes = trainingParams["trainingEpisodes"] as Int,
        snapshotFrequency = trainingParams["snapshotFrequency"] as Int,
        opponentPoolSize = trainingParams["opponentPoolSize"] as Int,
        evalFrequency = trainingParams["evaluationFrequency"] as Int,
        evalGames = trainingParams["evaluationGames"] as Int,
        gameParams = gameParams,
        modelDirectory = modelDir,
        initialExplorationRate = trainingParams["initialExplorationRate"] as Double,
        finalExplorationRate = trainingParams["finalExplorationRate"] as Double,
        startEpisode = startEpisode
    )
    
    // Start the self-play training process
    println("Starting self-play training...")
    trainer.trainAgent()
    
    // Log completion
    println("\nSelf-play training and evaluation completed!")
    println("Results saved to: $resultsDir")
    
    println("\nTo evaluate the trained agent in a league, run:")
    println("java -cp <classpath> games.planetwars.runners.RoundRobinLeagueKt")
    println("\nTo resume training later, run with:")
    println("java -cp <classpath> games.planetwars.runners.RunRLSelfPlayTrainingKt --resume")
} 