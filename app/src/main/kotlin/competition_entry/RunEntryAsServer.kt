package competition_entry

import games.planetwars.agents.strategic.TeamTitansAgentV2
import games.planetwars.agents.strategic.TeamTitansAgentV3
import json_rmi.GameAgentServer

fun main() {
    val server = GameAgentServer(port = 8080, agentClass = TeamTitansAgentV2::class)
    server.start(wait = true)
}
