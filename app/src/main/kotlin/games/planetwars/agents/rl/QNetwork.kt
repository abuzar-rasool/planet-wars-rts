package games.planetwars.agents.rl

import org.deeplearning4j.nn.conf.NeuralNetConfiguration
import org.deeplearning4j.nn.conf.layers.DenseLayer
import org.deeplearning4j.nn.conf.layers.OutputLayer
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.nn.weights.WeightInit
import org.nd4j.linalg.activations.Activation
import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.learning.config.Adam
import org.nd4j.linalg.lossfunctions.LossFunctions
import java.io.File
import java.io.Serializable

/**
 * DeepLearning4J implementation of a Q-Network for deep reinforcement learning.
 */
class QNetwork(
    private val inputSize: Int,
    private val outputSize: Int,
    private val hiddenSize: Int = 256,
    private val hiddenLayers: Int = 2
) : Serializable {
    
    // The underlying DL4J model
    private var model: MultiLayerNetwork
    
    init {
        // Build neural network configuration
        val configuration = NeuralNetConfiguration.Builder()
            .seed(123)
            .weightInit(WeightInit.XAVIER)
            .updater(Adam(0.001))
            .l2(0.0001)
            .list()
        
        // Add first hidden layer
        configuration.layer(
            0,
            DenseLayer.Builder()
                .nIn(inputSize)
                .nOut(hiddenSize)
                .activation(Activation.RELU)
                .build()
        )
        
        // Add additional hidden layers if requested
        for (i in 1 until hiddenLayers) {
            configuration.layer(
                i,
                DenseLayer.Builder()
                    .nIn(hiddenSize)
                    .nOut(hiddenSize)
                    .activation(Activation.RELU)
                    .build()
            )
        }
        
        // Add output layer
        configuration.layer(
            hiddenLayers,
            OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                .nIn(hiddenSize)
                .nOut(outputSize)
                .activation(Activation.IDENTITY)
                .build()
        )
        
        // Build the model
        model = MultiLayerNetwork(configuration.build())
        model.init()
    }
    
    /**
     * Predict action Q-values from a state vector.
     * 
     * @param state The state vector.
     * @return The index of the action with the highest Q-value.
     */
    fun predict(state: FloatArray): Int {
        val input = Nd4j.create(state, intArrayOf(1, state.size))
        val output = model.output(input)
        
        // Find the action with the highest Q-value
        return output.argMax(1).getInt(0)
    }
    
    /**
     * Get the maximum Q-value for a state.
     * 
     * @param state The state vector.
     * @return The maximum Q-value.
     */
    fun getMaxQ(state: FloatArray): Double {
        val input = Nd4j.create(state, intArrayOf(1, state.size))
        val output = model.output(input)
        
        // Return the maximum Q-value
        return output.maxNumber().toDouble()
    }
    
    /**
     * Get all Q-values for a state.
     * 
     * @param state The state vector.
     * @return An array of Q-values for all actions.
     */
    fun getQValues(state: FloatArray): DoubleArray {
        val input = Nd4j.create(state, intArrayOf(1, state.size))
        val output = model.output(input)
        
        // Convert output to DoubleArray
        val result = DoubleArray(outputSize)
        for (i in 0 until outputSize) {
            result[i] = output.getDouble(0, i)
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
        // Create input batch
        val batchSize = states.size
        val inputBatch = Nd4j.create(batchSize, inputSize)
        for (i in 0 until batchSize) {
            inputBatch.putRow(i.toLong(), Nd4j.create(states[i]))
        }
        
        // Forward pass to get current Q-values
        val currentQs = model.output(inputBatch)
        
        // Create target batch (copy of current outputs)
        val targetBatch = currentQs.dup()
        
        // Update only the Q-values for the actions that were taken
        for (i in 0 until batchSize) {
            targetBatch.putScalar(intArrayOf(i, actions[i]), targetQs[i].toDouble())
        }
        
        // Train on this batch and return the score
        model.fit(inputBatch, targetBatch)
        // Calculate and return mean squared error as the loss
        val predictions = model.output(inputBatch)
        var mse = 0.0
        for (i in 0 until batchSize) {
            val error = predictions.getDouble(i.toLong(), actions[i].toLong()) - targetQs[i]
            mse += error * error
        }
        return mse / batchSize
    }
    
    /**
     * Copy weights from another network.
     * 
     * @param other The other network to copy from.
     */
    fun copyFrom(other: QNetwork) {
        model.setParameters(other.model.params())
    }
    
    /**
     * Save the model to a file.
     * 
     * @param path The file path to save to.
     */
    fun save(path: String) {
        // Ensure directory exists
        val file = File(path)
        file.parentFile?.mkdirs()
        
        // Save the model
        model.save(file)
    }
    
    /**
     * Load the model from a file.
     * 
     * @param path The file path to load from.
     * @return True if loading was successful, false otherwise.
     */
    fun load(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) {
            return false
        }
        
        try {
            model = MultiLayerNetwork.load(file, true)
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
        return model.summary()
    }
} 