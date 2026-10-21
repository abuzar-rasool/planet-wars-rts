package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PartialObservationAgent
import games.planetwars.agents.PartialObservationPlayer
import games.planetwars.core.GameParams
import games.planetwars.core.GameState
import games.planetwars.core.Observation
import games.planetwars.core.Planet
import games.planetwars.core.Player

/**
 * Partial-observability version of [TeamTitansAgentV3].
 *
 * Builds a game state from what it can see and runs the same [TitansPlanner] on it.
 */
class TeamTitansPartialAgentV2(
    val timeLimitMillis: Long = 90L
) : PartialObservationPlayer() {

    private var planner = TitansPlanner(player, params)

    override fun prepareToPlayAs(player: Player, params: GameParams, opponent: Player?): PartialObservationAgent {
        super.prepareToPlayAs(player, params, opponent)
        planner = TitansPlanner(this.player, this.params)
        return this
    }

    override fun getAgentType(): String = "TeamTitansPartialAgentV2"

    override fun getAction(observation: Observation): Action {
        val startTime = System.currentTimeMillis()
        return planner.bestAction(visibleState(observation), startTime, timeLimitMillis)
    }

    /** The game as we see it: hidden ship counts become 0 and fleets in flight are ignored. */
    private fun visibleState(observation: Observation): GameState {
        val planets = observation.observedPlanets.mapIndexed { index, planet ->
            Planet(
                owner = planet.owner,
                nShips = planet.nShips ?: 0.0,
                position = planet.position,
                growthRate = planet.growthRate,
                radius = planet.radius,
                transporter = null,
                id = index
            )
        }
        return GameState(planets, observation.gameTick)
    }
}
