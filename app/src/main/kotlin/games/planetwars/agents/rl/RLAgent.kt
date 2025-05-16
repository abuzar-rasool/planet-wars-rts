package games.planetwars.agents.rl

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.GameParams
import games.planetwars.core.GameState
import games.planetwars.core.Player
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

/**
 * A reinforcement learning agent for Planet Wars.
 * 
 * This agent uses Deep Q-Learning to make decisions about which planets to attack
 * and how many ships to send.
 */
class RLAgent(
    private val modelDirectory: String = "models/rl",
    private val learningRate: Double = 0.001,
    private val discountFactor: Double = 0.99,
    private var explorationRate: Double = 0.1,
    private val batchSize: Int = 32,
    private val memoryCapacity: Int = 10000,
    private val targetNetworkUpdateFrequency: Int = 1000,
    private val saveFrequency: Int = 5000,
    private val logFrequency: Int = 100,
    private val isTraining: Boolean = true
) : PlanetWarsPlayer() {

    private lateinit var stateEncoder: StateEncoder
    private lateinit var actionEncoder: ActionEncoder
    private lateinit var replayMemory: ReplayMemory
    private lateinit var qNetwork: DummyQNetwork
    private lateinit var targetNetwork: DummyQNetwork
    private lateinit var optimizer: Optimizer
    private lateinit var logger: RLLogger
    
    private var gameCount: Int = 0
    private var stepCount: Int = 0
    private var episodeReward: Double = 0.0
    private var lastState: FloatArray? = null
    private var lastAction: Int = -1
    
    override fun prepareToPlayAs(player: Player, params: GameParams, opponent: String?): String {
        super.prepareToPlayAs(player, params, opponent)
        
        // Initialize components if not already initialized
        if (!::stateEncoder.isInitialized) {
            val stateDim = calculateStateDimension(params)
            val actionDim = calculateActionDimension(params)
            
            stateEncoder = StateEncoder(params.numPlanets)
            actionEncoder = ActionEncoder(params.numPlanets)
            replayMemory = ReplayMemory(memoryCapacity)
            
            // Create model directory if it doesn't exist
            File(modelDirectory).mkdirs()
            
            // Initialize logger
            logger = RLLogger("$modelDirectory/logs")
            
            // Try to load existing model or create a new one
            val modelPath = "$modelDirectory/model_latest.bin"
            if (File(modelPath).exists()) {
                try {
                    loadModel(modelPath)
                    logger.log("Loaded existing model from $modelPath")
                } catch (e: Exception) {
                    logger.log("Failed to load model: ${e.message}. Creating new model.")
                    initializeNewModel(stateDim, actionDim)
                }
            } else {
                logger.log("No existing model found. Creating new model.")
                initializeNewModel(stateDim, actionDim)
            }
        }
        
        // Reset episode variables
        episodeReward = 0.0
        lastState = null
        lastAction = -1
        
        return getAgentType()
    }
    
    override fun getAction(gameState: GameState): Action {
        // Convert game state to a vector representation
        val stateVector = stateEncoder.encode(gameState, player)
        
        // Store the last state-action pair for learning
        if (isTraining && lastState != null) {
            // Calculate reward from last action
            val reward = calculateReward(gameState)
            episodeReward += reward
            
            // Store transition in replay memory
            replayMemory.add(lastState!!, lastAction, reward, stateVector, gameState.gameTick >= params.maxTicks)
            
            // Learn from replay memory
            if (replayMemory.size() >= batchSize) {
                learn()
            }
        }
        
        // Select action either via exploration or exploitation
        val actionIndex = if (isTraining && Math.random() < explorationRate) {
            // Exploration: choose random action
            (0 until actionEncoder.getActionSpaceSize()).random()
        } else {
            // Exploitation: choose best action according to Q-network
            qNetwork.predict(stateVector)
        }
        
        // Convert action index to game action
        val action = actionEncoder.decode(actionIndex, gameState, player)
        
        // Store current state and action for next step
        lastState = stateVector
        lastAction = actionIndex
        stepCount++
        
        // Log periodically during training
        if (isTraining && stepCount % logFrequency == 0) {
            logger.log("Step: $stepCount, Exploration: $explorationRate, Last reward: ${calculateReward(gameState)}")
        }
        
        // Save model periodically during training
        if (isTraining && stepCount % saveFrequency == 0) {
            saveModel("$modelDirectory/model_step_$stepCount.bin")
            saveModel("$modelDirectory/model_latest.bin")
            logger.log("Model saved at step $stepCount")
        }
        
        return action
    }
    
    override fun processGameOver(finalState: GameState) {
        if (isTraining && lastState != null) {
            // Calculate final reward
            val finalReward = calculateFinalReward(finalState)
            episodeReward += finalReward
            
            // Get terminal state representation
            val terminalState = stateEncoder.encode(finalState, player)
            
            // Store final transition
            replayMemory.add(lastState!!, lastAction, finalReward, terminalState, true)
            
            // Learn from replay memory
            if (replayMemory.size() >= batchSize) {
                learn()
            }
            
            // Log episode results
            gameCount++
            val winner = determineWinner(finalState)
            val result = when (winner) {
                player -> "WON"
                player.opponent() -> "LOST"
                else -> "DRAW"
            }
            
            logger.log("Game $gameCount completed. Result: $result. Total reward: $episodeReward")
            
            // Save model after game
            if (gameCount % 10 == 0) {
                saveModel("$modelDirectory/model_game_$gameCount.bin")
                saveModel("$modelDirectory/model_latest.bin")
                logger.log("Model saved after game $gameCount")
            }
        }
        
        // Reset for next game
        lastState = null
        lastAction = -1
        episodeReward = 0.0
    }
    
    /**
     * Set the exploration rate dynamically.
     * Useful for decaying exploration during training.
     */
    fun setExplorationRate(rate: Double) {
        this.explorationRate = rate.coerceIn(0.0, 1.0)
    }
    
    /**
     * Load a specific model from the given path.
     * Used for loading snapshots in self-play.
     */
    fun loadSpecificModel(path: String): Boolean {
        return try {
            // Initialize logger if not already initialized
            if (!::logger.isInitialized) {
                File(modelDirectory).mkdirs()
                logger = RLLogger("$modelDirectory/logs")
            }
            
            // Make sure all required components are initialized
            if (!::qNetwork.isInitialized) {
                // Use default values for initialization
                val defaultPlanets = 20 // Default number of planets
                val stateDim = defaultPlanets * 8
                val actionDim = defaultPlanets * defaultPlanets
                
                stateEncoder = StateEncoder(defaultPlanets)
                actionEncoder = ActionEncoder(defaultPlanets)
                replayMemory = ReplayMemory(memoryCapacity)
                qNetwork = DummyQNetwork(stateDim, actionDim)
                targetNetwork = DummyQNetwork(stateDim, actionDim)
                optimizer = Optimizer(qNetwork, learningRate)
                
                logger.log("Initialized network components with default parameters")
            }
            
            loadModel(path)
            logger.log("Loaded specific model from $path")
            true
        } catch (e: Exception) {
            if (::logger.isInitialized) {
                logger.log("Failed to load specific model from $path: ${e.message}")
            } else {
                println("Failed to load specific model from $path: ${e.message}")
            }
            false
        }
    }
    
    /**
     * Save a model to a specific path.
     * Used for saving snapshots in self-play.
     */
    fun saveSpecificModel(path: String): Boolean {
        return try {
            // Initialize logger if not already initialized
            if (!::logger.isInitialized) {
                File(modelDirectory).mkdirs()
                logger = RLLogger("$modelDirectory/logs")
            }
            
            // Make sure all required components are initialized
            if (!::qNetwork.isInitialized) {
                // Use default values for initialization
                val defaultPlanets = 20 // Default number of planets
                val stateDim = defaultPlanets * 8
                val actionDim = defaultPlanets * defaultPlanets
                
                stateEncoder = StateEncoder(defaultPlanets)
                actionEncoder = ActionEncoder(defaultPlanets)
                replayMemory = ReplayMemory(memoryCapacity)
                qNetwork = DummyQNetwork(stateDim, actionDim)
                targetNetwork = DummyQNetwork(stateDim, actionDim)
                optimizer = Optimizer(qNetwork, learningRate)
                
                logger.log("Initialized network components with default parameters")
            }
            
            saveModel(path)
            logger.log("Saved model to $path")
            true
        } catch (e: Exception) {
            if (::logger.isInitialized) {
                logger.log("Failed to save model to $path: ${e.message}")
            } else {
                println("Failed to save model to $path: ${e.message}")
            }
            false
        }
    }
    
    /**
     * Load the latest model from the model directory.
     */
    fun loadLatestModel(): Boolean {
        val latestModelPath = "$modelDirectory/model_latest.bin"
        return loadSpecificModel(latestModelPath)
    }
    
    private fun calculateStateDimension(params: GameParams): Int {
        // For each planet we store: owner (3 values one-hot), number of ships,
        // growth rate, position (x,y), and whether it has an available transporter
        return params.numPlanets * 8
    }
    
    private fun calculateActionDimension(params: GameParams): Int {
        // Action space is source planet * target planet
        // We'll handle the ship count separately for simplicity
        return params.numPlanets * params.numPlanets
    }
    
    private fun initializeNewModel(stateDim: Int, actionDim: Int) {
        qNetwork = DummyQNetwork(stateDim, actionDim)
        targetNetwork = DummyQNetwork(stateDim, actionDim)
        optimizer = Optimizer(qNetwork, learningRate)
        
        // Initialize target network with same weights as Q-network
        targetNetwork.copyFrom(qNetwork)
    }
    
    private fun loadModel(path: String) {
        // Implementation would depend on the specific ML framework used
        // This would load the model weights from disk
        qNetwork.load(path)
        targetNetwork.copyFrom(qNetwork)
    }
    
    private fun saveModel(path: String) {
        // Implementation would depend on the specific ML framework used
        // This would save the model weights to disk
        qNetwork.save(path)
    }
    
    private fun learn() {
        // Sample batch from replay memory
        val batch = replayMemory.sample(batchSize)
        
        // Calculate target Q-values
        val targetsList = batch.map { (state, action, reward, nextState, isDone) ->
            if (isDone) {
                reward
            } else {
                // Q-learning update rule: Q(s,a) = r + γ * max_a' Q(s',a')
                reward + discountFactor * targetNetwork.getMaxQ(nextState)
            }
        }
        
        // Convert List<Double> to FloatArray
        val targetsArray = FloatArray(targetsList.size) { i -> targetsList[i].toFloat() }
        
        // Update Q-network
        optimizer.update(batch.map { it.state }, batch.map { it.action }, targetsArray)
        
        // Update target network periodically
        if (stepCount % targetNetworkUpdateFrequency == 0) {
            targetNetwork.copyFrom(qNetwork)
            logger.log("Target network updated at step $stepCount")
        }
    }
    
    private fun calculateReward(gameState: GameState): Double {
        // Calculate immediate reward based on current game state
        val myShips = gameState.planets.filter { it.owner == player }.sumOf { it.nShips }
        val enemyShips = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.nShips }
        val myPlanets = gameState.planets.count { it.owner == player }
        val enemyPlanets = gameState.planets.count { it.owner == player.opponent() }
        
        // Reward is based on relative advantage in ships and planets
        val shipAdvantage = myShips - enemyShips
        val planetAdvantage = (myPlanets - enemyPlanets) * 10.0
        
        return (shipAdvantage + planetAdvantage) / 100.0
    }
    
    private fun calculateFinalReward(finalState: GameState): Double {
        // Calculate final reward based on game outcome
        return when (determineWinner(finalState)) {
            player -> 10.0          // Win
            player.opponent() -> -10.0  // Loss
            else -> 0.0            // Draw
        }
    }
    
    private fun determineWinner(gameState: GameState): Player {
        val myShips = gameState.planets.filter { it.owner == player }.sumOf { it.nShips }
        val enemyShips = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.nShips }
        
        return when {
            myShips > enemyShips -> player
            enemyShips > myShips -> player.opponent()
            else -> Player.Neutral
        }
    }
    
    override fun getAgentType(): String {
        return "RLAgent-DQN - By Abuzar"
    }
} 