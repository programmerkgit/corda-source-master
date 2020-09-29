package net.corda.node.logging

import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.internal.div
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.driver
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

class ErrorCodeLoggingTests {
    @Test(timeout=300_000)
	fun `log entries with a throwable and ERROR or WARN get an error code appended`() {
        driver(DriverParameters(notarySpecs = emptyList())) {
            val node = startNode(startInSameProcess = false).getOrThrow()
            node.rpc.startFlow(::MyFlow).waitForCompletion()
            val logFile = node.logFile()

            val linesWithErrorCode = logFile.useLines { lines ->
                lines.filter { line ->
                    line.contains("[errorCode=")
                }.filter { line ->
                    line.contains("moreInformationAt=https://errors.corda.net/")
                }.toList()
            }

            assertThat(linesWithErrorCode).isNotEmpty
        }
    }

    // This is used to detect broken logging which can be caused by loggers being initialized
    // before the initLogging() call is made
    @Test(timeout=300_000)
	fun `When logging is set to error level, there are no other levels logged after node startup`() {
        driver(DriverParameters(notarySpecs = emptyList())) {
            val node = startNode(startInSameProcess = false, logLevelOverride = "ERROR").getOrThrow()
            val logFile = node.logFile()
            val lengthAfterStart = logFile.length()
            node.rpc.startFlow(::MyFlow).waitForCompletion()
            // An exception thrown in a flow will log at the "INFO" level.
            assertThat(logFile.length()).isEqualTo(lengthAfterStart)
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class MyFlow : FlowLogic<String>() {
        override fun call(): String {
            throw IllegalArgumentException("Mwahahahah")
        }
    }
}

private fun FlowHandle<*>.waitForCompletion() {
    try {
        returnValue.getOrThrow()
    } catch (e: Exception) {
        // This is expected to throw an exception, using getOrThrow() just to wait until done.
    }
}

fun NodeHandle.logFile(): File = (baseDirectory / "logs").toFile().walk().filter { it.name.startsWith("node-") && it.extension == "log" }.single()