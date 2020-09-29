package net.corda.node.persistence

import net.corda.core.utilities.getOrThrow
import net.corda.node.flows.isQuasarAgentSpecified
import net.corda.nodeapi.internal.persistence.DatabaseIncompatibleException
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DbSchemaInitialisationTest {

    @Test(timeout=300_000)
	fun `database is initialised`() {
        driver(DriverParameters(startNodesInProcess = isQuasarAgentSpecified(), cordappsForAllNodes = emptyList())) {
            val nodeHandle = {
                startNode(NodeParameters(customOverrides = mapOf("database.initialiseSchema" to "true"))).getOrThrow()
            }()
            assertNotNull(nodeHandle)
        }
    }

    @Test(timeout=300_000)
	fun `database is not initialised`() {
        driver(DriverParameters(startNodesInProcess = isQuasarAgentSpecified(), cordappsForAllNodes = emptyList())) {
            assertFailsWith(DatabaseIncompatibleException::class) {
                startNode(NodeParameters(customOverrides = mapOf("database.initialiseSchema" to "false"))).getOrThrow()
            }
        }
    }
}