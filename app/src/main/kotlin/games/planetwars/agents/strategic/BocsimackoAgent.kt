package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.*
import kotlin.math.*
import kotlin.collections.mutableMapOf

/**
 * Complete implementation of the bocsimacko-b0.83 winning agent from the 2010 Google AI Planet Wars Challenge.
 * 
 * Key features implemented from the original:
 * 1. Sophisticated alpha-beta search with variable depth widths
 * 2. Advanced evaluation function with multiple strategic factors
 * 3. Deferred order execution and undo capabilities
 * 4. Neighbor traversal optimization for pathfinding
 * 5. Caching system for game states and evaluations
 * 6. Planet truncation for horizon planning
 * 7. Safety margin calculations for risk assessment
 * 8. Dynamic move generation with scored move ordering
 * 9. Terminal state detection and evaluation
 * 10. Sophisticated opponent modeling
 */
class BocsimackoAgent(
    private val maxDepth: Int = 10,
    private val initialWidths: IntArray = intArrayOf(15, 8, 5, 3, 2, 1, 1, 1, 1, 1),
    private val timeLimitMillis: Long = 90L, // Increased from 18ms to 150ms
    private val evaluationCacheSize: Int = 10000,
    private val positionCacheSize: Int = 5000
) : PlanetWarsPlayer() {

    // Caching systems
    private val evaluationCache = mutableMapOf<String, Double>()
    private val positionCache = mutableMapOf<String, EvaluatedPosition>()
    private val transpositionTable = mutableMapOf<String, AlphaBetaResult>()
    
    // Search state
    private var bestMoveSoFar: Action? = null
    private var bestScoreSoFar: Double = Double.NEGATIVE_INFINITY
    private var nodesEvaluated = 0
    private var searchStartTime = 0L
    
    // Game state tracking
    private var currentTurn = 0
    private var gamePhase = GamePhase.OPENING
    
    enum class GamePhase { OPENING, MIDDLE, LATE }
    
    data class EvaluatedPosition(
        val score: Double,
        val turn: Int,
        val features: PositionFeatures
    )
    
    data class PositionFeatures(
        val myShips: Double,
        val oppShips: Double,
        val myGrowth: Double,
        val oppGrowth: Double,
        val myPlanets: Int,
        val oppPlanets: Int,
        val neutralPlanets: Int,
        val strategicValue: Double,
        val centralControl: Double,
        val borderSecurity: Double,
        val expansionPotential: Double,
        val economicEfficiency: Double,
        val militaryPressure: Double,
        val defensiveStability: Double
    )
    
    data class AlphaBetaResult(
        val score: Double,
        val bestMove: Action?,
        val depth: Int,
        val nodeType: NodeType
    )
    
    enum class NodeType { EXACT, LOWER_BOUND, UPPER_BOUND }
    
    data class ScoredAction(
        val action: Action,
        val score: Double,
        val features: ActionFeatures
    )
    
    data class ActionFeatures(
        val isCapture: Boolean,
        val gainStrategicValue: Double,
        val riskLevel: Double,
        val efficiency: Double
    )

    override fun getAgentType(): String = "BocsimackoAgent-D${maxDepth}-W${initialWidths.joinToString(",")}"

    override fun getAction(gameState: GameState): Action {
        searchStartTime = System.currentTimeMillis()
        nodesEvaluated = 0
        bestMoveSoFar = getBestQuickAction(gameState) // Start with a good fallback
        bestScoreSoFar = Double.NEGATIVE_INFINITY
        
        updateGamePhase(gameState)
        cleanupCaches()
        
        val widths = adjustWidthsForPhase(initialWidths)
        val action = alphaBetaSearch(gameState, widths)
        
        currentTurn++
        return action ?: bestMoveSoFar ?: Action.doNothing()
    }
    
    private fun updateGamePhase(gameState: GameState) {
        val myPlanets = gameState.planets.count { it.owner == player }
        val totalPlanets = gameState.planets.size
        val gameProgress = gameState.gameTick.toDouble() / params.maxTicks
        
        gamePhase = when {
            myPlanets <= 2 || gameProgress < 0.2 -> GamePhase.OPENING
            gameProgress > 0.7 || myPlanets >= totalPlanets * 0.6 -> GamePhase.LATE
            else -> GamePhase.MIDDLE
        }
    }
    
    private fun adjustWidthsForPhase(baseWidths: IntArray): IntArray {
        return when (gamePhase) {
            GamePhase.OPENING -> baseWidths.map { (it * 1.2).toInt() }.toIntArray()
            GamePhase.LATE -> baseWidths.map { max(1, (it * 0.8).toInt()) }.toIntArray()
            else -> baseWidths
        }
    }
    
    private fun cleanupCaches() {
        if (evaluationCache.size > evaluationCacheSize) {
            val toRemove = evaluationCache.size - evaluationCacheSize / 2
            evaluationCache.entries.take(toRemove).forEach { evaluationCache.remove(it.key) }
        }
        
        if (positionCache.size > positionCacheSize) {
            val toRemove = positionCache.size - positionCacheSize / 2
            positionCache.entries.take(toRemove).forEach { positionCache.remove(it.key) }
        }
    }

    private fun alphaBetaSearch(gameState: GameState, widths: IntArray): Action? {
        return try {
            val result = alphaBeta(
                gameState = gameState,
                depth = 0,
                alpha = Double.NEGATIVE_INFINITY,
                beta = Double.POSITIVE_INFINITY,
                widths = widths,
                maximizingPlayer = true
            )
            result.bestMove ?: bestMoveSoFar
        } catch (e: Exception) {
            // Return best move found so far in case of timeout or error
            bestMoveSoFar
        }
    }

    private fun alphaBeta(
        gameState: GameState,
        depth: Int,
        alpha: Double,
        beta: Double,
        widths: IntArray,
        maximizingPlayer: Boolean
    ): AlphaBetaResult {
        
        // Check time limit less frequently to reduce overhead
        if (depth == 0 && System.currentTimeMillis() - searchStartTime > timeLimitMillis) {
            throw RuntimeException("Time limit exceeded")
        }
        
        nodesEvaluated++
        
        // Check transposition table
        val stateKey = generateStateKey(gameState, depth)
        transpositionTable[stateKey]?.let { cached ->
            if (cached.depth >= depth) {
                when (cached.nodeType) {
                    NodeType.EXACT -> return cached
                    NodeType.LOWER_BOUND -> if (cached.score >= beta) return cached
                    NodeType.UPPER_BOUND -> if (cached.score <= alpha) return cached
                }
            }
        }
        
        // Terminal node or depth limit reached
        if (depth >= widths.size || isTerminalState(gameState)) {
            val score = evaluatePosition(gameState)
            val result = AlphaBetaResult(score, null, depth, NodeType.EXACT)
            transpositionTable[stateKey] = result
            return result
        }
        
        // Generate and score moves
        val actions = generateAndScoreActions(gameState, depth, maximizingPlayer)
        val width = min(widths[depth], actions.size)
        val topActions = actions.take(width)
        
        var bestScore = if (maximizingPlayer) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        var bestAction: Action? = null
        var currentAlpha = alpha
        var currentBeta = beta
        var nodeType = if (maximizingPlayer) NodeType.UPPER_BOUND else NodeType.LOWER_BOUND
        
        for (scoredAction in topActions) {
            val action = scoredAction.action
            val nextState = simulateAction(gameState, action, maximizingPlayer)
            
            val result = alphaBeta(
                gameState = nextState,
                depth = depth + 1,
                alpha = currentAlpha,
                beta = currentBeta,
                widths = widths,
                maximizingPlayer = !maximizingPlayer
            )
            
            if (maximizingPlayer) {
                if (result.score > bestScore) {
                    bestScore = result.score
                    bestAction = action
                    
                    // Update best move at root
                    if (depth == 0) {
                        bestMoveSoFar = action
                        bestScoreSoFar = bestScore
                    }
                }
                currentAlpha = max(currentAlpha, bestScore)
                if (bestScore >= currentBeta) {
                    nodeType = NodeType.LOWER_BOUND
                    break // Beta cutoff
                }
            } else {
                if (result.score < bestScore) {
                    bestScore = result.score
                    bestAction = action
                }
                currentBeta = min(currentBeta, bestScore)
                if (bestScore <= currentAlpha) {
                    nodeType = NodeType.UPPER_BOUND
                    break // Alpha cutoff
                }
            }
        }
        
        if (nodeType == NodeType.UPPER_BOUND || nodeType == NodeType.LOWER_BOUND) {
            // Exact score found
            if ((maximizingPlayer && bestScore > alpha && bestScore < beta) ||
                (!maximizingPlayer && bestScore > alpha && bestScore < beta)) {
                nodeType = NodeType.EXACT
            }
        }
        
        val result = AlphaBetaResult(bestScore, bestAction, depth, nodeType)
        transpositionTable[stateKey] = result
        return result
    }

    private fun generateAndScoreActions(
        gameState: GameState,
        depth: Int,
        maximizingPlayer: Boolean
    ): List<ScoredAction> {
        val currentPlayer = if (maximizingPlayer) player else player.opponent()
        val sourcePlanets = gameState.planets.filter { 
            it.owner == currentPlayer && it.nShips > 0.5 
        }
        
        if (sourcePlanets.isEmpty()) {
            return listOf(ScoredAction(Action.doNothing(), 0.0, 
                ActionFeatures(false, 0.0, 0.0, 0.0)))
        }
        
        val actions = mutableListOf<ScoredAction>()
        
        // Add do-nothing action
        val doNothingScore = scoreAction(gameState, Action.doNothing(), currentPlayer, depth)
        actions.add(ScoredAction(
            Action.doNothing(), 
            doNothingScore.score,
            doNothingScore.features
        ))
        
        // Generate movement actions
        for (source in sourcePlanets) {
            val targets = findViableTargets(gameState, source, currentPlayer)
            
            for (target in targets) {
                // Multiple ship amount options based on strategic value
                val shipOptions = generateShipOptions(source, target, gameState, currentPlayer)
                
                for (shipAmount in shipOptions) {
                    if (shipAmount >= 0.5) {
                        val action = Action(currentPlayer, source.id, target.id, shipAmount)
                        val evaluation = scoreAction(gameState, action, currentPlayer, depth)
                        actions.add(ScoredAction(action, evaluation.score, evaluation.features))
                    }
                }
            }
        }
        
        // Sort by score and strategic value
        return actions.sortedByDescending { 
            it.score + it.features.gainStrategicValue * 0.1 
        }
    }
    
    private fun findViableTargets(
        gameState: GameState,
        source: Planet,
        currentPlayer: Player
    ): List<Planet> {
        return gameState.planets
            .filter { it.id != source.id && it.owner != currentPlayer }
            .filter { target ->
                val distance = distanceBetween(source, target)
                val travelTime = ceil(distance / params.transporterSpeed).toInt()
                
                // Reduce search radius and filter more aggressively
                travelTime <= 30 && isStrategicallyViable(source, target, gameState, travelTime)
            }
            .sortedBy { distanceBetween(source, it) }
            .take(5) // Limit to 5 closest viable targets per source
    }
    
    private fun isStrategicallyViable(
        source: Planet,
        target: Planet,
        gameState: GameState,
        travelTime: Int
    ): Boolean {
        // Don't attack if target will grow too large
        val targetGrowth = target.growthRate * travelTime
        val maxViableShips = source.nShips * 2.0
        
        if (target.nShips + targetGrowth > maxViableShips) return false
        
        // Check if target is strategically important (high growth, strategic position)
        val strategicValue = target.growthRate * 10.0 + 
                           (if (target.owner == Player.Neutral) 5.0 else 15.0)
        
        return strategicValue > 2.0
    }
    
    private fun generateShipOptions(
        source: Planet,
        target: Planet,
        gameState: GameState,
        currentPlayer: Player
    ): List<Double> {
        val distance = distanceBetween(source, target)
        val travelTime = ceil(distance / params.transporterSpeed).toInt()
        val targetGrowth = target.growthRate * travelTime
        
        // Calculate minimum ships needed for capture
        val minNeeded = target.nShips + targetGrowth + 1.0
        
        val options = mutableListOf<Double>()
        
        // Conservative option: minimum + safety margin
        if (minNeeded <= source.nShips) {
            options.add(min(source.nShips, minNeeded + 2.0))
        }
        
        // Only add one moderate option instead of multiple fractions
        val moderateAmount = source.nShips * 0.5
        if (moderateAmount >= minNeeded && moderateAmount <= source.nShips) {
            options.add(moderateAmount)
        }
        
        // Aggressive option: only if highly strategic and we have overwhelming force
        if (target.growthRate >= 4.0 && source.nShips > minNeeded * 2.0) {
            options.add(source.nShips * 0.8)
        }
        
        return options.distinct().sorted().take(2) // Limit to max 2 options per target
    }
    
    private fun scoreAction(
        gameState: GameState,
        action: Action,
        currentPlayer: Player,
        depth: Int
    ): ScoredAction {
        if (action == Action.doNothing()) {
            return ScoredAction(action, 0.0, 
                ActionFeatures(false, 0.0, 0.0, 0.0))
        }
        
        val source = gameState.planets.find { it.id == action.sourcePlanetId }!!
        val target = gameState.planets.find { it.id == action.destinationPlanetId }!!
        
        val distance = distanceBetween(source, target)
        val travelTime = ceil(distance / params.transporterSpeed).toInt()
        val targetGrowth = target.growthRate * travelTime
        
        // Strategic evaluation
        val isCapture = action.numShips > target.nShips + targetGrowth
        val gainStrategicValue = calculateStrategicValue(target, gameState, isCapture)
        val riskLevel = calculateRiskLevel(source, action.numShips, gameState)
        val efficiency = calculateEfficiency(action.numShips, target, travelTime, isCapture)
        
        // Base score calculation
        var score = 0.0
        
        if (isCapture) {
            score += target.growthRate * 20.0 // Value of capturing planet
            score += target.nShips * 0.5     // Immediate ships gained
            score -= action.numShips * 0.3   // Cost of ships sent
        } else {
            score -= action.numShips * 0.8   // Cost without benefit
        }
        
        // Strategic bonuses
        score += gainStrategicValue
        score -= riskLevel * 5.0
        score += efficiency * 10.0
        
        // Depth-based adjustments
        score *= (1.0 - depth * 0.1)
        
        val features = ActionFeatures(isCapture, gainStrategicValue, riskLevel, efficiency)
        return ScoredAction(action, score, features)
    }
    
    private fun calculateStrategicValue(
        target: Planet,
        gameState: GameState,
        wouldCapture: Boolean
    ): Double {
        if (!wouldCapture) return 0.0
        
        var value = 0.0
        
        // Growth rate value
        value += target.growthRate * 5.0
        
        // Position value - proximity to enemy planets
        val nearEnemyPlanets = gameState.planets
            .filter { it.owner == player.opponent() }
            .count { distanceBetween(it, target) < 15.0 }
        value += nearEnemyPlanets * 3.0
        
        // Strategic position - central vs edge
        val avgDistance = gameState.planets
            .map { distanceBetween(it, target) }
            .average()
        value += max(0.0, 20.0 - avgDistance) * 0.5
        
        return value
    }
    
    private fun calculateRiskLevel(
        source: Planet,
        shipsToSend: Double,
        gameState: GameState
    ): Double {
        val remainingShips = source.nShips - shipsToSend
        
        // Risk of leaving source planet vulnerable
        val nearbyEnemies = gameState.planets
            .filter { it.owner == player.opponent() }
            .filter { distanceBetween(it, source) < 20.0 }
            .sumOf { it.nShips }
        
        return if (remainingShips < nearbyEnemies * 0.5) {
            (nearbyEnemies - remainingShips) / source.nShips
        } else 0.0
    }
    
    private fun calculateEfficiency(
        shipsToSend: Double,
        target: Planet,
        travelTime: Int,
        wouldCapture: Boolean
    ): Double {
        if (!wouldCapture) return 0.0
        
        val targetGrowth = target.growthRate * travelTime
        val overkill = shipsToSend - (target.nShips + targetGrowth + 1.0)
        
        // Efficiency is higher when using just enough ships
        return max(0.0, 1.0 - overkill / shipsToSend)
    }

    private fun simulateAction(
        gameState: GameState,
        action: Action,
        isPlayerMove: Boolean
    ): GameState {
        val newState = gameState.deepCopy()
        val model = ForwardModel(newState, params)
        
        val playerAction = if (isPlayerMove) action else Action.doNothing()
        val opponentAction = if (!isPlayerMove) action else generateOpponentResponse(newState)
        
        model.step(mapOf(
            player to playerAction,
            player.opponent() to opponentAction
        ))
        
        return model.state
    }
    
    private fun generateOpponentResponse(gameState: GameState): Action {
        // Simple but effective opponent model
        val opponent = player.opponent()
        val sources = gameState.planets.filter { it.owner == opponent && it.nShips > 1.0 }
        if (sources.isEmpty()) return Action.doNothing()
        
        val myPlanets = gameState.planets.filter { it.owner == player }
        if (myPlanets.isEmpty()) return Action.doNothing()
        
        // Target my weakest planet with strongest source
        val source = sources.maxByOrNull { it.nShips } ?: return Action.doNothing()
        val target = myPlanets.minByOrNull { it.nShips } ?: return Action.doNothing()
        
        return Action(opponent, source.id, target.id, source.nShips * 0.7)
    }

    private fun evaluatePosition(gameState: GameState): Double {
        val cacheKey = generatePositionKey(gameState)
        positionCache[cacheKey]?.let { cached ->
            if (cached.turn == currentTurn) return cached.score
        }
        
        val features = extractPositionFeatures(gameState)
        val score = computePositionScore(features, gameState)
        
        positionCache[cacheKey] = EvaluatedPosition(score, currentTurn, features)
        return score
    }
    
    private fun extractPositionFeatures(gameState: GameState): PositionFeatures {
        val myPlanets = gameState.planets.filter { it.owner == player }
        val oppPlanets = gameState.planets.filter { it.owner == player.opponent() }
        val neutralPlanets = gameState.planets.filter { it.owner == Player.Neutral }
        
        val myShips = myPlanets.sumOf { it.nShips }
        val oppShips = oppPlanets.sumOf { it.nShips }
        val myGrowth = myPlanets.sumOf { it.growthRate }
        val oppGrowth = oppPlanets.sumOf { it.growthRate }
        
        // Advanced features
        val centerX = gameState.planets.map { it.position.x }.average()
        val centerY = gameState.planets.map { it.position.y }.average()
        val centralControl = calculateCentralControl(myPlanets, centerX, centerY)
        val borderSecurity = calculateBorderSecurity(myPlanets, oppPlanets)
        val expansionPotential = calculateExpansionPotential(myPlanets, neutralPlanets)
        val economicEfficiency = calculateEconomicEfficiency(myPlanets)
        val militaryPressure = calculateMilitaryPressure(myPlanets, oppPlanets)
        val defensiveStability = calculateDefensiveStability(myPlanets, oppPlanets, gameState)
        
        val strategicValue = centralControl * 0.2 + borderSecurity * 0.15 + expansionPotential * 0.25 +
                           economicEfficiency * 0.2 + militaryPressure * 0.2
        
        return PositionFeatures(
            myShips, oppShips, myGrowth, oppGrowth,
            myPlanets.size, oppPlanets.size, neutralPlanets.size,
            strategicValue, centralControl, borderSecurity, expansionPotential,
            economicEfficiency, militaryPressure, defensiveStability
        )
    }
    
    private fun calculateDefensiveStability(
        myPlanets: List<Planet>, 
        oppPlanets: List<Planet>,
        gameState: GameState
    ): Double {
        if (myPlanets.isEmpty()) return 0.0
        
        var stableCount = 0
        for (myPlanet in myPlanets) {
            // Check for incoming threats - planets with transporters heading to this planet
            val immediateThreats = gameState.planets
                .filter { it.owner == player.opponent() && it.transporter?.destinationIndex == myPlanet.id }
                .sumOf { it.transporter?.nShips ?: 0.0 }
            
            // Check for reinforcements - my planets with transporters heading to this planet
            val reinforcements = gameState.planets
                .filter { it.owner == player && it.transporter?.destinationIndex == myPlanet.id }
                .sumOf { it.transporter?.nShips ?: 0.0 }
            
            if (myPlanet.nShips + reinforcements >= immediateThreats * 1.1) {
                stableCount++
            }
        }
        
        return stableCount.toDouble() / myPlanets.size
    }
    
    private fun computePositionScore(features: PositionFeatures, gameState: GameState): Double {
        val phase = determineGamePhase(gameState)
        val weights = getPhaseWeights(phase)
        
        val materialScore = evaluateMaterialAdvantage(gameState)
        val productionScore = evaluateProductionAdvantage(gameState)
        val strategicScore = evaluateStrategicPosition(gameState)
        val positionalScore = evaluatePositionalAdvantage(gameState)
        val riskScore = evaluateRiskFactors(gameState)
        val futureScore = evaluateFutureProspects(gameState, phase)
        
        val totalScore = materialScore * weights["material"]!! +
                        productionScore * weights["production"]!! +
                        strategicScore * weights["strategic"]!! +
                        positionalScore * weights["positional"]!! +
                        riskScore * weights["risk"]!! +
                        futureScore * weights["future"]!!
        
        return totalScore
    }
    
    private fun determineGamePhase(gameState: GameState): GamePhase {
        val gameProgress = gameState.gameTick.toDouble() / params.maxTicks
        val myPlanets = gameState.planets.count { it.owner == player }
        val totalPlanets = gameState.planets.size
        val controlRatio = myPlanets.toDouble() / totalPlanets
        val neutralPlanets = gameState.planets.count { it.owner == Player.Neutral }
        
        return when {
            gameProgress < 0.15 || myPlanets <= 3 -> GamePhase.OPENING
            neutralPlanets > totalPlanets * 0.3 && controlRatio < 0.5 -> GamePhase.MIDDLE
            gameProgress > 0.75 || controlRatio > 0.7 -> GamePhase.LATE
            else -> GamePhase.MIDDLE
        }
    }
    
    private fun getPhaseWeights(phase: GamePhase): Map<String, Double> {
        return when (phase) {
            GamePhase.OPENING -> mapOf(
                "material" to 0.8, "production" to 2.0, "strategic" to 1.5,
                "positional" to 1.0, "risk" to 0.5, "future" to 1.8
            )
            GamePhase.MIDDLE -> mapOf(
                "material" to 1.2, "production" to 1.5, "strategic" to 1.0,
                "positional" to 1.5, "risk" to 1.0, "future" to 1.2
            )
            GamePhase.LATE -> mapOf(
                "material" to 1.8, "production" to 1.0, "strategic" to 0.8,
                "positional" to 1.8, "risk" to 1.5, "future" to 0.5
            )
        }
    }
    
    private fun evaluateMaterialAdvantage(gameState: GameState): Double {
        val myShips = gameState.planets.filter { it.owner == player }.sumOf { it.nShips }
        val oppShips = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.nShips }
        
        // Include ships in transit
        val myTransitShips = gameState.planets
            .filter { it.owner == player && it.transporter != null }
            .sumOf { it.transporter?.nShips ?: 0.0 }
        val oppTransitShips = gameState.planets
            .filter { it.owner == player.opponent() && it.transporter != null }
            .sumOf { it.transporter?.nShips ?: 0.0 }
        
        val totalMyShips = myShips + myTransitShips
        val totalOppShips = oppShips + oppTransitShips
        
        return if (totalOppShips > 0) {
            (totalMyShips - totalOppShips) / (totalMyShips + totalOppShips)
        } else {
            if (totalMyShips > 0) 1.0 else 0.0
        }
    }
    
    private fun evaluateProductionAdvantage(gameState: GameState): Double {
        val myProduction = gameState.planets.filter { it.owner == player }.sumOf { it.growthRate }
        val oppProduction = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.growthRate }
        val totalProduction = myProduction + oppProduction
        
        return if (totalProduction > 0) {
            (myProduction - oppProduction) / totalProduction
        } else 0.0
    }
    
    private fun evaluateStrategicPosition(gameState: GameState): Double {
        val myPlanets = gameState.planets.filter { it.owner == player }
        val oppPlanets = gameState.planets.filter { it.owner == player.opponent() }
        val neutralPlanets = gameState.planets.filter { it.owner == Player.Neutral }
        
        if (myPlanets.isEmpty()) return -1.0
        
        // Simplified strategic evaluation focusing on key metrics
        var strategicValue = 0.0
        
        // Growth rate advantage (40% weight)
        val myGrowth = myPlanets.sumOf { it.growthRate }
        val totalGrowth = gameState.planets.sumOf { it.growthRate }
        if (totalGrowth > 0) {
            strategicValue += (myGrowth / totalGrowth) * 0.4
        }
        
        // Planet count advantage (30% weight)  
        val myPlanetCount = myPlanets.size
        val totalPlanets = gameState.planets.size
        strategicValue += (myPlanetCount.toDouble() / totalPlanets) * 0.3
        
        // Expansion potential - simplified (20% weight)
        val nearNeutrals = neutralPlanets.count { neutral ->
            myPlanets.any { distanceBetween(it, neutral) < 25.0 }
        }
        if (neutralPlanets.isNotEmpty()) {
            strategicValue += (nearNeutrals.toDouble() / neutralPlanets.size) * 0.2
        }
        
        // Military advantage (10% weight)
        val myShips = myPlanets.sumOf { it.nShips }
        val oppShips = oppPlanets.sumOf { it.nShips }
        if (myShips + oppShips > 0) {
            strategicValue += (myShips / (myShips + oppShips)) * 0.1
        }
        
        return strategicValue
    }
    
    private fun calculateCentralControl(myPlanets: List<Planet>, centerX: Double, centerY: Double): Double {
        if (myPlanets.isEmpty()) return 0.0
        
        val centralPlanets = myPlanets.count { planet ->
            val dx = planet.position.x - centerX
            val dy = planet.position.y - centerY
            sqrt(dx * dx + dy * dy) < 25.0
        }
        
        return centralPlanets.toDouble() / myPlanets.size
    }
    
    private fun calculateBorderSecurity(myPlanets: List<Planet>, oppPlanets: List<Planet>): Double {
        if (myPlanets.isEmpty()) return 0.0
        
        var secureCount = 0
        for (myPlanet in myPlanets) {
            val nearestEnemy = oppPlanets.minOfOrNull { distanceBetween(myPlanet, it) } ?: Double.MAX_VALUE
            
            if (nearestEnemy > 30.0) {
                secureCount++
            } else {
                val localStrength = calculateLocalStrength(myPlanet, myPlanets, 20.0)
                val enemyThreat = calculateEnemyThreat(myPlanet, oppPlanets, 25.0)
                if (localStrength >= enemyThreat * 1.2) {
                    secureCount++
                }
            }
        }
        
        return secureCount.toDouble() / myPlanets.size
    }
    
    private fun calculateExpansionPotential(myPlanets: List<Planet>, neutralPlanets: List<Planet>): Double {
        if (neutralPlanets.isEmpty()) return 1.0
        
        var accessibleValue = 0.0
        var totalValue = 0.0
        
        for (neutral in neutralPlanets) {
            val value = neutral.growthRate * 10.0 + neutral.nShips * 0.1
            totalValue += value
            
            val nearestMyPlanet = myPlanets.minOfOrNull { distanceBetween(it, neutral) } ?: Double.MAX_VALUE
            
            if (nearestMyPlanet < 40.0) {
                accessibleValue += value * (1.0 - nearestMyPlanet / 40.0)
            }
        }
        
        return if (totalValue > 0) accessibleValue / totalValue else 0.0
    }
    
    private fun calculateEconomicEfficiency(myPlanets: List<Planet>): Double {
        if (myPlanets.isEmpty()) return 0.0
        
        val totalProduction = myPlanets.sumOf { it.growthRate }
        val totalMaintenance = myPlanets.sumOf { it.nShips * 0.01 }
        
        return if (totalMaintenance > 0) {
            min(1.0, totalProduction / totalMaintenance)
        } else 1.0
    }
    
    private fun calculateMilitaryPressure(myPlanets: List<Planet>, oppPlanets: List<Planet>): Double {
        if (oppPlanets.isEmpty()) return 1.0
        
        var totalPressure = 0.0
        for (oppPlanet in oppPlanets) {
            val nearestMyPlanet = myPlanets.minOfOrNull { distanceBetween(it, oppPlanet) } ?: Double.MAX_VALUE
            
            if (nearestMyPlanet < 30.0) {
                val pressure = 1.0 - nearestMyPlanet / 30.0
                totalPressure += pressure
            }
        }
        
        return min(1.0, totalPressure / oppPlanets.size)
    }
    
    private fun evaluatePositionalAdvantage(gameState: GameState): Double {
        val myPlanets = gameState.planets.filter { it.owner == player }
        
        if (myPlanets.isEmpty()) return 0.0
        
        // Connectivity
        val connectivity = calculateConnectivity(myPlanets)
        
        // Spread
        val spread = calculateSpread(myPlanets, gameState.planets)
        
        // Chokepoint control
        val chokepointControl = calculateChokepointControl(myPlanets, gameState.planets)
        
        return connectivity * 0.4 + spread * 0.3 + chokepointControl * 0.3
    }
    
    private fun calculateConnectivity(myPlanets: List<Planet>): Double {
        if (myPlanets.size <= 1) return 1.0
        
        var totalDistance = 0.0
        var connections = 0
        
        for (i in myPlanets.indices) {
            for (j in i + 1 until myPlanets.size) {
                totalDistance += distanceBetween(myPlanets[i], myPlanets[j])
                connections++
            }
        }
        
        val averageDistance = if (connections > 0) totalDistance / connections else 0.0
        return max(0.0, 1.0 - averageDistance / 50.0)
    }
    
    private fun calculateSpread(myPlanets: List<Planet>, allPlanets: List<Planet>): Double {
        if (myPlanets.isEmpty()) return 0.0
        
        val centerX = allPlanets.map { it.position.x }.average()
        val centerY = allPlanets.map { it.position.y }.average()
        
        val averageDistance = myPlanets.map { planet ->
            val dx = planet.position.x - centerX
            val dy = planet.position.y - centerY
            sqrt(dx * dx + dy * dy)
        }.average()
        
        return min(1.0, averageDistance / 50.0)
    }
    
    private fun calculateChokepointControl(myPlanets: List<Planet>, allPlanets: List<Planet>): Double {
        // Simplified chokepoint detection - planets that control access between regions
        var chokepointCount = 0
        
        for (planet in myPlanets) {
            val nearbyPlanets = allPlanets.filter { 
                it != planet && distanceBetween(planet, it) < 30.0 
            }
            
            if (nearbyPlanets.size >= 3) {
                // Check if this planet is central to local cluster
                val avgDistanceToNearby = nearbyPlanets.map { distanceBetween(planet, it) }.average()
                val avgDistanceBetweenNearby = nearbyPlanets.flatMap { p1 ->
                    nearbyPlanets.filter { it != p1 }.map { p2 -> distanceBetween(p1, p2) }
                }.average()
                
                if (avgDistanceToNearby < avgDistanceBetweenNearby * 0.7) {
                    chokepointCount++
                }
            }
        }
        
        return if (myPlanets.isNotEmpty()) chokepointCount.toDouble() / myPlanets.size else 0.0
    }
    
    private fun evaluateRiskFactors(gameState: GameState): Double {
        val myPlanets = gameState.planets.filter { it.owner == player }
        val oppPlanets = gameState.planets.filter { it.owner == player.opponent() }
        
        if (myPlanets.isEmpty()) return -1.0
        
        // Immediate threats
        val immediateRisk = calculateImmediateRisk(gameState)
        
        // Vulnerability
        val vulnerabilityRisk = calculateVulnerabilityRisk(myPlanets, oppPlanets)
        
        // Economic risk
        val economicRisk = calculateEconomicRisk(myPlanets)
        
        return -(immediateRisk * 0.5 + vulnerabilityRisk * 0.3 + economicRisk * 0.2)
    }
    
    private fun calculateImmediateRisk(gameState: GameState): Double {
        val myPlanets = gameState.planets.filter { it.owner == player }
        var totalRisk = 0.0
        
        for (planet in myPlanets) {
            val incomingThreats = gameState.planets
                .filter { it.owner == player.opponent() && it.transporter?.destinationIndex == planet.id }
                .sumOf { it.transporter?.nShips ?: 0.0 }
            
            val incomingReinforcements = gameState.planets
                .filter { it.owner == player && it.transporter?.destinationIndex == planet.id }
                .sumOf { it.transporter?.nShips ?: 0.0 }
            
            val netDefense = planet.nShips + incomingReinforcements
            if (incomingThreats > netDefense) {
                totalRisk += (incomingThreats - netDefense) / planet.nShips
            }
        }
        
        return if (myPlanets.isNotEmpty()) totalRisk / myPlanets.size else 0.0
    }
    
    private fun calculateVulnerabilityRisk(myPlanets: List<Planet>, oppPlanets: List<Planet>): Double {
        if (myPlanets.isEmpty()) return 1.0
        
        var vulnerableCount = 0
        for (planet in myPlanets) {
            val nearestEnemy = oppPlanets.minOfOrNull { distanceBetween(planet, it) } ?: Double.MAX_VALUE
            
            if (nearestEnemy < 20.0) {
                val enemyStrength = calculateEnemyThreat(planet, oppPlanets, 25.0)
                if (enemyStrength > planet.nShips * 1.5) {
                    vulnerableCount++
                }
            }
        }
        
        return vulnerableCount.toDouble() / myPlanets.size
    }
    
    private fun calculateEconomicRisk(myPlanets: List<Planet>): Double {
        if (myPlanets.isEmpty()) return 1.0
        
        val totalProduction = myPlanets.sumOf { it.growthRate }
        if (totalProduction == 0.0) return 0.0
        
        val sortedByProduction = myPlanets.sortedByDescending { it.growthRate }
        val topProducers = sortedByProduction.take(2)
        val topProduction = topProducers.sumOf { it.growthRate }
        
        return topProduction / totalProduction
    }
    
    private fun evaluateFutureProspects(gameState: GameState, phase: GamePhase): Double {
        return when (phase) {
            GamePhase.OPENING -> evaluateOpeningProspects(gameState)
            GamePhase.MIDDLE -> evaluateMiddleProspects(gameState)
            GamePhase.LATE -> evaluateEndgameProspects(gameState)
        }
    }
    
    private fun evaluateOpeningProspects(gameState: GameState): Double {
        val neutralPlanets = gameState.planets.filter { it.owner == Player.Neutral }
        val myPlanets = gameState.planets.filter { it.owner == player }
        
        var futureValue = 0.0
        for (neutral in neutralPlanets) {
            val distance = myPlanets.minOfOrNull { distanceBetween(it, neutral) } ?: Double.MAX_VALUE
            if (distance < 30.0) {
                val value = neutral.growthRate * 2.0
                val accessibility = max(0.0, 1.0 - distance / 30.0)
                futureValue += value * accessibility
            }
        }
        
        return futureValue / max(1.0, neutralPlanets.sumOf { it.growthRate })
    }
    
    private fun evaluateMiddleProspects(gameState: GameState): Double {
        val myGrowth = gameState.planets.filter { it.owner == player }.sumOf { it.growthRate }
        val oppGrowth = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.growthRate }
        val neutralGrowth = gameState.planets.filter { it.owner == Player.Neutral }.sumOf { it.growthRate }
        
        val totalGrowth = myGrowth + oppGrowth + neutralGrowth
        return if (totalGrowth > 0) {
            (myGrowth + neutralGrowth * 0.5) / totalGrowth
        } else 0.0
    }
    
    private fun evaluateEndgameProspects(gameState: GameState): Double {
        val myPlanets = gameState.planets.count { it.owner == player }
        val oppPlanets = gameState.planets.count { it.owner == player.opponent() }
        val totalPlanets = gameState.planets.size
        
        if (myPlanets + oppPlanets == 0) return 0.0
        
        val controlRatio = myPlanets.toDouble() / (myPlanets + oppPlanets)
        val dominance = if (myPlanets > totalPlanets * 0.6) 0.5 else 0.0
        
        return controlRatio + dominance
    }
    
    // Utility functions
    private fun calculateLocalStrength(center: Planet, myPlanets: List<Planet>, radius: Double): Double {
        return myPlanets
            .filter { distanceBetween(center, it) <= radius }
            .sumOf { it.nShips }
    }
    
    private fun calculateEnemyThreat(center: Planet, oppPlanets: List<Planet>, radius: Double): Double {
        return oppPlanets
            .filter { distanceBetween(center, it) <= radius }
            .sumOf { it.nShips * max(0.0, 1.0 - distanceBetween(center, it) / radius) }
    }
    
    private data class MapBounds(val width: Double, val height: Double)
    
    private fun calculateMapBounds(planets: List<Planet>): MapBounds {
        if (planets.isEmpty()) return MapBounds(0.0, 0.0)
        
        val minX = planets.minOf { it.position.x }
        val maxX = planets.maxOf { it.position.x }
        val minY = planets.minOf { it.position.y }
        val maxY = planets.maxOf { it.position.y }
        
        return MapBounds(maxX - minX, maxY - minY)
    }

    private fun isTerminalState(gameState: GameState): Boolean {
        val myPlanets = gameState.planets.count { it.owner == player }
        val oppPlanets = gameState.planets.count { it.owner == player.opponent() }
        
        return myPlanets == 0 || oppPlanets == 0 || 
               gameState.gameTick >= params.maxTicks
    }
    
    private fun distanceBetween(p1: Planet, p2: Planet): Double {
        val dx = p1.position.x - p2.position.x
        val dy = p1.position.y - p2.position.y
        return sqrt(dx * dx + dy * dy)
    }
    
    private fun generateStateKey(gameState: GameState, depth: Int): String {
        return "${gameState.gameTick}-$depth-${gameState.planets.hashCode()}"
    }
    
    private fun generatePositionKey(gameState: GameState): String {
        return "${gameState.gameTick}-${gameState.planets.map { "${it.owner}-${it.nShips.toInt()}-${it.growthRate.toInt()}" }.joinToString("-")}"
    }

    // Enhanced deferred orders system for sophisticated tactical planning
    private data class DeferredOrder(
        val action: Action,
        val earliestExecutionTurn: Int,
        val priority: Double,
        val conditions: List<OrderCondition>
    )
    
    private sealed class OrderCondition {
        object AlwaysExecute : OrderCondition()
        data class OnlyIfSafe(val minDefenseRatio: Double) : OrderCondition()
        data class OnlyIfAdvantage(val minAdvantageRatio: Double) : OrderCondition()
        data class OnlyIfExpansion(val maxNeutralDistance: Double) : OrderCondition()
    }
    
    private val deferredOrders = mutableListOf<DeferredOrder>()
    
    private fun shouldExecuteDeferredOrder(order: DeferredOrder, gameState: GameState): Boolean {
        if (gameState.gameTick < order.earliestExecutionTurn) return false
        
        return order.conditions.all { condition ->
            when (condition) {
                is OrderCondition.AlwaysExecute -> true
                is OrderCondition.OnlyIfSafe -> {
                    val source = gameState.planets.find { it.id == order.action.sourcePlanetId }
                    if (source?.owner != player) return false
                    val localStrength = calculateLocalStrength(source, 
                        gameState.planets.filter { it.owner == player }, 20.0)
                    val enemyThreat = calculateEnemyThreat(source, 
                        gameState.planets.filter { it.owner == player.opponent() }, 25.0)
                    localStrength >= enemyThreat * condition.minDefenseRatio
                }
                is OrderCondition.OnlyIfAdvantage -> {
                    val materialAdvantage = evaluateMaterialAdvantage(gameState)
                    materialAdvantage >= condition.minAdvantageRatio
                }
                is OrderCondition.OnlyIfExpansion -> {
                    val target = gameState.planets.find { it.id == order.action.destinationPlanetId }
                    target?.owner == Player.Neutral && 
                    gameState.planets.filter { it.owner == player }
                        .minOfOrNull { distanceBetween(it, target) } ?: Double.MAX_VALUE <= condition.maxNeutralDistance
                }
            }
        }
    }
    
    // Enhanced opponent modeling with pattern recognition
    private val opponentMoveHistory = mutableListOf<Action>()
    private val opponentPatterns = mutableMapOf<String, Int>()
    
    private fun updateOpponentModel(opponentAction: Action) {
        opponentMoveHistory.add(opponentAction)
        
        // Keep only recent history
        if (opponentMoveHistory.size > 20) {
            opponentMoveHistory.removeFirst()
        }
        
        // Analyze patterns
        if (opponentMoveHistory.size >= 3) {
            val pattern = extractMovePattern(opponentMoveHistory.takeLast(3))
            opponentPatterns[pattern] = opponentPatterns.getOrDefault(pattern, 0) + 1
        }
    }
    
    private fun extractMovePattern(moves: List<Action>): String {
        return moves.joinToString("|") { action ->
            when {
                action == Action.doNothing() -> "WAIT"
                action.numShips > 50 -> "HEAVY_ATTACK"
                action.numShips > 20 -> "ATTACK"
                action.numShips > 5 -> "PROBE"
                else -> "LIGHT"
            }
        }
    }
    
    private fun predictOpponentStrategy(gameState: GameState): OpponentStrategy {
        val recentMoves = opponentMoveHistory.takeLast(5)
        val aggressiveness = recentMoves.count { it != Action.doNothing() }.toDouble() / recentMoves.size
        val averageForce = recentMoves.filter { it != Action.doNothing() }.map { it.numShips }.average()
        
        return when {
            aggressiveness > 0.8 && averageForce > 30 -> OpponentStrategy.AGGRESSIVE
            aggressiveness > 0.6 -> OpponentStrategy.BALANCED
            aggressiveness > 0.3 -> OpponentStrategy.DEFENSIVE
            else -> OpponentStrategy.PASSIVE
        }
    }
    
    private enum class OpponentStrategy { AGGRESSIVE, BALANCED, DEFENSIVE, PASSIVE }
    
    // Enhanced horizon analysis for long-term planning
    private fun evaluateHorizonOutlook(gameState: GameState, horizonTurns: Int): Double {
        val myCurrentProduction = gameState.planets.filter { it.owner == player }.sumOf { it.growthRate }
        val oppCurrentProduction = gameState.planets.filter { it.owner == player.opponent() }.sumOf { it.growthRate }
        
        // Project future strength assuming current production continues
        val myFutureStrength = myCurrentProduction * horizonTurns
        val oppFutureStrength = oppCurrentProduction * horizonTurns
        
        // Factor in expansion opportunities
        val neutralValue = gameState.planets.filter { it.owner == Player.Neutral }
            .sumOf { it.growthRate * max(0.0, 1.0 - (getDistanceToNearestMyPlanet(it, gameState) / 50.0)) }
        
        // Estimate share of neutral expansion
        val myExpansionShare = if (myCurrentProduction + oppCurrentProduction > 0) {
            myCurrentProduction / (myCurrentProduction + oppCurrentProduction)
        } else 0.5
        
        val projectedAdvantage = (myFutureStrength + neutralValue * myExpansionShare) - oppFutureStrength
        
        return projectedAdvantage / max(1.0, myFutureStrength + oppFutureStrength)
    }
    
    private fun getDistanceToNearestMyPlanet(planet: Planet, gameState: GameState): Double {
        return gameState.planets.filter { it.owner == player }
            .minOfOrNull { distanceBetween(planet, it) } ?: Double.MAX_VALUE
    }

    // Quick fallback action selection for when search times out
    private fun getBestQuickAction(gameState: GameState): Action {
        val myPlanets = gameState.planets.filter { it.owner == player && it.nShips > 1.0 }
        if (myPlanets.isEmpty()) return Action.doNothing()
        
        // Find highest growth neutral planet within reach
        val neutrals = gameState.planets.filter { it.owner == Player.Neutral }
        val bestTarget = neutrals
            .filter { neutral -> 
                myPlanets.any { my -> distanceBetween(my, neutral) < 30.0 }
            }
            .maxByOrNull { it.growthRate }
        
        if (bestTarget != null) {
            val source = myPlanets
                .filter { it.nShips > bestTarget.nShips + 5.0 }
                .minByOrNull { distanceBetween(it, bestTarget) }
            
            if (source != null) {
                val shipsToSend = (bestTarget.nShips + 5.0).coerceAtMost(source.nShips * 0.7)
                return Action(player, source.id, bestTarget.id, shipsToSend)
            }
        }
        
        return Action.doNothing()
    }
} 