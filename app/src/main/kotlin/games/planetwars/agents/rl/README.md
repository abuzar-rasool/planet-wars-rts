# Planet Wars Reinforcement Learning Agent

This package provides a reinforcement learning (RL) agent implementation for the Planet Wars game. The agent uses Deep Q-Learning to learn optimal strategies through repeated gameplay.

## Components

- **RLAgent**: The main agent class implementing the PlanetWarsPlayer interface
- **StateEncoder**: Converts game states into vector representations for the neural network
- **ActionEncoder**: Maps between numerical action indices and game actions
- **QNetwork**: Neural network implementation for value function approximation
- **ReplayMemory**: Experience replay buffer for storing and sampling transitions
- **RLLogger**: Logging utility for tracking training progress
- **Optimizer**: Handles model updates during training

## Usage

### Training a new agent

```kotlin
// Import the necessary classes
import games.planetwars.agents.rl.RLAgent
import games.planetwars.runners.RLTrainer
import games.planetwars.core.GameParams

// Create and run a trainer
val trainer = RLTrainer(
    trainingEpisodes = 1000,
    evalFrequency = 50,
    evalGames = 10,
    gameParams = GameParams(numPlanets = 20)
)
trainer.trainAgent()
```

You can also use the provided runner script:

```
java -cp <classpath> games.planetwars.runners.RunRLTrainingKt
```

### Using a trained agent

```kotlin
// Create an agent in inference mode (no exploration or training)
val agent = RLAgent(
    modelDirectory = "path/to/model/directory",
    isTraining = false,
    explorationRate = 0.0
)

// Use it in a game
val gameRunner = GameRunner(agent, opponentAgent, gameParams)
gameRunner.runGame()
```

### Evaluating in a league

The agent is already included in the `SamplePlayerLists.getFullList()` method, so you can evaluate it against other agents using:

```
java -cp <classpath> games.planetwars.runners.RoundRobinLeagueKt
```

## Reinforcement Learning Theory

### Core Concepts

Reinforcement learning is a paradigm where an agent learns by interacting with an environment and receiving feedback. The key elements are:

1. **States (S)**: Representations of the environment at each step (encoded game states)
2. **Actions (A)**: Choices the agent can make (attacking from one planet to another)
3. **Rewards (R)**: Feedback signals indicating the quality of actions
4. **Policy (π)**: Strategy that maps states to actions
5. **Value Function (Q)**: Estimates the expected future rewards for state-action pairs

### Q-Learning Algorithm

This agent implements Deep Q-Learning, which approximates the optimal action-value function Q*(s,a) using a neural network. The fundamental update equation is:

```
Q(s, a) ← Q(s, a) + α * [r + γ * max_a'Q(s', a') - Q(s, a)]
```

Where:
- α is the learning rate
- γ is the discount factor for future rewards
- r is the immediate reward
- s' is the next state
- max_a'Q(s', a') is the maximum estimated future value

In our implementation, the neural network takes a state as input and outputs Q-values for all possible actions. During training, we update the network weights to minimize the difference between predicted Q-values and target Q-values.

### Experience Replay

The agent uses experience replay to improve learning stability and efficiency:

1. Store transitions (s, a, r, s', done) in the replay memory
2. Sample random mini-batches from the memory during learning
3. Update the network based on these experiences

This breaks the correlation between sequential samples and allows the agent to learn from past experiences multiple times.

### Exploration vs. Exploitation

To balance discovering new strategies and using known good ones, the agent employs an epsilon-greedy strategy:

- With probability ε: Choose a random action (exploration)
- With probability 1-ε: Choose the action with the highest Q-value (exploitation)

The exploration rate (ε) can be adjusted as training progresses.

## Self-Play Training

Self-play is a powerful learning technique where the agent plays against previous versions of itself. This approach can significantly enhance agent performance, especially in competitive games like Planet Wars.

### Benefits of Self-Play

1. **No Ceiling on Opponent Skill**: The agent continually faces opponents that match its current skill level
2. **Diverse Strategy Learning**: The agent is forced to counter its own strategies, preventing specialization against fixed opponents
3. **Automatic Curriculum**: Difficulty naturally increases as the agent improves

### Implementing Self-Play

To implement self-play with this agent, you could:

1. **Version Storage**: Periodically save snapshots of the agent during training
2. **Opponent Pool**: Maintain a pool of previous agent versions for training
3. **Selection Strategy**: Choose opponents from the pool using strategies like:
   - Random selection
   - Prioritizing agents that recently defeated the current agent
   - Selecting agents of appropriate skill level

### Example Self-Play Implementation

```kotlin
// Create a self-play trainer
val selfPlayTrainer = RLSelfPlayTrainer(
    trainingEpisodes = 5000,
    snapshotFrequency = 100,  // Save agent version every 100 episodes
    opponentPoolSize = 10,    // Keep 10 previous versions
    evalFrequency = 200
)
selfPlayTrainer.trainAgent()
```

To add self-play to the current implementation, modify `RLTrainer` to periodically create and store copies of the agent, then select from these stored versions as opponents instead of or in addition to the fixed opponent agents.

## Implementation Details

### State Representation

The state is encoded as a vector containing for each planet:
- Owner (one-hot encoded as player/opponent/neutral)
- Number of ships (normalized)
- Growth rate (normalized)
- Position (x,y coordinates normalized)
- Whether a transporter is available

### Action Space

Actions are represented as (source planet, target planet) pairs, encoded as indices in a flattened matrix.
The agent automatically determines the number of ships to send based on a fixed ratio of the available ships.

### Reward Function

The reward function considers:
- Immediate rewards: Change in relative ship counts and planet ownership
- Terminal rewards: Win (+10), Loss (-10), Draw (0)

## Customizing

You can customize the agent's behavior by adjusting hyperparameters:

```kotlin
val agent = RLAgent(
    learningRate = 0.001,          // Learning rate for neural network
    discountFactor = 0.99,         // Discount factor for future rewards
    explorationRate = 0.1,         // Probability of random exploration
    batchSize = 32,                // Batch size for learning updates
    memoryCapacity = 10000,        // Size of replay memory
    targetNetworkUpdateFrequency = 1000  // How often to update target network
)
```

## Dependencies

The core implementation uses a simple linear model to avoid external dependencies. However, for better performance, you can replace the Q-Network implementation with a more sophisticated deep learning framework (DL4J, PyTorch, etc.). 