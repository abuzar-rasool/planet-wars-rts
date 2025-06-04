package games.planetwars.debug

import games.planetwars.agents.Action
import games.planetwars.agents.PlanetWarsAgent
import games.planetwars.agents.strategic.TeamTitansAgentV2
import games.planetwars.core.*
import kotlin.random.Random

/**
 * Comprehensive agent testing suite for Planet Wars RTS agents.
 * Tests functionality, performance, robustness, and correctness without running full competitions.
 */
class AgentTester(
    private val verbose: Boolean = true,
    private val performanceTargetMs: Long = 100L
) {
    
    data class TestResult(
        val testName: String,
        val passed: Boolean,
        val message: String,
        val duration: Long? = null,
        val details: Map<String, Any> = emptyMap()
    )
    
    data class TestSuite(
        val agentType: String,
        val results: List<TestResult>,
        val overallSuccess: Boolean,
        val summary: String
    )
    
    /**
     * Run complete test suite for an agent
     */
    fun runCompleteTestSuite(agentFactory: () -> PlanetWarsAgent): TestSuite {
        val agent = agentFactory()
        val results = mutableListOf<TestResult>()
        
        if (verbose) println("Testing Agent: ${agent.getAgentType()}")
        
        // Basic functionality tests
        results.addAll(testBasicFunctionality(agentFactory))
        
        // Action validation tests
        results.addAll(testActionValidation(agentFactory))
        
        // Performance tests
        results.addAll(testPerformance(agentFactory))
        
        // Parameter robustness tests
        results.addAll(testParameterRobustness(agentFactory))
        
        // Error handling tests
        results.addAll(testErrorHandling(agentFactory))
        
        // Scenario-based tests
        results.addAll(testGameScenarios(agentFactory))
        
        val overallSuccess = results.all { it.passed }
        val summary = generateSummary(results)
        
        if (verbose) {
            println("\nTest Results:")
            println(summary)
        }
        
        return TestSuite(agent.getAgentType(), results, overallSuccess, summary)
    }
    
    /**
     * Test basic agent functionality
     */
    private fun testBasicFunctionality(agentFactory: () -> PlanetWarsAgent): List<TestResult> {
        val results = mutableListOf<TestResult>()
        
        if (verbose) println("\nBasic Functionality:")
        
        // Test 1: Agent creation and initialization
        results.add(runTest("Agent Creation") {
            val agent = agentFactory()
            val agentType = agent.getAgentType()
            require(agentType.isNotBlank()) { "Agent type should not be blank" }
            "Agent created successfully: $agentType"
        })
        
        // Test 2: Player assignment
        results.add(runTest("Player Assignment") {
            val agent = agentFactory()
            val params = GameParams()
            val result = agent.prepareToPlayAs(Player.Player1, params, "TestOpponent")
            require(result.isNotBlank()) { "prepareToPlayAs should return non-blank string" }
            "Player assignment successful"
        })
        
        // Test 3: Basic action generation
        results.add(runTest("Basic Action Generation") {
            val agent = agentFactory()
            val params = GameParams(numPlanets = 10)
            agent.prepareToPlayAs(Player.Player1, params)
            val gameState = GameStateFactory(params).createGame()
            val action = agent.getAction(gameState)
            require(action != null) { "Agent should return non-null action" }
            "Action generated successfully"
        })
        
        return results
    }
    
    /**
     * Test action validation and legality
     */
    private fun testActionValidation(agentFactory: () -> PlanetWarsAgent): List<TestResult> {
        val results = mutableListOf<TestResult>()
        
        if (verbose) println("\nAction Validation:")
        
        // Test multiple scenarios
        val scenarios = listOf(
            GameParams(numPlanets = 5, initialNeutralRatio = 0.0),
            GameParams(numPlanets = 15, initialNeutralRatio = 0.5),
            GameParams(numPlanets = 25, initialNeutralRatio = 0.3)
        )
        
        scenarios.forEachIndexed { index, params ->
            results.add(runTest("Scenario ${index + 1}") {
                val agent = agentFactory()
                agent.prepareToPlayAs(Player.Player1, params)
                
                var validActions = 0
                var invalidActions = 0
                
                repeat(10) { // Test 10 different states
                    val gameState = GameStateFactory(params).createGame()
                    // Simulate some game progression
                    repeat(Random.nextInt(0, 20)) {
                        val model = ForwardModel(gameState, params)
                        model.step(emptyMap())
                    }
                    
                    val action = agent.getAction(gameState)
                    if (isValidAction(action, gameState, Player.Player1)) {
                        validActions++
                    } else {
                        invalidActions++
                    }
                }
                
                require(validActions >= 8) { 
                    "Too many invalid actions: $invalidActions out of 10" 
                }
                "${params.numPlanets} planets: $validActions/10 valid"
            })
        }
        
        return results
    }
    
    /**
     * Test agent performance and time constraints
     */
    private fun testPerformance(agentFactory: () -> PlanetWarsAgent): List<TestResult> {
        val results = mutableListOf<TestResult>()
        
        if (verbose) println("\nPerformance:")
        
        // Test performance under various planet counts
        val planetCounts = listOf(10, 20, 30)
        
        planetCounts.forEach { planetCount ->
            results.add(runTimedTest("$planetCount planets") {
                val agent = agentFactory()
                val params = GameParams(numPlanets = planetCount)
                agent.prepareToPlayAs(Player.Player1, params)
                
                val times = mutableListOf<Long>()
                
                repeat(20) {
                    val gameState = GameStateFactory(params).createGame()
                    val startTime = System.currentTimeMillis()
                    agent.getAction(gameState)
                    val duration = System.currentTimeMillis() - startTime
                    times.add(duration)
                }
                
                val avgTime = times.average()
                val maxTime = times.maxOrNull() ?: 0L
                
                require(maxTime < performanceTargetMs) { 
                    "Max time ${maxTime}ms exceeds target ${performanceTargetMs}ms" 
                }
                
                mapOf(
                    "avgTime" to avgTime,
                    "maxTime" to maxTime,
                    "samples" to times.size
                ) to "Avg: ${avgTime.toInt()}ms, Max: ${maxTime}ms"
            })
        }
        
        // Stress test with time pressure
        results.add(runTimedTest("Stress Test") {
            val agent = agentFactory()
            val params = GameParams(numPlanets = 25)
            agent.prepareToPlayAs(Player.Player1, params)
            
            var timeouts = 0
            var successCount = 0
            
            repeat(50) {
                val gameState = GameStateFactory(params).createGame()
                val startTime = System.currentTimeMillis()
                
                try {
                    agent.getAction(gameState)
                    val duration = System.currentTimeMillis() - startTime
                    if (duration < performanceTargetMs) {
                        successCount++
                    } else {
                        timeouts++
                    }
                } catch (e: Exception) {
                    timeouts++
                }
            }
            
            require(timeouts < 5) { 
                "Too many timeouts/failures: $timeouts out of 50" 
            }
            
            mapOf(
                "successes" to successCount,
                "timeouts" to timeouts
            ) to "50 rapid decisions: $timeouts timeouts"
        })
        
        return results
    }
    
    /**
     * Test agent robustness with different parameters
     */
    private fun testParameterRobustness(agentFactory: () -> PlanetWarsAgent): List<TestResult> {
        val results = mutableListOf<TestResult>()
        
        if (verbose) println("\nParameter Robustness:")
        
        // Test extreme parameter ranges (competition parameters)
        val parameterSets = listOf(
            GameParams(
                numPlanets = 10, initialNeutralRatio = 0.25,
                transporterSpeed = 2.0, maxGrowthRate = 0.05
            ),
            GameParams(
                numPlanets = 30, initialNeutralRatio = 0.35,
                transporterSpeed = 5.0, maxGrowthRate = 0.2
            ),
            GameParams(
                numPlanets = 20, initialNeutralRatio = 0.3,
                transporterSpeed = 3.5, maxGrowthRate = 0.15
            )
        )
        
        parameterSets.forEachIndexed { index, params ->
            results.add(runTest("Parameter Set ${index + 1}") {
                val agent = agentFactory()
                agent.prepareToPlayAs(Player.Player1, params)
                
                var successfulDecisions = 0
                val issues = mutableListOf<String>()
                
                repeat(15) {
                    try {
                        val gameState = GameStateFactory(params).createGame()
                        val action = agent.getAction(gameState)
                        
                        if (isValidAction(action, gameState, Player.Player1)) {
                            successfulDecisions++
                        } else {
                            issues.add("Invalid action: $action")
                        }
                    } catch (e: Exception) {
                        issues.add("Exception: ${e.message}")
                    }
                }
                
                require(successfulDecisions >= 12) { 
                    "Only $successfulDecisions/15 successful decisions. Issues: $issues" 
                }
                
                "${params.numPlanets}p, speed ${params.transporterSpeed}: $successfulDecisions/15 valid"
            })
        }
        
        return results
    }
    
    /**
     * Test error handling and edge cases
     */
    private fun testErrorHandling(agentFactory: () -> PlanetWarsAgent): List<TestResult> {
        val results = mutableListOf<TestResult>()
        
        if (verbose) println("\nError Handling:")
        
        // Test with minimal planets
        results.add(runTest("Minimal Planets") {
            val agent = agentFactory()
            val params = GameParams(numPlanets = 2, initialNeutralRatio = 0.0)
            agent.prepareToPlayAs(Player.Player1, params)
            
            val gameState = GameStateFactory(params).createGame()
            val action = agent.getAction(gameState)
            
            require(action != null) { "Agent should handle minimal planet scenarios" }
            require(isValidAction(action, gameState, Player.Player1)) { 
                "Action should be valid even with minimal planets" 
            }
            
            "Handled 2-planet scenario"
        })
        
        return results
    }
    
    /**
     * Test agent behavior in specific game scenarios
     */
    private fun testGameScenarios(agentFactory: () -> PlanetWarsAgent): List<TestResult> {
        val results = mutableListOf<TestResult>()
        
        if (verbose) println("\nGame Scenarios:")
        
        // Test consistent decision making across different scenarios
        results.add(runTest("Decision Consistency") {
            val agent = agentFactory()
            val params = GameParams(numPlanets = 15, initialNeutralRatio = 0.3)
            agent.prepareToPlayAs(Player.Player1, params)
            
            var validDecisions = 0
            var exceptions = 0
            
            repeat(10) {
                try {
                    val testState = GameStateFactory(params).createGame()
                    val testAction = agent.getAction(testState)
                    
                    // Just check if the decision is valid - no strategy bias
                    if (isValidAction(testAction, testState, Player.Player1)) {
                        validDecisions++
                    }
                } catch (e: Exception) {
                    exceptions++
                }
            }
            
            require(validDecisions >= 8) { 
                "Agent should make valid decisions consistently: only $validDecisions/10 valid, $exceptions exceptions" 
            }
            
            "$validDecisions/10 valid decisions"
        })
        
        // Test late game behavior
        results.add(runTest("Late Game") {
            val agent = agentFactory()
            val params = GameParams(numPlanets = 20, maxTicks = 1000)
            agent.prepareToPlayAs(Player.Player1, params)
            
            val gameState = GameStateFactory(params).createGame()
            // Simulate late game
            gameState.gameTick = 800
            
            val action = agent.getAction(gameState)
            
            require(isValidAction(action, gameState, Player.Player1)) { 
                "Agent should handle late game scenarios" 
            }
            
            "Handled tick ${gameState.gameTick}"
        })
        
        return results
    }
    
    // Utility methods
    private fun runTest(testName: String, test: () -> String): TestResult {
        return try {
            val startTime = System.currentTimeMillis()
            val message = test()
            val duration = System.currentTimeMillis() - startTime
            
            if (verbose) println("  ✓ $testName")
            TestResult(testName, true, message, duration)
        } catch (e: Exception) {
            if (verbose) println("  ✗ $testName: ${e.message}")
            TestResult(testName, false, e.message ?: "Unknown error")
        }
    }
    
    private fun runTimedTest(testName: String, test: () -> Pair<Map<String, Any>, String>): TestResult {
        return try {
            val startTime = System.currentTimeMillis()
            val (details, message) = test()
            val duration = System.currentTimeMillis() - startTime
            
            if (verbose) println("  ✓ $testName: $message")
            TestResult(testName, true, message, duration, details)
        } catch (e: Exception) {
            if (verbose) println("  ✗ $testName: ${e.message}")
            TestResult(testName, false, e.message ?: "Unknown error")
        }
    }
    
    private fun isValidAction(action: Action, gameState: GameState, player: Player): Boolean {
        if (action == Action.doNothing() || action == Action.DO_NOTHING) return true
        
        try {
            val source = gameState.planets[action.sourcePlanetId]
            return source.owner == player && 
                   source.transporter == null && 
                   source.nShips >= action.numShips &&
                   action.destinationPlanetId < gameState.planets.size
        } catch (e: Exception) {
            return false
        }
    }
    
    private fun generateSummary(results: List<TestResult>): String {
        val passed = results.count { it.passed }
        val total = results.size
        val avgDuration = results.mapNotNull { it.duration }.average()
        
        val categoryResults = results.groupBy { 
            it.testName.split(" - ")[0].split(" ")[0] 
        }.mapValues { (_, tests) -> 
            tests.count { it.passed } to tests.size 
        }
        
        val summary = StringBuilder()
        summary.appendLine("Overall: $passed/$total tests passed")
        summary.appendLine("Average test duration: ${avgDuration.toInt()}ms")
        summary.appendLine("\nCategory breakdown:")
        categoryResults.forEach { (category, counts) ->
            summary.appendLine("  $category: ${counts.first}/${counts.second}")
        }
        
        if (passed < total) {
            summary.appendLine("\nFailed tests:")
            results.filter { !it.passed }.forEach { result ->
                summary.appendLine("  - ${result.testName}: ${result.message}")
            }
        }
        
        return summary.toString()
    }
}

/**
 * Quick test function for rapid agent validation
 */
fun quickTestAgent(agentFactory: () -> PlanetWarsAgent): Boolean {
    val tester = AgentTester(verbose = false)
    val result = tester.runCompleteTestSuite(agentFactory)
    return result.overallSuccess
}

/**
 * Detailed test function with full reporting
 */
fun detailedTestAgent(agentFactory: () -> PlanetWarsAgent): AgentTester.TestSuite {
    val tester = AgentTester(verbose = true)
    return tester.runCompleteTestSuite(agentFactory)
}

/**
 * Main function to test an agent - Change the agent here to test your own
 */
fun main() {
    println("Planet Wars RTS - Agent Tester")
    println("=".repeat(40))
    
    // CHANGE THIS LINE TO TEST YOUR AGENT
    val myAgent = { TeamTitansAgentV2(maxHorizon = 500) }
    
    println("Agent: ${myAgent().getAgentType()}")
    
    // Quick test first
    print("Quick validation... ")
    val quickResult = quickTestAgent(myAgent)
    println(if (quickResult) "✅ PASS" else "❌ FAIL")
    
    if (quickResult) {
        println("\n✅ Agent ready for basic use!")
    } else {
        println("\n❌ Running detailed diagnostics...\n")
        
        // Run detailed test for failed agents
        val detailedResult = detailedTestAgent(myAgent)
        
        if (!detailedResult.overallSuccess) {
            println("\n🔍 Failed Tests:")
            detailedResult.results.filter { !it.passed }.forEach { test ->
                println("  • ${test.testName}: ${test.message}")
            }
            
            println("\n💡 Next Steps:")
            println("  • Fix the failed tests above")
            println("  • Ensure valid action generation")
            println("  • Handle edge cases properly")
        }
    }
    
    println("\n" + "=".repeat(40))
    println("Testing complete. Change 'myAgent' variable to test your own agent.")
} 