package games.planetwars.agents.random

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.GameParams
import games.planetwars.core.GameState
import games.planetwars.core.Player
import kotlin.math.ceil
import kotlin.math.sqrt

// # Best parameters found at 2025-05-16_05-17-14
// # Overall win rate against all opponents: 89,00%

// reserveRatio=0.16144043852582665
// availableShipsRatio=0.5
// defenseReinforceRatio=0.8726788164910481
// defensePriorityBase=1981.3306658171791
// neutralHorizon=32
// enemyGrowthWeight=7.773299340309059
// attackPriorityForLargestEnemy=25.210326562731378
// vulnerabilityPenalty=40.149163959713626
// sourceMinShips=2
// maxAttackDistance=80
// internalRedistributionDistance=40
// internalRedistributionShips=39
// defensiveOverkillMargin=3
// attackOverkillMargin=2

data class HeuristicParameters(
    // Ship allocation parameters
    val reserveRatio: Double = 0.16144043852582665,         // How much of ships to keep in reserve (0.2 = 20%)
    val availableShipsRatio: Double = 0.5,   // How much to make available for attacks (0.8 = 80%)
    val defenseReinforceRatio: Double = 0.8726788164910481, // When reinforcing, what fraction to send (0.75 = 75%)
    
    // Scoring weights
    val defensePriorityBase: Double = 1981.3306658171791, // Base score for defensive actions
    val neutralHorizon: Int = 32,           // How many ticks to consider for neutral planet benefit
    val enemyGrowthWeight: Double = 7.773299340309059,    // Weight for enemy planet growth rate in attack score
    val attackPriorityForLargestEnemy: Double = 25.210326562731378, // Extra score for attacking largest enemy planet
    val vulnerabilityPenalty: Double = 40.149163959713626, // Penalty for leaving source planet vulnerable
    
    // Thresholds
    val sourceMinShips: Int = 2,            // Minimum ships needed in source to consider an action
    val maxAttackDistance: Int = 80,        // Maximum distance to consider attacking
    val internalRedistributionDistance: Int = 40, // Distance threshold for internal redistribution
    val internalRedistributionShips: Int = 39, // Ship threshold for internal redistribution
    val defensiveOverkillMargin: Int = 3,    // Extra ships to send in defensive actions
    val attackOverkillMargin: Int = 2        // Extra ships to send in offensive actions
)

class TunableHeuristicAgent(val heuristicParams: HeuristicParameters = HeuristicParameters()) : PlanetWarsPlayer() {

    // Precompute distance matrix for all planet pairs (assuming static planet list).
    // This helps to quickly get travel time without recalculation.
    private lateinit var travelTime: Array<IntArray>

    override fun prepareToPlayAs(player: Player, params: GameParams, opponent: String?): String {
        super.prepareToPlayAs(player, params, opponent)
        // Initialize distance matrix once game starts
        // We'll fill travelTime on first getAction call
        return getAgentType()
    }

    override fun getAction(gameState: GameState): Action {
        val myP = player  // our player ID (Player1 or Player2)
        // If distance matrix not set up yet (e.g. prepareToPlayAs didn't do it), do it now.
        if (!::travelTime.isInitialized) {
            val n = gameState.planets.size
            travelTime = Array(n) { IntArray(n) }
            for (i in 0 until n) {
                val pi = gameState.planets[i]
                for (j in 0 until n) {
                    val pj = gameState.planets[j]
                    // Euclidean distance - using position data from planets
                    val dx = pi.position.x - pj.position.x
                    val dy = pi.position.y - pj.position.y
                    val dist = sqrt(dx*dx + dy*dy)
                    travelTime[i][j] = ceil(dist).toInt()  // travel time in ticks
                }
            }
        }

        // Identify all candidate actions (source->target moves) and evaluate them
        var bestAction = Action.doNothing()  // default to no action if nothing better
        var bestScore = Double.NEGATIVE_INFINITY

        // Gather basic lists of planets by owner for convenience
        val myPlanets = gameState.planets.filter { it.owner == myP }
        val enemyPlanets = gameState.planets.filter { it.owner == myP.opponent() }
        val neutralPlanets = gameState.planets.filter { it.owner == Player.Neutral }

        // Quick function to estimate future ships on a planet after t ticks (for owner perspective)
        fun projectedShips(planet: games.planetwars.core.Planet, owner: Player, t: Int): Int {
            return if (planet.owner == owner) {
                // If currently owned by that owner, it will have current ships + growth*t
                (planet.nShips + planet.growthRate * t).toInt()
            } else if (planet.owner == Player.Neutral || planet.owner == owner.opponent()) {
                // If not owned by that owner, it will have current ships (possibly enemy or neutral) + growth*t for whoever owns it.
                // For enemy-owned, treat them as opponent's ships.
                (planet.nShips + planet.growthRate * t).toInt()
            } else {
                0  // shouldn't happen
            }
        }

        // Find incoming enemy transporters targeting our planets
        // In this game, transporters are stored in the source planet's transporter field
        val incomingEnemyTransporters = gameState.planets
            .mapNotNull { it.transporter }
            .filter { it.owner == myP.opponent() }
        
        // Check defensive needs first: see if any of our planets is about to be attacked
        for (myPlanet in myPlanets) {
            // Find transporters targeting this planet
            val incomingEnemyFleets = incomingEnemyTransporters.filter { 
                it.destinationIndex == myPlanet.id 
            }

            if (incomingEnemyFleets.isNotEmpty()) {
                // Calculate approximate time to arrival based on position and velocity
                // This is a simplified approach since we don't have direct eta
                for (fleet in incomingEnemyFleets) {
                    val targetPos = myPlanet.position
                    val fleetPos = fleet.s
                    val distance = sqrt(
                        (targetPos.x - fleetPos.x) * (targetPos.x - fleetPos.x) +
                        (targetPos.y - fleetPos.y) * (targetPos.y - fleetPos.y)
                    )
                    val fleetSpeed = sqrt(fleet.v.x * fleet.v.x + fleet.v.y * fleet.v.y)
                    val turnsToArrive = if (fleetSpeed > 0) (distance / fleetSpeed).toInt() else 0
                    
                    // Predict if myPlanet will fall
                    val futureMyShips = projectedShips(myPlanet, myP, turnsToArrive)
                    if (fleet.nShips > futureMyShips + 1) {
                        // Planet is in danger, try to send help from nearest my planet with surplus
                        val helper = myPlanets
                            .filter { it.id != myPlanet.id && it.transporter == null }  // can send now
                            .minByOrNull { travelTime[it.id][myPlanet.id] }  // choose closest helper
                        if (helper != null) {
                            val eta = travelTime[helper.id][myPlanet.id]
                            if (eta <= turnsToArrive) {
                                // We can send reinforcement in time
                                val sendShips = kotlin.math.min(helper.nShips * heuristicParams.defenseReinforceRatio,  // send % of helper ships
                                                               fleet.nShips - futureMyShips + heuristicParams.defensiveOverkillMargin)
                                // send enough to overcome enemy plus a small margin
                                if (sendShips > 0 && sendShips < helper.nShips && helper.nShips - sendShips >= 1) {
                                    // Formulate action and score it (high priority since defense)
                                    val action = Action(player, helper.id, myPlanet.id, sendShips.toDouble())
                                    val score = heuristicParams.defensePriorityBase + sendShips  // large base score for critical defense
                                    if (score > bestScore) {
                                        bestScore = score
                                        bestAction = action
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Next priority: offensive or expansion moves
        for (source in myPlanets) {
            if (source.transporter != null || source.nShips < heuristicParams.sourceMinShips) continue  // skip planets already sent a fleet or too few ships
            // Determine a "surplus" we can use from this planet (keep some reserve)
            val availableShips = (source.nShips * heuristicParams.availableShipsRatio).toInt()  // use up to X%, keep Y% reserve
            if (availableShips < 1) continue

            // Consider neutral targets
            for (target in neutralPlanets) {
                if (availableShips <= 0) break
                // Skip if this neutral is closer to enemy (likely contested or unsafe)
                val d_my = travelTime[source.id][target.id]
                val d_enemy = enemyPlanets.minOfOrNull { travelTime[it.id][target.id] } ?: Int.MAX_VALUE
                if (d_my >= d_enemy) continue  // if we cannot get there faster, skip or consider smaller move
                // Determine ships needed to capture neutral
                val required = target.nShips.toInt() + 1  // must send more than neutral has
                if (availableShips >= required) {
                    // Evaluate benefit: growth * some horizon - cost
                    val horizon = heuristicParams.neutralHorizon  // consider X ticks of future production as benefit
                    val benefit = target.growthRate * (horizon - d_my).coerceAtLeast(0)
                    val score = benefit - required  // simple evaluation: net ships gained over time
                    if (score > bestScore) {
                        bestScore = score
                        bestAction = Action(player, source.id, target.id, required.toDouble())
                    }
                }
            }

            // Consider attacking enemy targets
            for (target in enemyPlanets) {
                if (availableShips <= 0) break
                // Only consider attack if we can reach in reasonable time
                val d = travelTime[source.id][target.id]
                if (d > heuristicParams.maxAttackDistance) continue  // too far, likely not worth it
                // Predict enemy ships on target at arrival
                val enemyFutureShips = projectedShips(target, myP.opponent(), d)
                // If we can send enough to capture (enemyFutureShips + 1), evaluate
                if (availableShips > enemyFutureShips) {
                    // Basic score: weaken enemy + gain planet
                    var score = enemyFutureShips + target.growthRate * heuristicParams.enemyGrowthWeight  // value enemy ships destroyed + some weight for gaining growth
                    // If target is enemy's only or largest planet, prioritize more
                    if (enemyPlanets.size == 1 || target.growthRate == enemyPlanets.maxOf { it.growthRate }) {
                        score += heuristicParams.attackPriorityForLargestEnemy
                    }
                    // If sending this leaves source vulnerable, penalize
                    if (source.nShips - availableShips < 5) {
                        score -= heuristicParams.vulnerabilityPenalty  // leaving source nearly empty is risky
                    }
                    if (score > bestScore) {
                        bestScore = score
                        // Send just enough to take over (or a bit more for safety)
                        val sendAmount = enemyFutureShips + heuristicParams.attackOverkillMargin  // a slight overkill margin
                        bestAction = Action(player, source.id, target.id, sendAmount.coerceAtMost(availableShips).toDouble())
                    }
                }
            }

            // Internal redistribution (if no better target): send to friendly front-line planet
            // If this source is far from enemy but has surplus, move ships toward front
            if (enemyPlanets.isNotEmpty()) {
                val closestEnemyDist = enemyPlanets.minOf { travelTime[source.id][it.id] }
                // If this source is relatively far (backline) and has lots of ships, reposition
                if (closestEnemyDist > heuristicParams.internalRedistributionDistance && source.nShips > heuristicParams.internalRedistributionShips) {
                    // find friendly planet closer to enemy to forward ships to
                    val forwardTarget = myPlanets
                        .filter { it.id != source.id }
                        .minByOrNull { enemyPlanets.minOf { e -> travelTime[it.id][e.id] } }  // planet with shortest distance to any enemy
                    if (forwardTarget != null) {
                        val send = (source.nShips * 0.5).toInt()  // send half forward
                        if (send > 0 && source.nShips - send >= 1) {
                            val score = send * 0.5  // lower priority than combat, but nonzero
                            if (score > bestScore) {
                                bestScore = score
                                bestAction = Action(player, source.id, forwardTarget.id, send.toDouble())
                            }
                        }
                    }
                }
            }
        }

        return bestAction  // either a concrete move or "do nothing" if no good move found
    }

    override fun getAgentType(): String {
        return "Tunable Heuristic Agent"
    }
} 