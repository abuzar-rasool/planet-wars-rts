package games.planetwars.agents.rl

/**
 * Optimizer for training the Q-Network.
 */
class Optimizer(
    private val qNetwork: DummyQNetwork,
    private val learningRate: Double
) {
    private var totalLoss: Double = 0.0
    private var updateCount: Int = 0
    
    /**
     * Update the Q-network using a batch of transitions.
     * 
     * @param states List of state vectors
     * @param actions List of action indices
     * @param targets Target Q-values
     * @return Loss value from the update
     */
    fun update(states: List<FloatArray>, actions: List<Int>, targets: FloatArray): Double {
        // Train the network and get the loss
        val loss = qNetwork.train(states, actions, targets)
        
        // Update metrics
        totalLoss += loss
        updateCount++
        
        return loss
    }
    
    /**
     * Get the average loss over all updates.
     * 
     * @return Average loss or null if no updates have occurred
     */
    fun getAverageLoss(): Double? {
        return if (updateCount > 0) totalLoss / updateCount else null
    }
    
    /**
     * Reset the optimizer metrics.
     */
    fun reset() {
        totalLoss = 0.0
        updateCount = 0
    }
} 