package games.planetwars.agents.strategic

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsPlayer
import games.planetwars.core.GameParams
import games.planetwars.core.GameState
import games.planetwars.core.Player

/**
 * TeamTitans' competition agent (the one served by RunEntryAsServer).
 *
 * Each turn it simulates a set of candidate moves and plays the one leading to the best position.
 * The look-ahead depth adapts to the game settings. See [TitansPlanner] for the details.
 */
class TeamTitansAgentV3(
    val timeLimitMillis: Long = 90L
) : PlanetWarsPlayer() {

    private var planner = TitansPlanner(player, params)

    override fun prepareToPlayAs(player: Player, params: GameParams, opponent: String?): String {
        super.prepareToPlayAs(player, params, opponent)
        planner = TitansPlanner(this.player, this.params)
        return getAgentType()
    }

    override fun getAgentType(): String = "TeamTitansAgentV3"

    override fun getAction(gameState: GameState): Action =
        planner.bestAction(gameState, System.currentTimeMillis(), timeLimitMillis)
}
