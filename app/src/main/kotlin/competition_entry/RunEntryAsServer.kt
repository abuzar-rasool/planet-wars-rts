package competition_entry

import games.planetwars.agents.strategic.TeamTitansAgentV3
import json_rmi.GameAgentServer

fun main() {
    val server = GameAgentServer(port = 8080, agentClass = TeamTitansAgentV3::class)
    server.start(wait = true)
}
