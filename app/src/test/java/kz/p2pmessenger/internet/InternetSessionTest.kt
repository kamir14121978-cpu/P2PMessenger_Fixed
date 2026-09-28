package kz.p2pmessenger.internet

import org.junit.Assert.*
import org.junit.Test

class InternetSessionTest {
    private class FakeEngine(val listener: RtcEngine.Listener) : RtcEngine {
        var offers = 0
        var remoteOffer: String? = null
        var remoteAnswer: String? = null
        var closed = false
        var acceptSend = true
        val sent = mutableListOf<String>()
        override fun createOffer() { offers++ }
        override fun acceptOffer(sdp: String) { remoteOffer = sdp }
        override fun acceptAnswer(sdp: String) { remoteAnswer = sdp }
        override fun send(text: String): Boolean { if (acceptSend) sent.add(text); return acceptSend }
        override fun close() { closed = true }
    }
    private class Fixture {
        val engines = mutableListOf<FakeEngine>()
        val session = InternetSession({ listener ->
            FakeEngine(listener).also { engines.add(it) }
        }, {})
        val engine get() = engines.last()
        fun offerReady(): ManualSignal {
            session.createOffer()
            engine.listener.onLocalSignal(SignalKind.OFFER, TEST_SDP)
            return ManualSignal.decode(session.state.output)
        }
        fun connect() {
            val offer = offerReady()
            session.connect(ManualSignal(SignalKind.ANSWER, offer.sessionId, TEST_SDP).encode())
            engine.listener.onLinkState(LinkState.CONNECTED)
        }
    }
    @Test fun offerIsNotCopyableUntilGatheringCompletes() {
        val f = Fixture()
        f.session.createOffer()
        assertEquals(Phase.GATHERING_OFFER, f.session.state.phase)
        assertEquals("", f.session.state.output)
        assertEquals(1, f.engine.offers)
        f.engine.listener.onLocalSignal(SignalKind.OFFER, TEST_SDP)
        assertEquals(Phase.WAITING_ANSWER, f.session.state.phase)
        assertFalse(f.session.state.output.isBlank())
    }
    @Test fun answerForCurrentOfferIsAppliedExactlyOnce() {
        val f = Fixture()
        val offer = f.offerReady()
        val answer = ManualSignal(SignalKind.ANSWER, offer.sessionId, TEST_SDP).encode()
        f.session.connect(answer)
        assertEquals(TEST_SDP, f.engine.remoteAnswer)
        assertEquals(Phase.CONNECTING, f.session.state.phase)
        f.session.connect(answer)
        assertNotNull(f.session.state.error)
        assertFalse(f.engine.closed)
    }
    @Test fun unrelatedAnswerDoesNotReplaceActiveOffer() {
        val f = Fixture()
        f.offerReady()
        f.session.connect(ManualSignal(SignalKind.ANSWER, TEST_ID, TEST_SDP).encode())
        assertNull(f.engine.remoteAnswer)
        assertEquals(Phase.WAITING_ANSWER, f.session.state.phase)
        assertNotNull(f.session.state.error)
    }
    @Test fun responderCreatesAnswerWithSameSessionId() {
        val f = Fixture()
        f.session.connect(ManualSignal(SignalKind.OFFER, TEST_ID, TEST_SDP).encode())
        assertEquals(TEST_SDP, f.engine.remoteOffer)
        assertEquals(Phase.GATHERING_ANSWER, f.session.state.phase)
        f.engine.listener.onLocalSignal(SignalKind.ANSWER, TEST_SDP)
        assertEquals(TEST_ID, ManualSignal.decode(f.session.state.output).sessionId)
        assertEquals(SignalKind.ANSWER, ManualSignal.decode(f.session.state.output).kind)
    }
    @Test fun invalidClipboardDoesNotDestroyConnection() {
        val f = Fixture()
        f.connect()
        f.session.connect("not signaling")
        assertEquals(Phase.CONNECTED, f.session.state.phase)
        assertFalse(f.engine.closed)
    }
    @Test fun simultaneousOffersRequireExplicitDisconnect() {
        val f = Fixture()
        f.offerReady()
        f.session.connect(ManualSignal(SignalKind.OFFER, TEST_ID, TEST_SDP).encode())
        assertEquals(1, f.engines.size)
        assertEquals(Phase.WAITING_ANSWER, f.session.state.phase)
        assertNotNull(f.session.state.error)
    }
    @Test fun lateCallbacksCannotReviveOldConnection() {
        val f = Fixture()
        f.offerReady()
        val old = f.engine
        f.session.disconnect()
        f.session.createOffer()
        old.listener.onLocalSignal(SignalKind.OFFER, TEST_SDP)
        old.listener.onLinkState(LinkState.CONNECTED)
        old.listener.onMessage("stale")
        old.listener.onError("stale")
        assertTrue(old.closed)
        assertEquals(Phase.GATHERING_OFFER, f.session.state.phase)
        assertEquals("", f.session.state.output)
        assertTrue(f.session.state.messages.isEmpty())
    }
    @Test fun sendRequiresOpenChannelAndNativeAcceptance() {
        val f = Fixture()
        f.offerReady()
        assertFalse(f.session.send("early"))
        f.engine.listener.onLinkState(LinkState.CONNECTED)
        f.engine.acceptSend = false
        assertFalse(f.session.send("not accepted"))
        assertTrue(f.session.state.messages.isEmpty())
        f.engine.acceptSend = true
        assertTrue(f.session.send("Привет 👋\nВторая строка"))
        assertEquals("Привет 👋\nВторая строка", f.engine.sent.single())
    }
    @Test fun messageLimitCountsUtf8BytesAndReceiveHistoryIsBounded() {
        val f = Fixture()
        f.connect()
        assertFalse(f.session.send("я".repeat(InternetSession.MAX_MESSAGE_BYTES)))
        assertFalse(f.session.send("  "))
        assertTrue(f.session.send("a".repeat(InternetSession.MAX_MESSAGE_BYTES)))
        repeat(220) { f.engine.listener.onMessage("Сообщение $it") }
        assertEquals(200, f.session.state.messages.size)
        assertEquals("Собеседник: Сообщение 219", f.session.state.messages.last())
    }
    @Test fun disconnectDisablesSendingAndCanRecover() {
        val f = Fixture()
        f.connect()
        f.engine.listener.onLinkState(LinkState.DISCONNECTED)
        assertFalse(f.session.send("offline"))
        f.engine.listener.onLinkState(LinkState.CONNECTED)
        assertTrue(f.session.send("back"))
    }
    @Test fun nativeFailureClosesEngineAndAllowsFreshOffer() {
        val f = Fixture()
        f.offerReady()
        val old = f.engine
        old.listener.onError("timeout")
        assertTrue(old.closed)
        assertEquals(Phase.FAILED, f.session.state.phase)
        assertEquals("", f.session.state.output)
        f.session.createOffer()
        assertEquals(2, f.engines.size)
    }
    @Test fun initializationFailureIsReported() {
        val session = InternetSession({ throw IllegalStateException("native failure") }, {})
        session.createOffer()
        assertEquals(Phase.FAILED, session.state.phase)
        assertNotNull(session.state.error)
    }
    @Test fun turnConfigurationSupportsRuntimeCredentials() {
        val server = IceServerConfig(listOf("turn:relay.example.org:3478", "turns:relay.example.org:5349"),
            username = "temporary-user", credential = "runtime-only")
        assertEquals(2, server.urls.size)
        assertTrue(IceServerConfig.TEST_STUN.all { config -> config.urls.all { it.startsWith("stun:") } })
        assertThrows(IllegalArgumentException::class.java) { IceServerConfig(listOf("https://invalid.example.org")) }
    }
}
