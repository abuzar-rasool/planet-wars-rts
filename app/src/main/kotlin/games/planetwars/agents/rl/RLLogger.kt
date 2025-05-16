package games.planetwars.agents.rl

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Logger for the RL agent that handles both console output and file logging.
 */
class RLLogger(
    private val logDirectory: String,
    private val consoleOutput: Boolean = true
) {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val sessionStartTime = LocalDateTime.now().format(formatter)
    private val logFile: File
    private val summaryFile: File
    
    init {
        // Create log directory if it doesn't exist
        File(logDirectory).mkdirs()
        
        // Initialize log files
        logFile = File("$logDirectory/training_log_$sessionStartTime.txt")
        summaryFile = File("$logDirectory/training_summary.txt")
        
        // Add session header to log file
        logFile.writeText("=== RL Agent Training Session: $sessionStartTime ===\n\n")
        
        // Update summary file with new session
        if (summaryFile.exists()) {
            summaryFile.appendText("\n=== New Training Session: $sessionStartTime ===\n")
        } else {
            summaryFile.writeText("=== RL Agent Training Summary ===\n\n")
            summaryFile.appendText("=== Training Session: $sessionStartTime ===\n")
        }
    }
    
    /**
     * Log a message to both console and log file.
     */
    fun log(message: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val formattedMessage = "[$timestamp] $message"
        
        // Console output
        if (consoleOutput) {
            println(formattedMessage)
        }
        
        // Log file output
        try {
            logFile.appendText("$formattedMessage\n")
        } catch (e: Exception) {
            if (consoleOutput) {
                println("Warning: Failed to write to log file: ${e.message}")
            }
        }
    }
    
    /**
     * Log an important summary message to both the regular log and the summary file.
     */
    fun logSummary(message: String) {
        // Log to regular log
        log("SUMMARY: $message")
        
        // Log to summary file
        try {
            summaryFile.appendText("$message\n")
        } catch (e: Exception) {
            if (consoleOutput) {
                println("Warning: Failed to write to summary file: ${e.message}")
            }
        }
    }
    
    /**
     * Log training metrics for visualization/analysis.
     */
    fun logMetrics(episode: Int, reward: Double, winRate: Double, avgLoss: Double? = null) {
        val metricsLine = "Episode: $episode, Reward: $reward, WinRate: $winRate" + 
                          (avgLoss?.let { ", Loss: $it" } ?: "")
        
        log(metricsLine)
        
        // Also log to a CSV file for easy plotting
        try {
            val metricsFile = File("$logDirectory/metrics.csv")
            
            // Create header if file doesn't exist
            if (!metricsFile.exists()) {
                metricsFile.writeText("Episode,Reward,WinRate,AvgLoss\n")
            }
            
            // Append metrics
            metricsFile.appendText("$episode,$reward,$winRate,${avgLoss ?: ""}\n")
        } catch (e: Exception) {
            if (consoleOutput) {
                println("Warning: Failed to write to metrics file: ${e.message}")
            }
        }
    }
    
    /**
     * Log the model architecture and hyperparameters.
     */
    fun logConfig(config: Map<String, Any>) {
        log("Configuration:")
        config.forEach { (key, value) ->
            log("  $key: $value")
        }
        
        // Also log to summary file
        try {
            summaryFile.appendText("\nConfiguration:\n")
            config.forEach { (key, value) ->
                summaryFile.appendText("  $key: $value\n")
            }
            summaryFile.appendText("\n")
        } catch (e: Exception) {
            if (consoleOutput) {
                println("Warning: Failed to write config to summary file: ${e.message}")
            }
        }
    }
} 