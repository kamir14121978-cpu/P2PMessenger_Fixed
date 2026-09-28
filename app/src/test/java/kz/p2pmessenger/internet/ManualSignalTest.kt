package kz.p2pmessenger.internet

import org.junit.Assert.*
import org.junit.Test

internal const val TEST_ID = "11111111-2222-4333-8444-555555555555"
internal val TEST_SDP = listOf(
    "v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0",
    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
    "c=IN IP4 0.0.0.0", "a=ice-ufrag:test", "a=ice-pwd:long-enough-test-password",
    "a=fingerprint:sha-256 AA:BB:CC", "a=sctp-port:5000",
    "a=candidate:1 1 udp 2122260223 192.0.2.1 12345 typ host", ""
).joinToString("\r\n")

class ManualSignalTest {
    @Test fun roundTripOfferAndAnswerPreservesCompleteSdp() {
        for (kind in SignalKind.entries) {
            val original = ManualSignal(kind, TEST_ID, TEST_SDP)
            assertEquals(original, ManualSignal.decode(original.encode()))
        }
    }
    @Test fun wrappedClipboardTextIsAccepted() {
        val signal = ManualSignal(SignalKind.OFFER, TEST_ID, TEST_SDP)
        assertEquals(signal, ManualSignal.decode(" \n" + signal.encode().chunked(40).joinToString("\n") + " "))
    }
    @Test fun rejectsUnknownVersionAndType() {
        val valid = ManualSignal(SignalKind.OFFER, TEST_ID, TEST_SDP).encode()
        assertThrows(IllegalArgumentException::class.java) { ManualSignal.decode(valid.replace("P2PM1", "P2PM2")) }
        assertThrows(IllegalArgumentException::class.java) { ManualSignal.decode(valid.replace(".offer.", ".other.")) }
    }
    @Test fun rejectsMalformedAndTruncatedData() {
        for (value in listOf("", "offer", "P2PM1.offer.$TEST_ID.%%%%", "P2PM1.offer.bad.aA")) {
            assertThrows(IllegalArgumentException::class.java) { ManualSignal.decode(value) }
        }
    }
    @Test fun rejectsOversizedInputBeforeDecoding() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualSignal.decode(" ".repeat(ManualSignal.MAX_INPUT_CHARS + 1))
        }
    }
    @Test fun rejectsMissingCandidatesAndMediaOffers() {
        for (sdp in listOf(
            TEST_SDP.lineSequence().filterNot { it.startsWith("a=candidate:") }.joinToString("\r\n"),
            TEST_SDP + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n",
            TEST_SDP.replace("a=fingerprint:", "a=other:"),
            TEST_SDP.replace("webrtc-datachannel", "other")
        )) {
            assertThrows(IllegalArgumentException::class.java) { ManualSignal(SignalKind.OFFER, TEST_ID, sdp).encode() }
        }
    }
}
