package net.corda.node.persistence

import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.node.flows.isQuasarAgentSpecified
import net.corda.node.services.Permissions
import net.corda.nodeapi.internal.persistence.CouldNotCreateDataSourceException
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.driver.internal.incrementalPortAllocation
import net.corda.testing.node.User
import net.corda.testing.node.internal.enclosedCordapp
import org.h2.jdbc.JdbcSQLNonTransientException
import org.junit.Test
import java.net.InetAddress
import java.sql.DriverManager
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class H2SecurityTests {
    companion object {
        private val port = incrementalPortAllocation()
        private fun getFreePort() = port.nextPort()
        private const val h2AddressKey = "h2Settings.address"
        private const val dbPasswordKey = "dataSourceProperties.dataSource.password"
    }

    @Test(timeout=300_000)
	fun `h2 server starts when h2Settings are set`() {
        driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = isQuasarAgentSpecified(),
                notarySpecs = emptyList(),
                cordappsForAllNodes = emptyList()
        )) {
            val port = getFreePort()
            startNode(customOverrides = mapOf(h2AddressKey to "localhost:$port")).getOrThrow()
            DriverManager.getConnection("jdbc:h2:tcp://localhost:$port/node", "sa", "").use {
                assertTrue(it.createStatement().executeQuery("SELECT 1").next())
            }
        }
    }

    @Test(timeout=300_000)
	fun `h2 server on the host name requires non-default database password`() {
        driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = isQuasarAgentSpecified(),
                notarySpecs = emptyList(),
                cordappsForAllNodes = emptyList()
        )) {
            assertFailsWith(CouldNotCreateDataSourceException::class) {
                startNode(customOverrides = mapOf(h2AddressKey to "${InetAddress.getLocalHost().hostName}:${getFreePort()}")).getOrThrow()
            }
        }
    }

    @Test(timeout=300_000)
	fun `h2 server on the external host IP requires non-default database password`() {
        driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = isQuasarAgentSpecified(),
                notarySpecs = emptyList(),
                cordappsForAllNodes = emptyList()
        )) {
            assertFailsWith(CouldNotCreateDataSourceException::class) {
                startNode(customOverrides = mapOf(h2AddressKey to "${InetAddress.getLocalHost().hostAddress}:${getFreePort()}")).getOrThrow()
            }
        }
    }

    @Test(timeout=300_000)
	fun `h2 server on host name requires non-blank database password`() {
        driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = isQuasarAgentSpecified(),
                notarySpecs = emptyList(),
                cordappsForAllNodes = emptyList()
        )) {
            assertFailsWith(CouldNotCreateDataSourceException::class) {
                startNode(customOverrides = mapOf(h2AddressKey to "${InetAddress.getLocalHost().hostName}:${getFreePort()}",
                        dbPasswordKey to " ")).getOrThrow()
            }
        }
    }

    @Test(timeout=300_000)
	fun `h2 server on external host IP requires non-blank database password`() {
        driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = isQuasarAgentSpecified(),
                notarySpecs = emptyList(),
                cordappsForAllNodes = emptyList()
        )) {
            assertFailsWith(CouldNotCreateDataSourceException::class) {
                startNode(customOverrides = mapOf(h2AddressKey to "${InetAddress.getLocalHost().hostAddress}:${getFreePort()}",
                        dbPasswordKey to " ")).getOrThrow()
            }
        }
    }

    @Test(timeout=300_000)
	fun `h2 server on localhost runs with the default database password`() {
        driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = false,
                notarySpecs = emptyList(),
                cordappsForAllNodes = emptyList()
        )) {
            startNode(customOverrides = mapOf(h2AddressKey to "localhost:${getFreePort()}")).getOrThrow()
        }
    }

    @Test(timeout=300_000)
	fun `h2 server to loopback IP runs with the default database password`() {
        driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = isQuasarAgentSpecified(),
                notarySpecs = emptyList(),
                cordappsForAllNodes = emptyList()
        )) {
            startNode(customOverrides = mapOf(h2AddressKey to "127.0.0.1:${getFreePort()}")).getOrThrow()
        }
    }

    @Test(timeout=300_000)
	fun `remote code execution via h2 server is disabled`() {
        driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = false,
                notarySpecs = emptyList(),
                cordappsForAllNodes = emptyList()
        )) {
            val port = getFreePort()
            startNode(customOverrides = mapOf(h2AddressKey to "localhost:$port", dbPasswordKey to "x")).getOrThrow()
            DriverManager.getConnection("jdbc:h2:tcp://localhost:$port/node", "sa", "x").use {
                assertFailsWith(JdbcSQLNonTransientException::class) {
                    it.createStatement().execute("CREATE ALIAS SET_PROPERTY FOR \"java.lang.System.setProperty\"")
                    it.createStatement().execute("CALL SET_PROPERTY('abc', '1')")
                }
            }
            assertNull(System.getProperty("abc"))
        }
    }

    @Test(timeout=300_000)
	fun `malicious flow tries to enable remote code execution via h2 server`() {
        val user = User("mark", "dadada", setOf(Permissions.startFlow<MaliciousFlow>()))
        driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = false,
                notarySpecs = emptyList(),
                cordappsForAllNodes = listOf(enclosedCordapp())
        )) {
            val port = getFreePort()
            val nodeHandle = startNode(rpcUsers = listOf(user), customOverrides = mapOf(h2AddressKey to "localhost:$port",
                    dbPasswordKey to "x")).getOrThrow()
            CordaRPCClient(nodeHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(::MaliciousFlow).returnValue.getOrThrow()
            }
            DriverManager.getConnection("jdbc:h2:tcp://localhost:$port/node", "sa", "x").use {
                assertFailsWith(JdbcSQLNonTransientException::class) {
                    it.createStatement().execute("CREATE ALIAS SET_PROPERTY FOR \"java.lang.System.setProperty\"")
                    it.createStatement().execute("CALL SET_PROPERTY('abc', '1')")
                }
            }
            assertNull(System.getProperty("abc"))
        }
    }

    @StartableByRPC
    class MaliciousFlow : FlowLogic<Boolean>() {
        @Suspendable
        override fun call(): Boolean {
            System.clearProperty("h2.allowedClasses")
            return true
        }
    }
}