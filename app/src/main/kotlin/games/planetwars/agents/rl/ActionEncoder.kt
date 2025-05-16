package games.planetwars.agents.rl

import games.planetwars.agents.Action
import games.planetwars.core.GameState
import games.planetwars.core.Player

/**
 * Handles encoding and decoding of actions between the RL agent's representation
 * and the game's Action class.
 */
class ActionEncoder(private val numPlanets: Int) {
    
    /**
     * Get the total size of the action space.
     * 
     * @return The number of possible actions.
     */
    fun getActionSpaceSize(): Int {
        return numPlanets * numPlanets
    }
    
    /**
     * Decode an action index into a valid game action.
     * 
     * @param actionIndex The index of the action in the action space.
     * @param gameState The current game state.
     * @param player The player taking the action.
     * @return A valid Action for the game.
     */
    fun decode(actionIndex: Int, gameState: GameState, player: Player): Action {
        // Decode the action index into source and target planets
        val sourcePlanetIndex = actionIndex / numPlanets
        val targetPlanetIndex = actionIndex % numPlanets
        
        // Check for valid source planet (must be owned by player and have a free transporter)
        val validSources = gameState.planets
            .filter { it.owner == player && it.transporter == null }
            .sortedBy { it.id }
        
        // Check for valid target planet (not owned by player)
        val validTargets = gameState.planets
            .filter { it.owner != player }
            .sortedBy { it.id }
        
        // If no valid source or target planets, return DoNothing
        if (validSources.isEmpty() || validTargets.isEmpty()) {
            return Action.DO_NOTHING
        }
        
        // Map the indices to actual planets
        val sourcePlanet = if (sourcePlanetIndex < validSources.size) {
            validSources[sourcePlanetIndex % validSources.size]
        } else {
            validSources[0]
        }
        
        val targetPlanet = if (targetPlanetIndex < validTargets.size) {
            validTargets[targetPlanetIndex % validTargets.size]
        } else {
            validTargets[0]
        }
        
        // Determine number of ships to send (50% of available ships)
        val numShips = sourcePlanet.nShips * 0.5
        
        // Create the action
        return Action(
            playerId = player,
            sourcePlanetId = sourcePlanet.id,
            destinationPlanetId = targetPlanet.id,
            numShips = numShips
        )
    }
    
    /**
     * Encode a game action into an action index.
     * This is useful for supervised learning from demonstrations.
     * 
     * @param action The game action.
     * @return The index of the action in the action space.
     */
    fun encode(action: Action, gameState: GameState): Int {
        // If it's a DO_NOTHING action, return a special index
        if (action == Action.DO_NOTHING) {
            return 0
        }
        
        // Find the source and target planet indices
        val sourcePlanetIndex = gameState.planets.indexOfFirst { it.id == action.sourcePlanetId }
        val targetPlanetIndex = gameState.planets.indexOfFirst { it.id == action.destinationPlanetId }
        
        // Calculate action index
        return if (sourcePlanetIndex >= 0 && targetPlanetIndex >= 0) {
            sourcePlanetIndex * numPlanets + targetPlanetIndex
        } else {
            0 // Default to first action if planets not found
        }
    }
} 