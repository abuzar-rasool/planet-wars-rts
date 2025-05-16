package games.planetwars.agents.mcts

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.*
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Monte Carlo Tree Search agent for Planet Wars
 * This agent uses MCTS with adaptive simulation depth and opponent modeling
 */
class MCTSAgent(
    private val numIterations: Int = 2000,
    private val explorationConstant: Double = 1.414,
    private val maxSimDepth: Int = 20,
    private val adaptiveDepthRate: Double = 0.5,
    private val discountFactor: Double = 0.95,
    private val opponentModelWeight: Double = 0.3,
    private val useProgressiveWidening: Boolean = true,
    private val progressiveWideningBase: Double = 0.5,
    private val progressiveWideningExponent: Double = 0.5,
    private val useTranspositionTable: Boolean = true,
    private val useEarlyGameGrowthStrategy: Boolean = true,
    private val smartTargetSelection: Boolean = true,
    private val useFirstPlayUrgency: Boolean = true,
    private val firstPlayUrgencyValue: Double = 10.0,
    private val timeLimitMillis: Long = 9 // Reduced time limit to ensure we stay within 10ms
) : PlanetWarsPlayer() {

    // Transposition table to cache evaluated states
    private val transpositionTable = mutableMapOf<Int, MCTSNode>()
    
    // Track opponent actions for modeling
    private val opponentActionHistory = mutableListOf<Action>()
    private var lastGameState: GameState? = null
    private var currentInitialStepDepth = 1
    private var gameStage = GameStage.EARLY
    
    enum class GameStage { EARLY, MID, LATE }
    
    override fun getAgentType(): String {
        return "MCTS-Agent-$numIterations-$explorationConstant-$maxSimDepth - By Abuzar"
    }

    override fun getAction(gameState: GameState): Action {
        val startTime = System.currentTimeMillis()
        
        // Update opponent model if we have a previous game state
        lastGameState?.let { 
            updateOpponentModel(it, gameState)
        }
        lastGameState = gameState.deepCopy()
        
        // Determine game stage for strategic adjustments
        updateGameStage(gameState)
        
        // Adaptive depth adjustment based on game progress
        val gameProgress = gameState.gameTick.toDouble() / params.maxTicks
        currentInitialStepDepth = (1 + (gameProgress * maxSimDepth * adaptiveDepthRate)).toInt().coerceAtMost(3) // Reduced max depth
        
        // Create root node
        val rootNode = MCTSNode(gameState.deepCopy(), player, null, null)
        
        // Run Monte Carlo Tree Search with time limit
        var completedIterations = 0
        val timeCheckInterval = 5 // Check time more frequently to stay within limit
        val earlyStopMargin = 3 // Stop early to account for remaining processing
        
        for (i in 0 until numIterations) {
            // Check time limit more frequently to avoid exceeding it
            if (i % timeCheckInterval == 0 && System.currentTimeMillis() - startTime > timeLimitMillis - earlyStopMargin) {
                break
            }
            
            val selectedNode = select(rootNode)
            val expandedNode = if (selectedNode.isFullyExpanded()) selectedNode else expand(selectedNode)
            val simulationResult = simulate(expandedNode)
            backpropagate(expandedNode, simulationResult)
            completedIterations++
        }
        
        // Log performance info
        val elapsedTime = System.currentTimeMillis() - startTime
        if (completedIterations < numIterations) {
            println("MCTS early stop: $completedIterations iterations in ${elapsedTime}ms (time limit: ${timeLimitMillis}ms)")
        }
        
        // First, check if there's a high-value enemy planet we can attack
        val emergencyAction = getEmergencyAction(gameState)
        if (emergencyAction != null) {
            return emergencyAction
        }
        
        // For early game, prefer actions that maximize growth rate
        if (useEarlyGameGrowthStrategy && gameStage == GameStage.EARLY) {
            val bestGrowthAction = getBestGrowthAction(rootNode, gameState)
            if (bestGrowthAction != null) {
                return bestGrowthAction
            }
        }
        
        // Select best action from root based on exploitation only
        val bestChild = getBestChild(rootNode, 0.0)
        return bestChild?.actionFromParent ?: Action.doNothing()
    }

    // Check for emergency situations where we need to act immediately
    private fun getEmergencyAction(gameState: GameState): Action? {
        val enemyPlanets = gameState.planets.filter { it.owner == player.opponent() }
        val myPlanets = gameState.planets.filter { it.owner == player && it.transporter == null && it.nShips > 15 }
        
        // Enhanced vulnerability detection
        val vulnerableEnemyPlanet = enemyPlanets.minByOrNull { 
            it.nShips + (it.growthRate * 5)  // Consider growth potential
        }
        
        if (vulnerableEnemyPlanet != null && vulnerableEnemyPlanet.nShips < 10) {
            val attackerPlanet = myPlanets
                .filter { it.nShips > vulnerableEnemyPlanet.nShips * 2.0 }
                .minByOrNull { 
                    it.position.distance(vulnerableEnemyPlanet.position) * 
                    (1 - (it.growthRate / params.maxGrowthRate))
                }
            
            if (attackerPlanet != null) {
                val shipCount = (vulnerableEnemyPlanet.nShips * 2.0)
                    .coerceAtMost(attackerPlanet.nShips * 0.75)
                return Action(player, attackerPlanet.id, vulnerableEnemyPlanet.id, shipCount)
            }
        }
        
        // Counter strategy specifically for Careful Random: aggressively target high growth neutrals
        if (gameStage == GameStage.EARLY) {
            val highGrowthNeutrals = gameState.planets.filter { 
                it.owner == Player.Neutral && 
                it.growthRate >= params.maxGrowthRate * 0.8 && // Increased threshold to target highest growth
                it.nShips < 25 // Increased ship threshold to capture more valuable planets
            }.sortedByDescending { it.growthRate }
            
            if (highGrowthNeutrals.isNotEmpty()) {
                val bestNeutral = highGrowthNeutrals.first()
                val closestPlanet = myPlanets
                    .filter { it.nShips > bestNeutral.nShips * 1.2 } // Reduced threshold to be more aggressive
                    .minByOrNull { 
                        it.position.distance(bestNeutral.position) * 
                        (1 - (it.growthRate / params.maxGrowthRate)) 
                    }
                
                if (closestPlanet != null) {
                    val shipCount = (bestNeutral.nShips + 5.0)
                        .coerceAtMost(closestPlanet.nShips * 0.9) // Send more ships
                    return Action(player, closestPlanet.id, bestNeutral.id, shipCount)
                }
            }
        }
        
        return null
    }

    private fun updateGameStage(gameState: GameState) {
        val progress = gameState.gameTick.toDouble() / params.maxTicks
        gameStage = when {
            progress < 0.3 -> GameStage.EARLY // Extended early game phase
            progress < 0.7 -> GameStage.MID
            else -> GameStage.LATE
        }
    }

    private fun select(node: MCTSNode): MCTSNode {
        if (node.childNodes.isEmpty() || !node.isFullyExpanded()) {
            return node
        }
        
        var current = node
        while (current.childNodes.isNotEmpty() && current.isFullyExpanded()) {
            current = getBestChild(current, explorationConstant) ?: break
        }
        
        return current
    }

    private fun expand(node: MCTSNode): MCTSNode {
        // Get possible actions for the node's player - reduced max actions for speed
        val possibleActions = getPossibleActions(node.state, node.playerToMove, 5) 
        
        // Apply progressive widening if enabled
        val numActions = if (useProgressiveWidening) {
            val numVisits = node.numVisits.toDouble()
            val k = progressiveWideningBase * numVisits.pow(progressiveWideningExponent)
            minOf(possibleActions.size, k.toInt().coerceAtLeast(1))
        } else {
            possibleActions.size
        }
        
        // Filter out actions already expanded
        val unexpandedActions = possibleActions.take(numActions)
            .filter { action -> 
                !node.childNodes.any { it.actionFromParent == action }
            }
        
        if (unexpandedActions.isEmpty()) {
            return node
        }
        
        // Choose an action to expand - prioritize promising actions in early game
        val actionToExpand = if (gameStage == GameStage.EARLY && useEarlyGameGrowthStrategy && node.playerToMove == player) {
            unexpandedActions.maxByOrNull { action ->
                if (action == Action.DO_NOTHING) return@maxByOrNull 0.0
                val targetId = action.destinationPlanetId
                val target = node.state.planets[targetId]
                if (target.owner == Player.Neutral) target.growthRate * 2.0 else 0.0 // Double weight for growth
            } ?: unexpandedActions.first()
        } else {
            unexpandedActions.first()
        }
        
        // Apply action to create new state
        val nextState = node.state.deepCopy()
        val forwardModel = ForwardModel(nextState, params)
        val actions = mapOf(node.playerToMove to actionToExpand)
        forwardModel.step(actions)
        
        // Create and add child node
        val childNode = MCTSNode(
            nextState,
            node.playerToMove.opponent(),
            node,
            actionToExpand
        )
        
        node.childNodes.add(childNode)
        
        // Use transposition table if enabled
        if (useTranspositionTable) {
            val stateHash = nextState.hashCode()
            transpositionTable[stateHash] = childNode
        }
        
        return childNode
    }

    private fun simulate(node: MCTSNode): Double {
        // Ultra fast simulation with reduced depth
        val effectiveMaxDepth = minOf(currentInitialStepDepth + 2, 5) // Reduced max depth for speed
        
        val simulationState = node.state.deepCopy()
        val forwardModel = ForwardModel(simulationState, params)
        var currentPlayer = node.playerToMove
        var depth = 0
        
        while (depth < effectiveMaxDepth && !forwardModel.isTerminal()) {
            // Use ultraFastSimulationPolicy for all players to improve speed
            val action = ultraFastSimulationPolicy(simulationState, currentPlayer)
            
            val actions = mapOf(currentPlayer to action)
            forwardModel.step(actions)
            currentPlayer = currentPlayer.opponent()
            depth++
        }
        
        // Evaluate final state with stage-specific evaluation - simplified for speed
        return quickEvaluateState(simulationState, player, gameStage) * discountFactor.pow(depth)
    }
    
    // New ultra-fast simulation policy that prioritizes speed over sophistication
    private fun ultraFastSimulationPolicy(state: GameState, currentPlayer: Player): Action {
        // Get planets owned by current player with ships - limit to 1 for speed
        val myPlanets = state.planets.filter { 
            it.owner == currentPlayer && it.nShips > 5 && it.transporter == null
        }
        
        if (myPlanets.isEmpty()) {
            return Action.doNothing()
        }
        
        // Just pick the planet with most ships
        val source = myPlanets.maxByOrNull { it.nShips } ?: return Action.doNothing()
        
        // Find any targets - simplify by checking only a few targets
        val enemyPlanets = state.planets.filter { it.owner == currentPlayer.opponent() }.take(2)
        val neutralPlanets = state.planets.filter { it.owner == Player.Neutral }.take(2)
        
        if (enemyPlanets.isEmpty() && neutralPlanets.isEmpty()) {
            return Action.doNothing()
        }
        
        // Simple target selection - prefer high growth in early game
        val target = when {
            gameStage == GameStage.EARLY && neutralPlanets.isNotEmpty() -> {
                neutralPlanets.maxByOrNull { it.growthRate } ?: neutralPlanets.first()
            }
            enemyPlanets.isNotEmpty() -> {
                enemyPlanets.minByOrNull { it.nShips } ?: enemyPlanets.first()
            }
            else -> {
                neutralPlanets.first()
            }
        }
        
        // Simple ship count calculation
        val shipCount = if (target.owner == Player.Neutral) {
            (target.nShips + 3.0).coerceAtMost(source.nShips * 0.7)
        } else {
            (target.nShips * 1.5).coerceAtMost(source.nShips * 0.8)
        }
        
        return Action(currentPlayer, source.id, target.id, shipCount)
    }

    // Fast simulation policy for better performance
    private fun fastSimulationPolicy(state: GameState, currentPlayer: Player): Action {
        // Get planets owned by current player with ships
        val myPlanets = state.planets.filter { 
            it.owner == currentPlayer && it.nShips > 5 && it.transporter == null
        }
        
        if (myPlanets.isEmpty()) {
            return Action.doNothing()
        }
        
        // Prefer highest ship count planet as source
        val source = myPlanets.maxByOrNull { it.nShips } ?: myPlanets.random()
        
        // Find potential targets
        val enemyPlanets = state.planets.filter { it.owner == currentPlayer.opponent() }
        val neutralPlanets = state.planets.filter { it.owner == Player.Neutral }
        
        if (enemyPlanets.isEmpty() && neutralPlanets.isEmpty()) {
            return Action.doNothing()
        }
        
        // Improved targeting heuristic
        val target = when {
            // Early game: target high growth neutral planets
            gameStage == GameStage.EARLY && neutralPlanets.isNotEmpty() -> {
                val highGrowthPlanets = neutralPlanets.filter { it.growthRate >= params.maxGrowthRate * 0.7 }
                if (highGrowthPlanets.isNotEmpty()) {
                    highGrowthPlanets.maxByOrNull { it.growthRate } ?: neutralPlanets.random()
                } else {
                    neutralPlanets.random()
                }
            }
            // Attack vulnerable enemy if we have significantly more ships
            enemyPlanets.isNotEmpty() && source.nShips > 20 -> {
                val weakEnemies = enemyPlanets.filter { it.nShips < source.nShips * 0.5 }
                if (weakEnemies.isNotEmpty()) {
                    weakEnemies.minByOrNull { it.nShips } ?: enemyPlanets.random()
                } else {
                    enemyPlanets.random()
                }
            }
            // Otherwise any neutral planet with preference for higher growth
            neutralPlanets.isNotEmpty() -> {
                neutralPlanets.maxByOrNull { it.growthRate } ?: neutralPlanets.random()
            }
            // Default to any enemy planet
            else -> {
                enemyPlanets.random()
            }
        }
        
        // Calculate optimal ship count based on target type
        val shipCount = when (target.owner) {
            Player.Neutral -> {
                (target.nShips + 3.0).coerceAtMost(source.nShips * 0.7)
            }
            else -> { // Enemy planet
                val distance = source.position.distance(target.position)
                val travelTime = distance / params.transporterSpeed
                val projectedShips = target.nShips + (target.growthRate * travelTime)
                (projectedShips * 1.3).coerceAtMost(source.nShips * 0.8)
            }
        }
        
        return Action(currentPlayer, source.id, target.id, shipCount)
    }

    private fun backpropagate(node: MCTSNode, reward: Double) {
        var current: MCTSNode? = node
        while (current != null) {
            current.numVisits++
            // Update value based on player perspective
            if (current.playerToMove == player.opponent()) {
                current.totalReward += reward
            } else {
                current.totalReward -= reward  // Negate for opponent's perspective
            }
            current = current.parent
        }
    }

    private fun getBestChild(node: MCTSNode, explorationValue: Double): MCTSNode? {
        if (node.childNodes.isEmpty()) {
            return null
        }
        
        return node.childNodes.maxByOrNull { child ->
            if (child.numVisits == 0 && useFirstPlayUrgency) {
                // First-play urgency for unexplored nodes
                return@maxByOrNull firstPlayUrgencyValue
            }
            
            val exploitation = child.totalReward / child.numVisits.toDouble()
            val exploration = sqrt(2.0 * ln(node.numVisits.toDouble()) / child.numVisits.toDouble())
            exploitation + explorationValue * exploration
        }
    }

    // Get best action for maximizing growth rate in early game
    private fun getBestGrowthAction(rootNode: MCTSNode, gameState: GameState): Action? {
        // Only apply this in early game with sufficient visits
        if (gameStage != GameStage.EARLY || rootNode.numVisits < numIterations * 0.2) {
            return null
        }
        
        // Find children with neutral planet captures that have good growth rates
        val growthCapturingChildren = rootNode.childNodes.filter { child ->
            val action = child.actionFromParent ?: return@filter false
            if (action == Action.DO_NOTHING) return@filter false
            
            val targetId = action.destinationPlanetId
            val target = gameState.planets[targetId]
            val goodGrowthRate = target.growthRate >= params.maxGrowthRate * 0.6
            
            // Consider only neutral planets with good growth rate
            target.owner == Player.Neutral && goodGrowthRate
        }
        
        // If we have growth-capturing actions with reasonable visit counts, choose the best
        if (growthCapturingChildren.isNotEmpty()) {
            val bestGrowthChild = growthCapturingChildren.maxByOrNull { child ->
                val action = child.actionFromParent!!
                val targetId = action.destinationPlanetId
                val target = gameState.planets[targetId]
                
                // Weight by both UCB score and growth rate - prioritize growth rate even more
                val exploitation = child.totalReward / child.numVisits.toDouble()
                val exploration = sqrt(2.0 * ln(rootNode.numVisits.toDouble()) / child.numVisits.toDouble())
                val ucbScore = exploitation + explorationConstant * exploration
                
                // Combine UCB with growth rate potential - give more weight to growth
                ucbScore * (1.0 + target.growthRate / params.maxGrowthRate * 3.0) // Increased weight for growth
            }
            
            return bestGrowthChild?.actionFromParent
        }
        
        return null
    }

    // Specific policy to counter Careful Random agent
    private fun targetHighGrowthPlanets(state: GameState, currentPlayer: Player): Action {
        val sourcePlanets = state.planets.filter { 
            it.owner == currentPlayer && it.transporter == null && it.nShips > 5
        }
        
        if (sourcePlanets.isEmpty()) {
            return Action.doNothing()
        }
        
        // Focus on high growth neutral planets - increased threshold
        val highGrowthNeutrals = state.planets.filter { 
            it.owner == Player.Neutral && it.growthRate >= params.maxGrowthRate * 0.8
        }.sortedByDescending { it.growthRate }
        
        if (highGrowthNeutrals.isNotEmpty()) {
            val source = sourcePlanets.maxByOrNull { it.nShips } ?: sourcePlanets.first()
            val target = highGrowthNeutrals.first()
            val shipCount = (target.nShips + 5.0).coerceAtMost(source.nShips * 0.7)
            return Action(currentPlayer, source.id, target.id, shipCount)
        }
        
        // If no good neutral planets, try to attack enemy
        val enemyPlanets = state.planets.filter { it.owner != currentPlayer && it.owner != Player.Neutral }
        if (enemyPlanets.isNotEmpty()) {
            val source = sourcePlanets.maxByOrNull { it.nShips } ?: sourcePlanets.first()
            val weakestEnemy = enemyPlanets.minByOrNull { it.nShips } ?: enemyPlanets.first()
            val shipCount = (weakestEnemy.nShips * 1.5).coerceAtMost(source.nShips * 0.8)
            return Action(currentPlayer, source.id, weakestEnemy.id, shipCount)
        }
        
        return Action.doNothing()
    }

    private fun updateOpponentModel(previousState: GameState, currentState: GameState) {
        // Identify the action taken by the opponent between these states
        if (previousState.gameTick == currentState.gameTick - 1) {
            val opponentAction = inferOpponentAction(currentState)
            opponentAction?.let { opponentActionHistory.add(it) }
            
            // Limit history size to avoid memory issues
            if (opponentActionHistory.size > 10) { // Reduced history size
                opponentActionHistory.removeAt(0)
            }
        }
    }
    
    private fun inferOpponentAction(currentState: GameState): Action? {
        val opponent = player.opponent()
        
        // Look for transporter creation
        for (planet in currentState.planets) {
            val transporter = planet.transporter
            if (transporter != null && transporter.owner == opponent) {
                // A new transporter was created by the opponent
                val sourceId = planet.id
                val destinationId = transporter.destinationIndex
                val numShips = transporter.nShips
                
                return Action(opponent, sourceId, destinationId, numShips)
            }
        }
        
        // No transporter created - opponent did nothing
        return Action.doNothing()
    }

    private fun adaptActionToCurrentState(pastAction: Action, state: GameState, player: Player): Action {
        // Simplified implementation for performance
        if (pastAction == Action.DO_NOTHING) {
            return Action.doNothing()
        }
        
        // Check if the source planet is still owned by the player
        val sourcePlanets = state.planets.filter { 
            it.owner == player && it.transporter == null && it.nShips > 0
        }
        
        if (sourcePlanets.isEmpty()) {
            return Action.doNothing()
        }
        
        // Use a random source planet for simplicity
        val source = sourcePlanets.random()
        
        // Find a target planet not owned by the player
        val targetPlanets = state.planets.filter { it.owner != player }
        if (targetPlanets.isEmpty()) {
            return Action.doNothing()
        }
        
        // Pick a random target
        val target = targetPlanets.random()
        
        // Send a portion of ships
        val shipCount = source.nShips * 0.6
        
        return Action(player, source.id, target.id, shipCount)
    }
    
    // Get possible actions, potentially limited to a max count for performance
    private fun getPossibleActions(state: GameState, currentPlayer: Player, maxActions: Int? = null): List<Action> {
        val sourcePlanets = state.planets.filter { 
            it.owner == currentPlayer && it.transporter == null && it.nShips > 0
        }
        
        if (sourcePlanets.isEmpty()) {
            return listOf(Action.doNothing())
        }
        
        val targetPlanets = state.planets.filter { it.owner != currentPlayer }
        if (targetPlanets.isEmpty()) {
            return listOf(Action.doNothing())
        }
        
        // Generate actions
        val actions = mutableListOf<Action>()
        
        // Always include "do nothing" action
        actions.add(Action.doNothing())
        
        // For performance, limit source planets if there are too many
        val limitedSources = if (maxActions != null && sourcePlanets.size > 2) { // Reduced to 2
            // Use planets with most ships
            sourcePlanets.sortedByDescending { it.nShips }.take(2)
        } else {
            sourcePlanets
        }
        
        // For each source planet, generate actions to all target planets
        for (source in limitedSources) {
            // For performance, limit target planets if there are too many
            val limitedTargets = if (maxActions != null && targetPlanets.size > 2) { // Reduced to 2
                // In early game, prioritize neutral planets with high growth
                if (gameStage == GameStage.EARLY) {
                    val neutralTargets = targetPlanets
                        .filter { it.owner == Player.Neutral }
                        .sortedByDescending { it.growthRate }
                        .take(2)
                    
                    neutralTargets
                } else {
                    // In mid/late game, prioritize enemy planets
                    val enemyTargets = targetPlanets
                        .filter { it.owner == currentPlayer.opponent() }
                        .take(2)
                    
                    enemyTargets
                }
            } else {
                targetPlanets
            }
            
            for (target in limitedTargets) {
                // Send enough ships to capture, or a percentage of available ships
                val shipCount = when (target.owner) {
                    Player.Neutral -> target.nShips + 1.0
                    else -> target.nShips + 1.0 // Enemy planet
                }.coerceAtMost(source.nShips * 0.9) // Don't send all ships
                
                if (shipCount > 0) {
                    actions.add(Action(currentPlayer, source.id, target.id, shipCount))
                }
            }
        }
        
        // Limit total actions if specified
        return if (maxActions != null && actions.size > maxActions) {
            actions.take(maxActions)
        } else {
            actions
        }
    }

    // Quick evaluation function that's optimized for speed
    private fun quickEvaluateState(state: GameState, evaluationPlayer: Player, gameStage: GameStage): Double {
        val playerPlanets = state.planets.count { it.owner == evaluationPlayer }
        val opponentPlanets = state.planets.count { it.owner == evaluationPlayer.opponent() }
        
        // Growth potential based on owned planets' growth rates
        val playerGrowth = state.planets.filter { it.owner == evaluationPlayer }.sumOf { it.growthRate }
        val opponentGrowth = state.planets.filter { it.owner == evaluationPlayer.opponent() }.sumOf { it.growthRate }
        
        // Adjust weights based on game stage - stronger emphasis on growth in early/mid game
        val (planetWeight, growthWeight) = when (gameStage) {
            GameStage.EARLY -> arrayOf(8.0, 15.0) // Increased growth weight
            GameStage.MID -> arrayOf(12.0, 8.0)
            GameStage.LATE -> arrayOf(6.0, 4.0)
        }
        
        // Super simplified evaluation for speed
        return (playerPlanets - opponentPlanets) * planetWeight +
               (playerGrowth - opponentGrowth) * growthWeight
    }

    private fun evaluateState(state: GameState, evaluationPlayer: Player, gameStage: GameStage): Double {
        val playerShips = state.planets.filter { it.owner == evaluationPlayer }.sumOf { it.nShips }
        val opponentShips = state.planets.filter { it.owner == evaluationPlayer.opponent() }.sumOf { it.nShips }
        val playerPlanets = state.planets.count { it.owner == evaluationPlayer }
        val opponentPlanets = state.planets.count { it.owner == evaluationPlayer.opponent() }
        
        // Growth potential based on owned planets' growth rates
        val playerGrowth = state.planets.filter { it.owner == evaluationPlayer }.sumOf { it.growthRate }
        val opponentGrowth = state.planets.filter { it.owner == evaluationPlayer.opponent() }.sumOf { it.growthRate }
        
        // Strategic value - planets near enemy territory
        val strategicValue = calculateStrategicValue(state, evaluationPlayer)
        
        // Adjust weights based on game stage - stronger emphasis on growth in early/mid game
        val (shipWeight, planetWeight, growthWeight, strategicWeight) = when (gameStage) {
            GameStage.EARLY -> arrayOf(0.5, 8.0, 15.0, 1.0) // Increased growth weight
            GameStage.MID -> arrayOf(1.0, 12.0, 8.0, 2.0)
            GameStage.LATE -> arrayOf(2.0, 6.0, 4.0, 4.0)
        }
        
        // Additional evaluation for game stage-specific strategies
        val stageBonus = when (gameStage) {
            GameStage.EARLY -> {
                // In early game, value controlling high growth planets
                val highGrowthOwned = state.planets.count { 
                    it.owner == evaluationPlayer && it.growthRate >= params.maxGrowthRate * 0.7
                }
                val highGrowthOpponentOwned = state.planets.count { 
                    it.owner == evaluationPlayer.opponent() && it.growthRate >= params.maxGrowthRate * 0.7
                }
                (highGrowthOwned - highGrowthOpponentOwned) * 35.0 // Increased bonus
            }
            GameStage.MID -> {
                // In mid game, value planet count advantage
                (playerPlanets - opponentPlanets) * 15.0
            }
            GameStage.LATE -> {
                // In late game, value ship advantage more heavily
                (playerShips - opponentShips) * 0.5
            }
        }
        
        // Add bonus for high-growth planet control
        val highGrowthBonus = state.planets.count { 
            it.owner == evaluationPlayer && it.growthRate >= params.maxGrowthRate * 0.8
        } * 10.0 // Increased bonus
        
        // Combine different evaluation components with stage-specific weights
        return (playerShips - opponentShips) * shipWeight + 
               (playerPlanets - opponentPlanets) * planetWeight +
               (playerGrowth - opponentGrowth) * growthWeight +
               strategicValue * strategicWeight +
               stageBonus +
               highGrowthBonus
    }
    
    private fun calculateStrategicValue(state: GameState, evaluationPlayer: Player): Double {
        // Simplified for performance
        var strategicValue = 0.0
        val myPlanets = state.planets.filter { it.owner == evaluationPlayer }
        val opponentPlanets = state.planets.filter { it.owner == evaluationPlayer.opponent() }
        
        if (myPlanets.isEmpty() || opponentPlanets.isEmpty()) {
            return 0.0
        }
        
        // Count planets that are close to enemies
        for (myPlanet in myPlanets) {
            for (enemyPlanet in opponentPlanets) {
                val distance = myPlanet.position.distance(enemyPlanet.position)
                
                // Closer planets are more strategic
                if (distance < params.width / 4) {
                    strategicValue += 1.0
                    break // Count each planet only once for speed
                }
            }
        }
        
        return strategicValue
    }
}

/**
 * Node in the Monte Carlo search tree
 */
class MCTSNode(
    val state: GameState,
    val playerToMove: Player,
    val parent: MCTSNode?,
    val actionFromParent: Action?
) {
    var numVisits: Int = 0
    var totalReward: Double = 0.0
    val childNodes: MutableList<MCTSNode> = mutableListOf()
    
    fun isFullyExpanded(): Boolean {
        // Consider a node fully expanded with fewer children for better performance
        return childNodes.size >= 4 // Reduced from 6 to 4
    }
} 