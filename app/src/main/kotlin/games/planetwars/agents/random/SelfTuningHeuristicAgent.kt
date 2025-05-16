package games.planetwars.agents.random

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.GameParams
import games.planetwars.core.GameState
import games.planetwars.core.Player
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Self-tuning agent that adjusts strategy parameters dynamically based on game state.
 * This agent adapts its parameters based on its relative position in the game, the
 * number of planets owned, and the phase of the game.
 */
class SelfTuningHeuristicAgent : PlanetWarsPlayer() {

    // Precompute distance matrix for all planet pairs (assuming static planet list).
    private lateinit var travelTime: Array<IntArray>
    
    // Game state tracking for adaptive strategy
    private var currentTick = 0
    private var maxTicks = 300 // Default, will be updated from gameParams
    private var gamePhase = GamePhase.EARLY
    private var myPlanetsHistory = mutableListOf<Int>()
    private var enemyPlanetsHistory = mutableListOf<Int>()
    private var lastEvaluation = 0 // Last tick when parameters were evaluated
    private var evaluationFrequency = 20 // Re-evaluate parameters every 20 ticks
    
    // The parameters being used currently (will adapt during gameplay)
    private var currentParams = HeuristicParameters()
    
    // Parameter sets for different game phases
    private val earlyGameParams = HeuristicParameters(
        reserveRatio = 0.1,
        availableShipsRatio = 0.9,
        defenseReinforceRatio = 0.6,
        defensePriorityBase = 800.0,
        neutralHorizon = 70,
        enemyGrowthWeight = 15.0,
        attackPriorityForLargestEnemy = 30.0,
        vulnerabilityPenalty = 15.0,
        maxAttackDistance = 60,
        internalRedistributionDistance = 30,
        internalRedistributionShips = 15
    )
    
    private val midGameParams = HeuristicParameters(
        reserveRatio = 0.2,
        availableShipsRatio = 0.8,
        defenseReinforceRatio = 0.75,
        defensePriorityBase = 1000.0,
        neutralHorizon = 50,
        enemyGrowthWeight = 10.0,
        attackPriorityForLargestEnemy = 50.0,
        vulnerabilityPenalty = 20.0,
        maxAttackDistance = 50,
        internalRedistributionDistance = 20,
        internalRedistributionShips = 20
    )
    
    private val lateGameParams = HeuristicParameters(
        reserveRatio = 0.15,
        availableShipsRatio = 0.85,
        defenseReinforceRatio = 0.9,
        defensePriorityBase = 1200.0,
        neutralHorizon = 30,
        enemyGrowthWeight = 5.0,
        attackPriorityForLargestEnemy = 80.0,
        vulnerabilityPenalty = 10.0,
        maxAttackDistance = 80,
        internalRedistributionDistance = 10,
        internalRedistributionShips = 30
    )
    
    // Winning position params (aggressive)
    private val winningParams = HeuristicParameters(
        reserveRatio = 0.1,
        availableShipsRatio = 0.9,
        defenseReinforceRatio = 0.6,
        defensePriorityBase = 500.0,
        neutralHorizon = 30,
        enemyGrowthWeight = 20.0,
        attackPriorityForLargestEnemy = 100.0,
        vulnerabilityPenalty = 5.0,
        maxAttackDistance = 100,
        internalRedistributionDistance = 5,
        internalRedistributionShips = 40
    )
    
    // Losing position params (defensive)
    private val losingParams = HeuristicParameters(
        reserveRatio = 0.3,
        availableShipsRatio = 0.7,
        defenseReinforceRatio = 0.95,
        defensePriorityBase = 2000.0,
        neutralHorizon = 40,
        enemyGrowthWeight = 5.0,
        attackPriorityForLargestEnemy = 30.0,
        vulnerabilityPenalty = 50.0,
        maxAttackDistance = 30,
        internalRedistributionDistance = 40,
        internalRedistributionShips = 10
    )

    override fun prepareToPlayAs(player: Player, params: GameParams, opponent: String?): String {
        super.prepareToPlayAs(player, params, opponent)
        maxTicks = params.maxTicks
        return getAgentType()
    }

    override fun getAction(gameState: GameState): Action {
        val myP = player
        // Get current game time
        currentTick = gameState.gameTick
        
        // Initialize travel time matrix if needed
        if (!::travelTime.isInitialized) {
            initializeTravelTimeMatrix(gameState)
        }
        
        // Track planet counts for adaptation
        val myPlanetCount = gameState.planets.count { it.owner == myP }
        val enemyPlanetCount = gameState.planets.count { it.owner == myP.opponent() }
        myPlanetsHistory.add(myPlanetCount)
        enemyPlanetsHistory.add(enemyPlanetCount)
        
        // Periodically update parameters based on game state
        if (currentTick >= lastEvaluation + evaluationFrequency) {
            updateGamePhase()
            adaptParameters(myPlanetCount, enemyPlanetCount)
            lastEvaluation = currentTick
        }
        
        // Create a temporary agent with current parameters to compute the action
        val tempAgent = TunableHeuristicAgent(heuristicParams = currentParams)
        tempAgent.prepareToPlayAs(player, GameParams(), null)
        
        // Use the tunable agent to get the action with current parameters
        return tempAgent.getAction(gameState)
    }
    
    private fun initializeTravelTimeMatrix(gameState: GameState) {
        val n = gameState.planets.size
        travelTime = Array(n) { IntArray(n) }
        for (i in 0 until n) {
            val pi = gameState.planets[i]
            for (j in 0 until n) {
                val pj = gameState.planets[j]
                val dx = pi.position.x - pj.position.x
                val dy = pi.position.y - pj.position.y
                val dist = sqrt(dx*dx + dy*dy)
                travelTime[i][j] = ceil(dist).toInt()
            }
        }
    }
    
    private fun updateGamePhase() {
        gamePhase = when {
            currentTick < maxTicks * 0.3 -> GamePhase.EARLY
            currentTick < maxTicks * 0.7 -> GamePhase.MID
            else -> GamePhase.LATE
        }
    }
    
    private fun adaptParameters(myPlanetCount: Int, enemyPlanetCount: Int) {
        // Start with phase-appropriate parameters
        val phaseParams = when (gamePhase) {
            GamePhase.EARLY -> earlyGameParams
            GamePhase.MID -> midGameParams
            GamePhase.LATE -> lateGameParams
        }
        
        // Blend with position-based parameters (winning vs losing)
        val positionWeight = calculatePositionAdvantage(myPlanetCount, enemyPlanetCount)
        
        // Positive positionWeight means winning, negative means losing
        currentParams = if (positionWeight > 0) {
            blendParameters(phaseParams, winningParams, positionWeight.coerceIn(0.0, 1.0))
        } else {
            blendParameters(phaseParams, losingParams, (-positionWeight).coerceIn(0.0, 1.0))
        }
    }
    
    private fun calculatePositionAdvantage(myPlanetCount: Int, enemyPlanetCount: Int): Double {
        // Get trend from history (are we gaining or losing planets?)
        val historyWindow = 5.coerceAtMost(myPlanetsHistory.size)
        val myTrend = if (historyWindow > 1) {
            val recent = myPlanetsHistory.takeLast(historyWindow)
            (recent.last() - recent.first()) / historyWindow.toDouble()
        } else 0.0
        
        val enemyTrend = if (historyWindow > 1) {
            val recent = enemyPlanetsHistory.takeLast(historyWindow)
            (recent.last() - recent.first()) / historyWindow.toDouble()
        } else 0.0
        
        // Calculate advantage based on:
        // 1. Current planet count ratio
        // 2. Trend (are we gaining or losing ground?)
        val planetAdvantage = if (enemyPlanetCount > 0) {
            (myPlanetCount.toDouble() / enemyPlanetCount.toDouble() - 1.0) * 0.5
        } else 1.0
        
        val trendFactor = (myTrend - enemyTrend) * 0.3
        
        // Combine factors, result between -1.0 (severely losing) and 1.0 (clearly winning)
        return (planetAdvantage + trendFactor).coerceIn(-1.0, 1.0)
    }
    
    private fun blendParameters(base: HeuristicParameters, target: HeuristicParameters, weight: Double): HeuristicParameters {
        // Linear interpolation between parameter sets
        return HeuristicParameters(
            reserveRatio = interpolate(base.reserveRatio, target.reserveRatio, weight),
            availableShipsRatio = interpolate(base.availableShipsRatio, target.availableShipsRatio, weight),
            defenseReinforceRatio = interpolate(base.defenseReinforceRatio, target.defenseReinforceRatio, weight),
            defensePriorityBase = interpolate(base.defensePriorityBase, target.defensePriorityBase, weight),
            neutralHorizon = interpolate(base.neutralHorizon, target.neutralHorizon, weight).toInt(),
            enemyGrowthWeight = interpolate(base.enemyGrowthWeight, target.enemyGrowthWeight, weight),
            attackPriorityForLargestEnemy = interpolate(base.attackPriorityForLargestEnemy, target.attackPriorityForLargestEnemy, weight),
            vulnerabilityPenalty = interpolate(base.vulnerabilityPenalty, target.vulnerabilityPenalty, weight),
            sourceMinShips = base.sourceMinShips,
            maxAttackDistance = interpolate(base.maxAttackDistance, target.maxAttackDistance, weight).toInt(),
            internalRedistributionDistance = interpolate(base.internalRedistributionDistance, target.internalRedistributionDistance, weight).toInt(),
            internalRedistributionShips = interpolate(base.internalRedistributionShips, target.internalRedistributionShips, weight).toInt(),
            defensiveOverkillMargin = base.defensiveOverkillMargin,
            attackOverkillMargin = base.attackOverkillMargin
        )
    }
    
    private fun interpolate(a: Double, b: Double, t: Double): Double {
        return a * (1 - t) + b * t
    }
    
    private fun interpolate(a: Int, b: Int, t: Double): Double {
        return a * (1 - t) + b * t
    }

    override fun getAgentType(): String {
        return "Self-Tuning Heuristic Agent"
    }
}

enum class GamePhase {
    EARLY, MID, LATE
} 