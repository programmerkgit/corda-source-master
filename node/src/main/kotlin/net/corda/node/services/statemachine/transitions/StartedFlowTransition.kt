package net.corda.node.services.statemachine.transitions

import net.corda.core.flows.FlowInfo
import net.corda.core.flows.FlowSession
import net.corda.core.flows.UnexpectedFlowEndException
import net.corda.core.internal.FlowIORequest
import net.corda.core.serialization.SerializedBytes
import net.corda.core.utilities.toNonEmptySet
import net.corda.node.services.statemachine.*
import java.lang.IllegalStateException

/**
 * This transition describes what should happen with a specific [FlowIORequest]. Note that at this time the request
 * is persisted (unless checkpoint was skipped) and the user-space DB transaction is commited.
 *
 * Before this transition we either did a checkpoint or the checkpoint was restored from the database.
 */
class StartedFlowTransition(
        override val context: TransitionContext,
        override val startingState: StateMachineState,
        val started: FlowState.Started
) : Transition {
    override fun transition(): TransitionResult {
        val flowIORequest = started.flowIORequest
        val checkpoint = startingState.checkpoint
        val errorsToThrow = collectRelevantErrorsToThrow(flowIORequest, checkpoint)
        if (errorsToThrow.isNotEmpty()) {
            return TransitionResult(
                    newState = startingState.copy(isFlowResumed = true),
                    // throw the first exception. TODO should this aggregate all of them somehow?
                    actions = listOf(Action.CreateTransaction),
                    continuation = FlowContinuation.Throw(errorsToThrow[0])
            )
        }
        return when (flowIORequest) {
            is FlowIORequest.Send -> sendTransition(flowIORequest)
            is FlowIORequest.Receive -> receiveTransition(flowIORequest)
            is FlowIORequest.SendAndReceive -> sendAndReceiveTransition(flowIORequest)
            is FlowIORequest.WaitForLedgerCommit -> waitForLedgerCommitTransition(flowIORequest)
            is FlowIORequest.Sleep -> sleepTransition(flowIORequest)
            is FlowIORequest.GetFlowInfo -> getFlowInfoTransition(flowIORequest)
            is FlowIORequest.WaitForSessionConfirmations -> waitForSessionConfirmationsTransition()
            is FlowIORequest.ExecuteAsyncOperation<*> -> executeAsyncOperation(flowIORequest)
            FlowIORequest.ForceCheckpoint -> executeForceCheckpoint()
        }
    }

    private fun waitForSessionConfirmationsTransition(): TransitionResult {
        return builder {
            if (currentState.checkpoint.checkpointState.sessions.values.any { it is SessionState.Initiating }) {
                FlowContinuation.ProcessEvents
            } else {
                resumeFlowLogic(Unit)
            }
        }
    }

    private fun getFlowInfoTransition(flowIORequest: FlowIORequest.GetFlowInfo): TransitionResult {
        val sessionIdToSession = LinkedHashMap<SessionId, FlowSessionImpl>()
        for (session in flowIORequest.sessions) {
            sessionIdToSession[(session as FlowSessionImpl).sourceSessionId] = session
        }
        return builder {
            // Initialise uninitialised sessions in order to receive the associated FlowInfo. Some or all sessions may
            // not be initialised yet.
            sendInitialSessionMessagesIfNeeded(sessionIdToSession.keys)
            val flowInfoMap = getFlowInfoFromSessions(sessionIdToSession)
            if (flowInfoMap == null) {
                FlowContinuation.ProcessEvents
            } else {
                resumeFlowLogic(flowInfoMap)
            }
        }
    }

    private fun TransitionBuilder.getFlowInfoFromSessions(sessionIdToSession: Map<SessionId, FlowSessionImpl>): Map<FlowSession, FlowInfo>? {
        val checkpoint = currentState.checkpoint
        val resultMap = LinkedHashMap<FlowSession, FlowInfo>()
        for ((sessionId, session) in sessionIdToSession) {
            val sessionState = checkpoint.checkpointState.sessions[sessionId]
            if (sessionState is SessionState.Initiated) {
                resultMap[session] = sessionState.peerFlowInfo
            } else {
                return null
            }
        }
        return resultMap
    }

    private fun sleepTransition(flowIORequest: FlowIORequest.Sleep): TransitionResult {
        // This ensures that the [Sleep] request is not executed multiple times if extra
        // [DoRemainingWork] events are pushed onto the fiber's event queue before the flow has really woken up
        return if (!startingState.isWaitingForFuture) {
            builder {
                currentState = currentState.copy(isWaitingForFuture = true)
                actions.add(Action.SleepUntil(currentState, flowIORequest.wakeUpAfter))
                FlowContinuation.ProcessEvents
            }
        } else {
            TransitionResult(startingState)
        }
    }

    private fun waitForLedgerCommitTransition(flowIORequest: FlowIORequest.WaitForLedgerCommit): TransitionResult {
        // This ensures that the [WaitForLedgerCommit] request is not executed multiple times if extra
        // [DoRemainingWork] events are pushed onto the fiber's event queue before the flow has really woken up
        return if (!startingState.isWaitingForFuture) {
            TransitionResult(
                newState = startingState.copy(isWaitingForFuture = true),
                actions = listOf(
                    Action.CreateTransaction,
                    Action.TrackTransaction(flowIORequest.hash),
                    Action.CommitTransaction
                )
            )
        } else {
            TransitionResult(startingState)
        }
    }

    private fun sendAndReceiveTransition(flowIORequest: FlowIORequest.SendAndReceive): TransitionResult {
        val sessionIdToMessage = LinkedHashMap<SessionId, SerializedBytes<Any>>()
        val sessionIdToSession = LinkedHashMap<SessionId, FlowSessionImpl>()
        for ((session, message) in flowIORequest.sessionToMessage) {
            val sessionId = (session as FlowSessionImpl).sourceSessionId
            sessionIdToMessage[sessionId] = message
            sessionIdToSession[sessionId] = session
        }
        return builder {
            sendToSessionsTransition(sessionIdToMessage)
            if (isErrored()) {
                FlowContinuation.ProcessEvents
            } else {
                val receivedMap = receiveFromSessionsTransition(sessionIdToSession)
                if (receivedMap == null) {
                    // We don't yet have the messages, change the suspension to be on Receive
                    val newIoRequest = FlowIORequest.Receive(flowIORequest.sessionToMessage.keys.toNonEmptySet())
                    currentState = currentState.copy(
                            checkpoint = currentState.checkpoint.copy(
                                    flowState = FlowState.Started(newIoRequest, started.frozenFiber)
                            )
                    )
                    FlowContinuation.ProcessEvents
                } else {
                    resumeFlowLogic(receivedMap)
                }
            }
        }
    }

    private fun receiveTransition(flowIORequest: FlowIORequest.Receive): TransitionResult {
        return builder {
            val sessionIdToSession = LinkedHashMap<SessionId, FlowSessionImpl>()
            for (session in flowIORequest.sessions) {
                sessionIdToSession[(session as FlowSessionImpl).sourceSessionId] = session
            }
            // send initialises to uninitialised sessions
            sendInitialSessionMessagesIfNeeded(sessionIdToSession.keys)
            val receivedMap = receiveFromSessionsTransition(sessionIdToSession)
            if (receivedMap == null) {
                FlowContinuation.ProcessEvents
            } else {
                resumeFlowLogic(receivedMap)
            }
        }
    }

    private fun TransitionBuilder.receiveFromSessionsTransition(
            sourceSessionIdToSessionMap: Map<SessionId, FlowSessionImpl>
    ): Map<FlowSession, SerializedBytes<Any>>? {
        val checkpoint = currentState.checkpoint
        val pollResult = pollSessionMessages(checkpoint.checkpointState.sessions, sourceSessionIdToSessionMap.keys) ?: return null
        val resultMap = LinkedHashMap<FlowSession, SerializedBytes<Any>>()
        for ((sessionId, message) in pollResult.messages) {
            val session = sourceSessionIdToSessionMap[sessionId]!!
            resultMap[session] = message
        }
        currentState = currentState.copy(
                checkpoint = checkpoint.setSessions(sessions = pollResult.newSessionMap)
        )
        return resultMap
    }

    data class PollResult(
            val messages: Map<SessionId, SerializedBytes<Any>>,
            val newSessionMap: SessionMap
    )
    private fun pollSessionMessages(sessions: SessionMap, sessionIds: Set<SessionId>): PollResult? {
        val newSessionMessages = LinkedHashMap(sessions)
        val resultMessages = LinkedHashMap<SessionId, SerializedBytes<Any>>()
        var someNotFound = false
        for (sessionId in sessionIds) {
            val sessionState = sessions[sessionId]
            when (sessionState) {
                is SessionState.Initiated -> {
                    val messages = sessionState.receivedMessages
                    if (messages.isEmpty()) {
                        someNotFound = true
                    } else {
                        newSessionMessages[sessionId] = sessionState.copy(receivedMessages = messages.subList(1, messages.size).toList())
                        resultMessages[sessionId] = messages[0].payload
                    }
                }
                else -> {
                    someNotFound = true
                }
            }
        }
        return if (someNotFound) {
            return null
        } else {
            PollResult(resultMessages, newSessionMessages)
        }
    }

    private fun TransitionBuilder.sendInitialSessionMessagesIfNeeded(sourceSessions: Set<SessionId>) {
        val checkpoint = startingState.checkpoint
        val newSessions = LinkedHashMap<SessionId, SessionState>(checkpoint.checkpointState.sessions)
        var index = 0
        for (sourceSessionId in sourceSessions) {
            val sessionState = checkpoint.checkpointState.sessions[sourceSessionId]
            if (sessionState == null) {
                return freshErrorTransition(CannotFindSessionException(sourceSessionId))
            }
            if (sessionState !is SessionState.Uninitiated) {
                continue
            }
            val initialMessage = createInitialSessionMessage(sessionState.initiatingSubFlow, sourceSessionId, sessionState.additionalEntropy, null)
            val newSessionState = SessionState.Initiating(
                    bufferedMessages = emptyList(),
                    rejectionError = null,
                    deduplicationSeed = sessionState.deduplicationSeed
            )
            val deduplicationId = DeduplicationId.createForNormal(checkpoint, index++, newSessionState)
            actions.add(Action.SendInitial(sessionState.destination, initialMessage, SenderDeduplicationId(deduplicationId, startingState.senderUUID)))
            newSessions[sourceSessionId] = newSessionState
        }
        currentState = currentState.copy(checkpoint = checkpoint.setSessions(sessions = newSessions))
    }

    private fun sendTransition(flowIORequest: FlowIORequest.Send): TransitionResult {
        return builder {
            val sessionIdToMessage = flowIORequest.sessionToMessage.mapKeys {
                sessionToSessionId(it.key)
            }
            sendToSessionsTransition(sessionIdToMessage)
            if (isErrored()) {
                FlowContinuation.ProcessEvents
            } else {
                resumeFlowLogic(Unit)
            }
        }
    }

    private fun TransitionBuilder.sendToSessionsTransition(sourceSessionIdToMessage: Map<SessionId, SerializedBytes<Any>>) {
        val checkpoint = startingState.checkpoint
        val newSessions = LinkedHashMap(checkpoint.checkpointState.sessions)
        var index = 0
        for ((sourceSessionId, _) in sourceSessionIdToMessage) {
            val existingSessionState = checkpoint.checkpointState.sessions[sourceSessionId] ?: return freshErrorTransition(CannotFindSessionException(sourceSessionId))
            if (existingSessionState is SessionState.Initiated && existingSessionState.initiatedState is InitiatedSessionState.Ended) {
                return freshErrorTransition(IllegalStateException("Tried to send to ended session $sourceSessionId"))
            }
        }

        val messagesByType = sourceSessionIdToMessage.toList()
                .map { (sourceSessionId, message) -> Triple(sourceSessionId, checkpoint.checkpointState.sessions[sourceSessionId]!!, message) }
                .groupBy { it.second::class }

        val sendInitialActions = messagesByType[SessionState.Uninitiated::class]?.map { (sourceSessionId, sessionState, message) ->
            val uninitiatedSessionState = sessionState as SessionState.Uninitiated
            val deduplicationId = DeduplicationId.createForNormal(checkpoint, index++, sessionState)
            val initialMessage = createInitialSessionMessage(uninitiatedSessionState.initiatingSubFlow, sourceSessionId, uninitiatedSessionState.additionalEntropy, message)
            newSessions[sourceSessionId] = SessionState.Initiating(
                    bufferedMessages = emptyList(),
                    rejectionError = null,
                    deduplicationSeed = uninitiatedSessionState.deduplicationSeed
            )
            Action.SendInitial(uninitiatedSessionState.destination, initialMessage, SenderDeduplicationId(deduplicationId, startingState.senderUUID))
        } ?: emptyList()
        messagesByType[SessionState.Initiating::class]?.forEach { (sourceSessionId, sessionState, message) ->
            val initiatingSessionState = sessionState as SessionState.Initiating
            val sessionMessage = DataSessionMessage(message)
            val deduplicationId = DeduplicationId.createForNormal(checkpoint, index++, initiatingSessionState)
            val newBufferedMessages = initiatingSessionState.bufferedMessages + Pair(deduplicationId, sessionMessage)
            newSessions[sourceSessionId] = initiatingSessionState.copy(bufferedMessages = newBufferedMessages)
        }
        val sendExistingActions = messagesByType[SessionState.Initiated::class]?.mapNotNull {(_, sessionState, message) ->
            val initiatedSessionState = sessionState as SessionState.Initiated
            if (initiatedSessionState.initiatedState !is InitiatedSessionState.Live)
                null
            else {
                val sessionMessage = DataSessionMessage(message)
                val deduplicationId = DeduplicationId.createForNormal(checkpoint, index++, initiatedSessionState)
                val sinkSessionId = initiatedSessionState.initiatedState.peerSinkSessionId
                val existingMessage = ExistingSessionMessage(sinkSessionId, sessionMessage)
                Action.SendExisting(initiatedSessionState.peerParty, existingMessage, SenderDeduplicationId(deduplicationId, startingState.senderUUID))
            }
        } ?: emptyList()

        if (sendInitialActions.isNotEmpty() || sendExistingActions.isNotEmpty()) {
            actions.add(Action.SendMultiple(sendInitialActions, sendExistingActions))
        }
        currentState = currentState.copy(checkpoint = checkpoint.setSessions(newSessions))
    }

    private fun sessionToSessionId(session: FlowSession): SessionId {
        return (session as FlowSessionImpl).sourceSessionId
    }

    private fun collectErroredSessionErrors(sessionIds: Collection<SessionId>, checkpoint: Checkpoint): List<Throwable> {
        return sessionIds.flatMap { sessionId ->
            val sessionState = checkpoint.checkpointState.sessions[sessionId]!!
            when (sessionState) {
                is SessionState.Uninitiated -> emptyList()
                is SessionState.Initiating -> {
                    if (sessionState.rejectionError == null) {
                        emptyList()
                    } else {
                        listOf(sessionState.rejectionError.exception)
                    }
                }
                is SessionState.Initiated -> sessionState.errors.map(FlowError::exception)
            }
        }
    }

    private fun collectErroredInitiatingSessionErrors(checkpoint: Checkpoint): List<Throwable> {
        return checkpoint.checkpointState.sessions.values.mapNotNull { sessionState ->
            (sessionState as? SessionState.Initiating)?.rejectionError?.exception
        }
    }

    private fun collectEndedSessionErrors(sessionIds: Collection<SessionId>, checkpoint: Checkpoint): List<Throwable> {
        return sessionIds.mapNotNull { sessionId ->
            val sessionState = checkpoint.checkpointState.sessions[sessionId]!!
            when (sessionState) {
                is SessionState.Initiated -> {
                    if (sessionState.initiatedState === InitiatedSessionState.Ended) {
                        UnexpectedFlowEndException(
                                "Tried to access ended session $sessionId",
                                cause = null,
                                originalErrorId = context.secureRandom.nextLong()
                        )
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
    }

    private fun collectEndedEmptySessionErrors(sessionIds: Collection<SessionId>, checkpoint: Checkpoint): List<Throwable> {
        return sessionIds.mapNotNull { sessionId ->
            val sessionState = checkpoint.checkpointState.sessions[sessionId]!!
            when (sessionState) {
                is SessionState.Initiated -> {
                    if (sessionState.initiatedState === InitiatedSessionState.Ended &&
                            sessionState.receivedMessages.isEmpty()) {
                        UnexpectedFlowEndException(
                                "Tried to access ended session $sessionId with empty buffer",
                                cause = null,
                                originalErrorId = context.secureRandom.nextLong()
                        )
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
    }

    private fun collectRelevantErrorsToThrow(flowIORequest: FlowIORequest<*>, checkpoint: Checkpoint): List<Throwable> {
        return when (flowIORequest) {
            is FlowIORequest.Send -> {
                val sessionIds = flowIORequest.sessionToMessage.keys.map(this::sessionToSessionId)
                collectErroredSessionErrors(sessionIds, checkpoint) + collectEndedSessionErrors(sessionIds, checkpoint)
            }
            is FlowIORequest.Receive -> {
                val sessionIds = flowIORequest.sessions.map(this::sessionToSessionId)
                collectErroredSessionErrors(sessionIds, checkpoint) + collectEndedEmptySessionErrors(sessionIds, checkpoint)
            }
            is FlowIORequest.SendAndReceive -> {
                val sessionIds = flowIORequest.sessionToMessage.keys.map(this::sessionToSessionId)
                collectErroredSessionErrors(sessionIds, checkpoint) + collectEndedSessionErrors(sessionIds, checkpoint)
            }
            is FlowIORequest.WaitForLedgerCommit -> {
                collectErroredSessionErrors(checkpoint.checkpointState.sessions.keys, checkpoint)
            }
            is FlowIORequest.GetFlowInfo -> {
                collectErroredSessionErrors(flowIORequest.sessions.map(this::sessionToSessionId), checkpoint)
            }
            is FlowIORequest.Sleep -> {
                emptyList()
            }
            is FlowIORequest.WaitForSessionConfirmations -> {
                collectErroredInitiatingSessionErrors(checkpoint)
            }
            is FlowIORequest.ExecuteAsyncOperation<*> -> {
                emptyList()
            }
            FlowIORequest.ForceCheckpoint -> {
                emptyList()
            }
        }
    }

    private fun createInitialSessionMessage(
            initiatingSubFlow: SubFlow.Initiating,
            sourceSessionId: SessionId,
            additionalEntropy: Long,
            payload: SerializedBytes<Any>?
    ): InitialSessionMessage {
        return InitialSessionMessage(
                initiatorSessionId = sourceSessionId,
                // We add additional entropy to add to the initiated side's deduplication seed.
                initiationEntropy = additionalEntropy,
                initiatorFlowClassName = initiatingSubFlow.classToInitiateWith.name,
                flowVersion = initiatingSubFlow.flowInfo.flowVersion,
                appName = initiatingSubFlow.flowInfo.appName,
                firstPayload = payload
        )
    }

    private fun executeAsyncOperation(flowIORequest: FlowIORequest.ExecuteAsyncOperation<*>): TransitionResult {
        // This ensures that the [ExecuteAsyncOperation] request is not executed multiple times if extra
        // [DoRemainingWork] events are pushed onto the fiber's event queue before the flow has really woken up
        return if (!startingState.isWaitingForFuture) {
            builder {
                // The `numberOfSuspends` is added to the deduplication ID in case an async
                // operation is executed multiple times within the same flow.
                val deduplicationId = context.id.toString() + ":" + currentState.checkpoint.checkpointState.numberOfSuspends.toString()
                actions.add(Action.ExecuteAsyncOperation(deduplicationId, flowIORequest.operation))
                currentState = currentState.copy(isWaitingForFuture = true)
                FlowContinuation.ProcessEvents
            }
        } else {
            TransitionResult(startingState)
        }
    }

    private fun executeForceCheckpoint(): TransitionResult {
        return builder { resumeFlowLogic(Unit) }
    }
}
