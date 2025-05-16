package games.planetwars.agents.rl

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import kotlin.random.Random

/**
 * A data class representing a single transition in the replay memory.
 */
data class Transition(
    val state: FloatArray,
    val action: Int,
    val reward: Double,
    val nextState: FloatArray,
    val isDone: Boolean
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Transition) return false

        if (!state.contentEquals(other.state)) return false
        if (action != other.action) return false
        if (reward != other.reward) return false
        if (!nextState.contentEquals(other.nextState)) return false
        if (isDone != other.isDone) return false

        return true
    }

    override fun hashCode(): Int {
        var result = state.contentHashCode()
        result = 31 * result + action
        result = 31 * result + reward.hashCode()
        result = 31 * result + nextState.contentHashCode()
        result = 31 * result + isDone.hashCode()
        return result
    }
}

/**
 * Experience replay memory for reinforcement learning.
 * Stores state transitions and provides random sampling for training.
 */
class ReplayMemory(
    private val capacity: Int,
    private val savePath: String = "models/rl/replay_memory.bin"
) : Serializable {

    private val memory = ArrayList<Transition>(capacity)
    private var position = 0

    /**
     * Add a new transition to the replay memory.
     */
    fun add(state: FloatArray, action: Int, reward: Double, nextState: FloatArray, isDone: Boolean) {
        val transition = Transition(state, action, reward, nextState, isDone)
        
        if (memory.size < capacity) {
            memory.add(transition)
        } else {
            memory[position] = transition
        }
        
        position = (position + 1) % capacity
    }

    /**
     * Sample a batch of transitions randomly from the replay memory.
     */
    fun sample(batchSize: Int): List<Transition> {
        val actualBatchSize = minOf(batchSize, memory.size)
        return (0 until actualBatchSize).map {
            memory[Random.nextInt(memory.size)]
        }
    }

    /**
     * Get the current size of the replay memory.
     */
    fun size(): Int = memory.size

    /**
     * Save the replay memory to disk.
     */
    fun save() {
        try {
            val directory = File(savePath.substringBeforeLast("/"))
            if (!directory.exists()) {
                directory.mkdirs()
            }
            
            ObjectOutputStream(File(savePath).outputStream()).use { out ->
                out.writeObject(this)
            }
        } catch (e: Exception) {
            println("Failed to save replay memory: ${e.message}")
        }
    }

    /**
     * Load replay memory from disk.
     */
    fun load(): Boolean {
        return try {
            val file = File(savePath)
            if (!file.exists()) {
                return false
            }
            
            val loaded = ObjectInputStream(file.inputStream()).use { it.readObject() } as ReplayMemory
            
            memory.clear()
            memory.addAll(loaded.memory)
            position = loaded.position
            
            true
        } catch (e: Exception) {
            println("Failed to load replay memory: ${e.message}")
            false
        }
    }

    companion object {
        /**
         * Create a new ReplayMemory, optionally loading from disk if available.
         */
        fun create(capacity: Int, savePath: String = "models/rl/replay_memory.bin"): ReplayMemory {
            val memory = ReplayMemory(capacity, savePath)
            memory.load() // Try to load existing memory
            return memory
        }
    }
} 