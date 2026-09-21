# TeamTitans Planet Wars RTS Submission

<img width="406" alt="image" src="https://github.com/user-attachments/assets/d70c0d2a-bd57-4795-9ec4-fb35a401f8f3" alt="QR Code" width="150" align="right"/>

This repository contains **TeamTitans' Planet Wars RTS competition submission**, built on top of the upstream Planet Wars RTS framework by Simon Lucas.

- Upstream framework: https://github.com/SimonLucas/planet-wars-rts
- Official competition page: https://simonlucas.github.io/planet-wars-rts/

## Competition Recognition

- The official Planet Wars RTS competition page states: **“Congratulations to TeamTitans for winning the AAMAS 2026 competition!”**
- The same official page links to TeamTitans' winning-agent slides.
- This repository also includes TeamTitans slide assets under [`slides/`](slides/) (for example, [`slides/TeamTitansSlides.pdf`](slides/TeamTitansSlides.pdf)).
- The upstream IEEE CoG 2025 results page lists **TeamTitansV3** in first place and describes TeamTitans as clear winners: [`competitions/IEEE_CoG_2025_Results.md`](https://github.com/SimonLucas/planet-wars-rts/blob/main/competitions/IEEE_CoG_2025_Results.md)

## What is Planet Wars RTS?

Planet Wars is a real-time strategy game where agents compete to control planets and destroy enemy units. The framework supports a configurable family of game variants, including:

- full or partial observability
- variable map size and number of planets
- configurable battle/transit rules and time limits
- different win conditions and game durations

Sample game views:

<img width="638" alt="image" src="https://github.com/user-attachments/assets/dc702b7c-745d-44e9-a7b9-d172ecd65478" />

<img width="640" alt="image" src="https://github.com/user-attachments/assets/e1de70d3-444d-49bf-b0ee-dc5982eebbfc" />

## Agent Interfaces

The project includes interfaces for both fully observable and partially observable play.

### Fully Observable

```kotlin
interface PlanetWarsAgent {
  fun getAction(gameState: GameState): Action
  fun getAgentType(): String
  fun prepareToPlayAs(player: Player, params: GameParams, opponent: Player? = null): PlanetWarsAgent
  fun processGameOver(finalState: GameState) {}
}
```

### Partially Observable

```kotlin
interface PartialObservationAgent {
  fun getAction(observation: Observation): Action
  fun getAgentType(): String
  fun prepareToPlayAs(player: Player, params: GameParams, opponent: Player? = null): PartialObservationAgent
  fun processGameOver(finalState: GameState) {}
}
```

## Running Locally

Use runner classes in `games.planetwars.runners` for headless execution and evaluation.

Examples:
- `games.planetwars.runners.GameRunner`
- `games.planetwars.runners.PartialObservationGameRunner`
- `games.planetwars.runners.RoundRobinLeague`

For GUI play/visualization, use:
- `games.planetwars.view.RunVisualGame`
- `games.planetwars.view.PartialObservationRunVisualGame`

## Containerized Competition Deployment

For submission-style deployment, package your agent as a Docker/Podman container exposing WebSocket port `8080`.

See:
- [Submission Instructions](submit_entry.md)
- [Slides Instructions](slides/README.md)

Repository files that support this flow include:
- [`Dockerfile`](Dockerfile)
- [`Dockerfile_with_gradle`](Dockerfile_with_gradle)
- [`planetwars.def`](planetwars.def)
- [`run_submission_bot.sh`](run_submission_bot.sh)

## Evaluation

For local league-style evaluation, see the round-robin runners in `games.planetwars.runners`, including `RoundRobinLeague` and improved parallel variants.

## Attribution and Scope

This repository is **not** the original Planet Wars RTS framework repository. It contains TeamTitans' competition-focused work derived from the upstream project.

- Core engine/framework and competition infrastructure are maintained by the upstream Planet Wars RTS organizers and contributors.
- TeamTitans' submission artifacts in this repository build on that foundation.
