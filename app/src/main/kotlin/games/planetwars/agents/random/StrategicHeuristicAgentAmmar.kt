package games.planetwars.agents.random

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.*
import kotlin.math.max

class StrategicHeuristicAgentAmmar : PlanetWarsPlayer() {
    
    // Weights for our heuristic components - these could be tuned
    private val growthRateWeight = 3.0
    private val distanceWeight = 2.0
    private val shipCountWeight = 1.0
    private val threatWeight = 1.5
    
    override fun getAction(gameState: GameState): Action {
        // Get own planets that can launch fleets
        val myAvailablePlanets = gameState.planets.filter { 
            it.owner == player && it.transporter == null && it.nShips > 1.0 
        }
        
        if (myAvailablePlanets.isEmpty()) {
            return Action.doNothing()
        }
        
        // Get potential target planets (opponent or neutral)
        val potentialTargets = gameState.planets.filter { 
            it.owner == player.opponent() || it.owner == Player.Neutral 
        }
        
        if (potentialTargets.isEmpty()) {
            return Action.doNothing()
        }
        
        // Find the best source-target combination
        var bestScore = Double.NEGATIVE_INFINITY
        var bestSource: Planet? = null
        var bestTarget: Planet? = null
        var bestShipCount = 0.0
        
        for (source in myAvailablePlanets) {
            for (target in potentialTargets) {
                // Calculate required ships to capture target
                val requiredShips = calculateRequiredShips(source, target)
                
                // Skip if we don't have enough ships or it would leave source too vulnerable
                if (source.nShips < requiredShips * 1.1 || 
                    source.nShips - requiredShips < 5.0) {
                    continue
                }
                
                // Calculate score for this attack
                val score = evaluateAttack(source, target, requiredShips, gameState)
                
                if (score > bestScore) {
                    bestScore = score
                    bestSource = source
                    bestTarget = target
                    bestShipCount = requiredShips
                }
            }
        }
        
        // If we found a good attack, execute it
        if (bestSource != null && bestTarget != null) {
            return Action(player, bestSource.id, bestTarget.id, bestShipCount)
        }
        
        // Defensive strategy - reinforce our most valuable planet
        val mostValuablePlanet = findMostValuablePlanet(myAvailablePlanets)
        val weakestPlanet = findWeakestPlanet(myAvailablePlanets, mostValuablePlanet)
        
        if (mostValuablePlanet != null && weakestPlanet != null && 
            weakestPlanet.nShips > 10.0 && mostValuablePlanet != weakestPlanet) {
            return Action(player, weakestPlanet.id, mostValuablePlanet.id, weakestPlanet.nShips / 2)
        }
        
        return Action.doNothing()
    }
    
    private fun calculateRequiredShips(source: Planet, target: Planet): Double {
        // Calculate how many ticks it would take to reach the target
        val distance = source.position.distance(target.position)
        val travelTime = distance / params.transporterSpeed
        
        return when (target.owner) {
            Player.Neutral -> target.nShips + 1.0
            else -> target.nShips + 1.0 + (target.growthRate * travelTime)
        }
    }
    
    private fun evaluateAttack(source: Planet, target: Planet, requiredShips: Double, gameState: GameState): Double {
        // Calculate base value from target's growth rate (higher is better)
        var score = target.growthRate * growthRateWeight
        
        // Distance factor (closer is better)
        val distance = source.position.distance(target.position)
        score -= distance / 100.0 * distanceWeight
        
        // Efficiency factor (lower ship requirement is better)
        score -= requiredShips / 50.0 * shipCountWeight
        
        // Strategic value modifiers
        if (target.owner == player.opponent()) {
            // Bonus for attacking opponent - denies them growth
            score += target.growthRate * 1.5
            
            // Extra bonus for attacking their high-growth planets
            if (target.growthRate > 0.075) {
                score += 2.0
            }
        } else {
            // Neutral planets
            // Bonus for high growth rate neutrals
            if (target.growthRate > 0.05) {
                score += target.growthRate * 10.0
            }
        }
        
        // Consider threat - is this planet close to enemy territory?
        val enemyPlanets = gameState.planets.filter { it.owner == player.opponent() }
        if (enemyPlanets.isNotEmpty()) {
            val closestEnemyDistance = enemyPlanets.minOf { it.position.distance(target.position) }
            if (closestEnemyDistance < 150) {
                // Defensive value - claiming planets near enemy territory
                score += (200 - closestEnemyDistance) / 50.0 * threatWeight
            }
        }
        
        return score
    }
    
    private fun findMostValuablePlanet(myPlanets: List<Planet>): Planet? {
        if (myPlanets.isEmpty()) return null
        return myPlanets.maxByOrNull { it.growthRate * 10.0 + it.nShips }
    }
    
    private fun findWeakestPlanet(myPlanets: List<Planet>, excludePlanet: Planet?): Planet? {
        if (myPlanets.isEmpty()) return null
        return myPlanets
            .filter { it.id != excludePlanet?.id }
            .minByOrNull { it.nShips / max(1.0, it.growthRate) }
    }
    
    override fun getAgentType(): String {
        return "Strategic Heuristic Agent Ammar"
    }
}

// Add a main function to test the agent
fun main() {
    val agent = StrategicHeuristicAgentAmmar()
    val gameParams = GameParams(numPlanets = 10)
    val gameState = GameStateFactory(gameParams).createGame()
    agent.prepareToPlayAs(Player.Player1, gameParams)
    println(agent.getAgentType())
    val action = agent.getAction(gameState)
    println(action)
} 