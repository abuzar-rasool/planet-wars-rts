package games.planetwars.agents.evo

import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.random.HeuristicParameters
import games.planetwars.agents.random.TunableHeuristicAgent
import games.planetwars.core.GameParams
import games.planetwars.core.Player
import games.planetwars.runners.GameRunner
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.random.Random
import kotlin.math.absoluteValue

/**
 * Stores information about a parameter configuration and its performance.
 */
data class ParameterResult(
    val parameters: HeuristicParameters,
    val winRate: Double,
    val gamesPlayed: Int,
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    fun toCSVLine(): String {
        val paramVals = listOf(
            parameters.reserveRatio,
            parameters.availableShipsRatio,
            parameters.defenseReinforceRatio,
            parameters.defensePriorityBase,
            parameters.neutralHorizon,
            parameters.enemyGrowthWeight,
            parameters.attackPriorityForLargestEnemy,
            parameters.vulnerabilityPenalty,
            parameters.sourceMinShips,
            parameters.maxAttackDistance,
            parameters.internalRedistributionDistance,
            parameters.internalRedistributionShips,
            parameters.defensiveOverkillMargin,
            parameters.attackOverkillMargin
        ).joinToString(",")
        
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return "$paramVals,$winRate,$gamesPlayed,${timestamp.format(formatter)}"
    }
    
    companion object {
        fun csvHeader(): String {
            return "reserveRatio,availableShipsRatio,defenseReinforceRatio,defensePriorityBase," +
                   "neutralHorizon,enemyGrowthWeight,attackPriorityForLargestEnemy,vulnerabilityPenalty," +
                   "sourceMinShips,maxAttackDistance,internalRedistributionDistance,internalRedistributionShips," +
                   "defensiveOverkillMargin,attackOverkillMargin,winRate,gamesPlayed,timestamp"
        }
    }
}

/**
 * Maintains a history of parameter configurations and their results.
 */
class ParameterHistory(
    private val historyFile: String = "results/parameter_optimization/history.csv",
    private val topResultsFile: String = "results/parameter_optimization/top_results.csv"
) {
    private val results = mutableListOf<ParameterResult>()
    private var topResults = mutableListOf<ParameterResult>()
    private val topResultsMax = 10 // Keep top 10 configurations
    
    init {
        // Create directories if they don't exist
        File(historyFile).parentFile?.mkdirs()
        loadHistoryIfExists()
    }
    
    private fun loadHistoryIfExists() {
        val file = File(historyFile)
        if (file.exists()) {
            val lines = file.readLines().drop(1) // Skip header
            // For simplicity, we're not parsing the full history back in this example
            println("Loaded ${lines.size} parameter configurations from history")
        }
        
        val topFile = File(topResultsFile)
        if (topFile.exists()) {
            val lines = topFile.readLines().drop(1) // Skip header
            // For simplicity, we're not parsing the full top results in this example
            println("Loaded top results from history")
        }
    }
    
    fun addResult(result: ParameterResult) {
        results.add(result)
        
        // Check if this result should be in the top results
        if (topResults.size < topResultsMax || result.winRate > topResults.minByOrNull { it.winRate }?.winRate ?: 0.0) {
            topResults.add(result)
            topResults = topResults.sortedByDescending { it.winRate }.take(topResultsMax).toMutableList()
            saveTopResults()
        }
        
        // Save to history file
        appendToHistoryFile(result)
    }
    
    private fun appendToHistoryFile(result: ParameterResult) {
        val file = File(historyFile)
        if (!file.exists()) {
            file.writeText(ParameterResult.csvHeader() + "\n")
        }
        file.appendText(result.toCSVLine() + "\n")
    }
    
    private fun saveTopResults() {
        val file = File(topResultsFile)
        file.writeText(ParameterResult.csvHeader() + "\n")
        topResults.forEach { file.appendText(it.toCSVLine() + "\n") }
    }
    
    fun getBestParameters(): HeuristicParameters? {
        return topResults.maxByOrNull { it.winRate }?.parameters
    }
    
    fun getTopResults(): List<ParameterResult> {
        return topResults.toList()
    }
    
    fun getAllResults(): List<ParameterResult> {
        return results.toList()
    }
}

/**
 * Base interface for parameter optimization strategies.
 */
interface OptimizationStrategy {
    fun optimize(
        history: ParameterHistory, 
        baseParams: HeuristicParameters,
        opponent: PlanetWarsAgent,
        evaluationGames: Int,
        gameParams: GameParams
    ): HeuristicParameters
}

/**
 * Evolutionary strategy for parameter optimization.
 */
class EvolutionaryStrategy(
    private val populationSize: Int = 10,
    private val generations: Int = 5,
    private val eliteCount: Int = 2,
    private val mutationRate: Double = 0.3,
    private val mutationStrength: Double = 0.2
) : OptimizationStrategy {
    
    override fun optimize(
        history: ParameterHistory,
        baseParams: HeuristicParameters,
        opponent: PlanetWarsAgent,
        evaluationGames: Int,
        gameParams: GameParams
    ): HeuristicParameters {
        
        // Initialize population with some variations of the base parameters
        var population = generateInitialPopulation(baseParams, history)
        
        // Main evolutionary loop
        for (generation in 0 until generations) {
            println("Generation ${generation + 1}/$generations")
            
            // Evaluate current population
            val evaluated = evaluatePopulation(population, opponent, evaluationGames, gameParams, history)
            
            // Select and breed next generation
            population = generateNextGeneration(evaluated)
        }
        
        // Return the best parameters found
        return history.getBestParameters() ?: baseParams
    }
    
    private fun generateInitialPopulation(
        baseParams: HeuristicParameters,
        history: ParameterHistory
    ): List<HeuristicParameters> {
        val population = mutableListOf<HeuristicParameters>()
        
        // Add best known parameters from history if available
        history.getTopResults().take(eliteCount).forEach { 
            population.add(it.parameters) 
        }
        
        // Add base parameters
        if (population.isEmpty()) {
            population.add(baseParams)
        }
        
        // Generate random variations to fill the population
        while (population.size < populationSize) {
            val template = population.random()
            population.add(mutateParameters(template))
        }
        
        return population
    }
    
    private fun evaluatePopulation(
        population: List<HeuristicParameters>,
        opponent: PlanetWarsAgent,
        evaluationGames: Int,
        gameParams: GameParams,
        history: ParameterHistory
    ): List<Pair<HeuristicParameters, Double>> {
        val results = mutableListOf<Pair<HeuristicParameters, Double>>()
        
        for ((index, params) in population.withIndex()) {
            println("  Evaluating candidate ${index + 1}/${population.size}")
            
            // Create an agent with these parameters
            val agent = TunableHeuristicAgent(params)
            
            // Run games and get win rate
            val runner = GameRunner(agent, opponent, gameParams)
            val scoreMap = runner.runGames(evaluationGames)
            
            val wins = scoreMap[Player.Player1] ?: 0
            val winRate = wins.toDouble() / evaluationGames
            
            // Store result
            results.add(Pair(params, winRate))
            history.addResult(ParameterResult(params, winRate, evaluationGames))
            
            println("    Win rate: $winRate")
        }
        
        return results.sortedByDescending { it.second }
    }
    
    private fun generateNextGeneration(
        evaluated: List<Pair<HeuristicParameters, Double>>
    ): List<HeuristicParameters> {
        val nextGen = mutableListOf<HeuristicParameters>()
        
        // Elite selection - keep best performers
        nextGen.addAll(evaluated.take(eliteCount).map { it.first })
        
        // Fill the rest with crossover and mutation
        while (nextGen.size < populationSize) {
            val parent1 = tournamentSelect(evaluated)
            val parent2 = tournamentSelect(evaluated)
            
            // Crossover
            val child = crossoverParameters(parent1, parent2)
            
            // Mutation
            if (Random.nextDouble() < mutationRate) {
                nextGen.add(mutateParameters(child))
            } else {
                nextGen.add(child)
            }
        }
        
        return nextGen
    }
    
    private fun tournamentSelect(
        evaluated: List<Pair<HeuristicParameters, Double>>
    ): HeuristicParameters {
        // Simple tournament selection
        val contestant1 = evaluated[Random.nextInt(evaluated.size)]
        val contestant2 = evaluated[Random.nextInt(evaluated.size)]
        return if (contestant1.second > contestant2.second) contestant1.first else contestant2.first
    }
    
    private fun crossoverParameters(
        parent1: HeuristicParameters,
        parent2: HeuristicParameters
    ): HeuristicParameters {
        // Simple uniform crossover
        return HeuristicParameters(
            reserveRatio = if (Random.nextBoolean()) parent1.reserveRatio else parent2.reserveRatio,
            availableShipsRatio = if (Random.nextBoolean()) parent1.availableShipsRatio else parent2.availableShipsRatio,
            defenseReinforceRatio = if (Random.nextBoolean()) parent1.defenseReinforceRatio else parent2.defenseReinforceRatio,
            defensePriorityBase = if (Random.nextBoolean()) parent1.defensePriorityBase else parent2.defensePriorityBase,
            neutralHorizon = if (Random.nextBoolean()) parent1.neutralHorizon else parent2.neutralHorizon,
            enemyGrowthWeight = if (Random.nextBoolean()) parent1.enemyGrowthWeight else parent2.enemyGrowthWeight,
            attackPriorityForLargestEnemy = if (Random.nextBoolean()) parent1.attackPriorityForLargestEnemy else parent2.attackPriorityForLargestEnemy,
            vulnerabilityPenalty = if (Random.nextBoolean()) parent1.vulnerabilityPenalty else parent2.vulnerabilityPenalty,
            sourceMinShips = if (Random.nextBoolean()) parent1.sourceMinShips else parent2.sourceMinShips,
            maxAttackDistance = if (Random.nextBoolean()) parent1.maxAttackDistance else parent2.maxAttackDistance,
            internalRedistributionDistance = if (Random.nextBoolean()) parent1.internalRedistributionDistance else parent2.internalRedistributionDistance,
            internalRedistributionShips = if (Random.nextBoolean()) parent1.internalRedistributionShips else parent2.internalRedistributionShips,
            defensiveOverkillMargin = if (Random.nextBoolean()) parent1.defensiveOverkillMargin else parent2.defensiveOverkillMargin,
            attackOverkillMargin = if (Random.nextBoolean()) parent1.attackOverkillMargin else parent2.attackOverkillMargin
        )
    }
    
    private fun mutateParameters(params: HeuristicParameters): HeuristicParameters {
        // Gaussian mutation for continuous parameters, small shifts for integers
        return HeuristicParameters(
            reserveRatio = mutateDouble(params.reserveRatio, 0.1, 0.5),
            availableShipsRatio = mutateDouble(params.availableShipsRatio, 0.5, 0.95),
            defenseReinforceRatio = mutateDouble(params.defenseReinforceRatio, 0.5, 0.95),
            defensePriorityBase = mutateDouble(params.defensePriorityBase, 500.0, 2000.0),
            neutralHorizon = mutateInt(params.neutralHorizon, 20, 100),
            enemyGrowthWeight = mutateDouble(params.enemyGrowthWeight, 5.0, 20.0),
            attackPriorityForLargestEnemy = mutateDouble(params.attackPriorityForLargestEnemy, 20.0, 100.0),
            vulnerabilityPenalty = mutateDouble(params.vulnerabilityPenalty, 5.0, 50.0),
            sourceMinShips = mutateInt(params.sourceMinShips, 1, 5),
            maxAttackDistance = mutateInt(params.maxAttackDistance, 30, 80),
            internalRedistributionDistance = mutateInt(params.internalRedistributionDistance, 10, 40),
            internalRedistributionShips = mutateInt(params.internalRedistributionShips, 10, 40),
            defensiveOverkillMargin = mutateInt(params.defensiveOverkillMargin, 1, 10),
            attackOverkillMargin = mutateInt(params.attackOverkillMargin, 1, 10)
        )
    }
    
    private fun mutateDouble(value: Double, min: Double, max: Double): Double {
        // Apply a random adjustment using multiple random values as approximate normal distribution
        val randomSum = Random.nextDouble() + Random.nextDouble() + Random.nextDouble() - 1.5
        val delta = (max - min) * mutationStrength * randomSum
        return (value + delta).coerceIn(min, max)
    }
    
    private fun mutateInt(value: Int, min: Int, max: Int): Int {
        // Apply a random adjustment to integer parameter
        val range = max - min
        val randomSum = (Random.nextDouble() + Random.nextDouble() + Random.nextDouble() - 1.5)
        val delta = (randomSum * range * mutationStrength).toInt()
        return (value + delta).coerceIn(min, max)
    }
}

/**
 * Main class for running parameter optimization.
 */
class ParameterOptimizer(
    private val baseParams: HeuristicParameters = HeuristicParameters(),
    private val opponent: PlanetWarsAgent,
    private val evaluationGames: Int = 20,
    private val gameParams: GameParams = GameParams(numPlanets = 20),
    private val history: ParameterHistory = ParameterHistory()
) {
    fun optimize(strategy: OptimizationStrategy): HeuristicParameters {
        return strategy.optimize(history, baseParams, opponent, evaluationGames, gameParams)
    }
    
    fun getBestParameters(): HeuristicParameters? {
        return history.getBestParameters()
    }
    
    fun printTopResults() {
        println("Top performing parameter configurations:")
        history.getTopResults().forEachIndexed { index, result ->
            println("${index + 1}. Win Rate: ${result.winRate * 100}% (${result.gamesPlayed} games)")
        }
    }
} 