# 🪐 PlanetWars AI Competition – Submission Instructions

Welcome to the PlanetWars AI competition! This document describes how to package and submit your entry. Your agent should run as a WebSocket server and respond to JSON-formatted messages. Each submission must include a `Dockerfile` that builds and runs your agent.

We support entries in **Kotlin/Java** or **Python**. 
Please follow the instructions below depending on your chosen language.

Note that it's possible to develop your agent in any language, providing it can comminicate via 
the WebSocket and handle JSON messages.

Each agent runs in a server process that listens for JSON messages.  
For Kotlin/Java and Python we provide the code to handle this, meaning you only need
focus on the agent logic.

Furthemore, the Kotlin code provides a **Forward Model** that enabling
the use of simulation-based AI methods, such as Monte Carlo Tree Search (MCTS).
(we may provide this in the Python version in the future).

---

## 📦 Submission Format

Each submission must:

1. Contain all necessary code and dependencies to build and run your agent.
2. Include a working `Dockerfile` that listens on **port 8080** and runs your agent server.
3. Be able to receive JSON game states and return legal actions via **WebSocket on port 8080**.
4. Be self-contained (no internet access during runtime).

---

## ☕ Kotlin/Java Submission

Your Kotlin or Java project must produce a single `.jar` file that runs the WebSocket server.

### Example `Dockerfile`

```dockerfile
# Use Java 20
FROM eclipse-temurin:20-jdk

# Set the working directory
WORKDIR /app

# Copy the compiled jar (ensure this path matches your Gradle build output)
COPY app/build/libs/client-server.jar app.jar

# Expose WebSocket port
EXPOSE 8080

# Run the agent
CMD ["java", "-jar", "app.jar"]

```
Around line 81, ensure this points to the main file you want to run your agent server from:
```kotlin
application {
    mainClass.set("competition_entry.RunEntryAsServerKt") // Adjust to match your actual package and file
}
```
In the example code, this loads in the Careful Random Agent:
```kotlin
package competition_entry

import games.planetwars.agents.random.CarefulRandomAgent
import json_rmi.GameAgentServer

fun main() {
    val server = GameAgentServer(port = 8080, agentClass = CarefulRandomAgent::class)
    server.start(wait = true)
}

```

