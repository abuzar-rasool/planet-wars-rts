package games.planetwars.agents.rl

import games.planetwars.core.GameState
import games.planetwars.core.Player

/**
 * Encodes a game state into a numerical feature vector for use by the RL agent.
 */
class StateEncoder(private val numPlanets: Int) {
    
    /**
     * Encode the game state into a feature vector.
     * 
     * @param gameState The current game state.
     * @param player The player perspective to encode from.
     * @return FloatArray containing the encoded state vector.
     */
    fun encode(gameState: GameState, player: Player): FloatArray {
        // Create a state vector large enough to hold all features
        // Per planet: owner (3 values), ships, growth rate, position (x,y), has transporter
        val stateVector = FloatArray(numPlanets * 8)
        
        // Process each planet
        gameState.planets.forEachIndexed { index, planet ->
            if (index >= numPlanets) return@forEachIndexed // Safety check
            
            val baseIndex = index * 8
            
            // Encode owner as one-hot (player, opponent, neutral)
            when (planet.owner) {
                player -> {
                    stateVector[baseIndex] = 1f
                    stateVector[baseIndex + 1] = 0f
                    stateVector[baseIndex + 2] = 0f
                }
                player.opponent() -> {
                    stateVector[baseIndex] = 0f
                    stateVector[baseIndex + 1] = 1f
                    stateVector[baseIndex + 2] = 0f
                }
                else -> { // Neutral
                    stateVector[baseIndex] = 0f
                    stateVector[baseIndex + 1] = 0f
                    stateVector[baseIndex + 2] = 1f
                }
            }
            
            // Encode normalized ship count (divide by 100 to normalize scale)
            stateVector[baseIndex + 3] = (planet.nShips / 100.0).toFloat()
            
            // Encode growth rate (normalized)
            stateVector[baseIndex + 4] = planet.growthRate.toFloat() / 5.0f
            
            // Encode position (normalized to [0,1] range)
            stateVector[baseIndex + 5] = planet.position.x.toFloat() / 1000.0f
            stateVector[baseIndex + 6] = planet.position.y.toFloat() / 1000.0f
            
            // Encode if transporter is available (1 if available, 0 if not)
            stateVector[baseIndex + 7] = if (planet.transporter == null) 1f else 0f
        }
        
        return stateVector
    }
} 