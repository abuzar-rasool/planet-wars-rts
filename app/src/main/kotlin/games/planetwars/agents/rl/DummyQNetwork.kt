package games.planetwars.agents.rl

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import kotlin.math.pow
import kotlin.random.Random

/**
 * A simplified Q-Network implementation for testing the RL agent without external dependencies.
 * 
 * This implementation uses a simple linear model instead of a neural network. It's not meant
 * for production use, but allows testing the RL framework without additional dependencies.
 */
class DummyQNetwork(
    private val inputSize: Int,
    private val outputSize: Int,
    private val hiddenSize: Int = 256,
    private val hiddenLayers: Int = 2
) : Serializable {
    
    // For simplicity, we'll just use a linear model: y = Wx + b
    private var weights: Array<FloatArray>
    private var bias: FloatArray
    
    // Learning rate
    private val alpha = 0.01f
    
    init {
        // Initialize weights with small random values
        weights = Array(outputSize) { FloatArray(inputSize) { (Random.nextFloat() - 0.5f) * 0.1f } }
        bias = FloatArray(outputSize) { (Random.nextFloat() - 0.5f) * 0.1f }
    }
    
    /**
     * Predict action Q-values from a state vector.
     * 
     * @param state The state vector.
     * @return The index of the action with the highest Q-value.
     */
    fun predict(state: FloatArray): Int {
        val qValues = calculateQValues(state)
        
        // Find the action with the highest Q-value
        var bestAction = 0
        var bestValue = qValues[0]
        
        for (i in 1 until outputSize) {
            if (qValues[i] > bestValue) {
                bestValue = qValues[i]
                bestAction = i
            }
        }
        
        return bestAction
    }
    
    /**
     * Get the maximum Q-value for a state.
     * 
     * @param state The state vector.
     * @return The maximum Q-value.
     */
    fun getMaxQ(state: FloatArray): Double {
        val qValues = calculateQValues(state)
        return qValues.maxOrNull()?.toDouble() ?: 0.0
    }
    
    /**
     * Get all Q-values for a state.
     * 
     * @param state The state vector.
     * @return An array of Q-values for all actions.
     */
    fun getQValues(state: FloatArray): DoubleArray {
        val qValues = calculateQValues(state)
        return qValues.map { it.toDouble() }.toDoubleArray()
    }
    
    private fun calculateQValues(state: FloatArray): FloatArray {
        val result = FloatArray(outputSize)
        
        // Calculate Q-values for each action: Q(s,a) = W_a * s + b_a
        for (a in 0 until outputSize) {
            var sum = bias[a]
            for (i in state.indices) {
                sum += weights[a][i] * state[i]
            }
            result[a] = sum
        }
        
        return result
    }
    
    /**
     * Train the network on a batch of state-action-reward-nextState tuples.
     * 
     * @param states Batch of state vectors.
     * @param actions Batch of action indices.
     * @param targetQs Batch of target Q-values.
     * @return The loss value.
     */
    fun train(states: List<FloatArray>, actions: List<Int>, targetQs: FloatArray): Double {
        var totalLoss = 0.0
        
        // Train on each example
        for (i in states.indices) {
            val state = states[i]
            val action = actions[i]
            val targetQ = targetQs[i].toFloat()
            
            // Current prediction
            val qValues = calculateQValues(state)
            val currentQ = qValues[action]
            
            // Calculate loss (MSE)
            val error = targetQ - currentQ
            totalLoss += error.pow(2).toDouble()
            
            // Update weights and bias for the chosen action (gradient descent)
            for (j in state.indices) {
                weights[action][j] += alpha * error * state[j]
            }
            bias[action] += alpha * error
        }
        
        // Return average loss
        return totalLoss / states.size
    }
    
    /**
     * Copy weights from another network.
     * 
     * @param other The other network to copy from.
     */
    fun copyFrom(other: DummyQNetwork) {
        for (a in 0 until outputSize) {
            for (i in 0 until inputSize) {
                weights[a][i] = other.weights[a][i]
            }
            bias[a] = other.bias[a]
        }
    }
    
    /**
     * Save the model to a file.
     * 
     * @param path The file path to save to.
     */
    fun save(path: String) {
        try {
            // Ensure directory exists
            val file = File(path)
            file.parentFile?.mkdirs()
            
            // Save the model
            ObjectOutputStream(file.outputStream()).use { stream ->
                stream.writeObject(this)
            }
        } catch (e: Exception) {
            println("Failed to save model: ${e.message}")
        }
    }
    
    /**
     * Load the model from a file.
     * 
     * @param path The file path to load from.
     * @return True if loading was successful, false otherwise.
     */
    fun load(path: String): Boolean {
        try {
            val file = File(path)
            if (!file.exists()) {
                return false
            }
            
            ObjectInputStream(file.inputStream()).use { stream ->
                val loaded = stream.readObject() as DummyQNetwork
                this.weights = loaded.weights
                this.bias = loaded.bias
            }
            return true
        } catch (e: Exception) {
            println("Failed to load model: ${e.message}")
            return false
        }
    }
    
    /**
     * Get a summary of the model architecture.
     * 
     * @return A string representation of the model architecture.
     */
    fun summary(): String {
        return "Simple linear model with ${inputSize} inputs and ${outputSize} outputs"
    }
} 