package kz.p2pmessenger.internet

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.zip.DeflaterOutputStream

class QrSignalTest {
    private fun realisticSdp(candidates: Int): String {
        val random = Random(42)
        val fingerprint = (1..32).joinToString(":") { "%02X".format(random.nextInt(256)) }
        val header = listOf(
            "v=0", "o=- 123456789012345 2 IN IP4 127.0.0.1", "s=-", "t=0 0",
            "a=group:BUNDLE 0", "a=extmap-allow-mixed", "a=msid-semantic: WMS",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel", "c=IN IP4 0.0.0.0",
            "a=ice-ufrag:randomUfrag", "a=ice-pwd:randomGeneratedPasswordForTesting",
            "a=ice-options:trickle", "a=fingerprint:sha-256 $fingerprint", "a=setup:actpass",
            "a=mid:0", "a=sctp-port:5000", "a=max-message-size:262144"
        )
        val ice = (1..candidates).map { index ->
            val ip = (1..8).joinToString(":") { random.nextInt(65536).toString(16) }
            "a=candidate:${random.nextInt(Int.MAX_VALUE)} 1 udp 2122260223 $ip ${10000 + index} typ host generation 0 network-id $index network-cost 10"
        }
        return (header + ice + "").joinToString("\r\n")
    }

    private fun scanImage(text: String): String {
        val matrix = QrCode.matrix(text)
        val pixels = IntArray(matrix.width * matrix.height) {
            if (matrix[it % matrix.width, it / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test fun offerAndAnswerSurviveCompressionAndRealQrImageDecode() {
        for (kind in SignalKind.entries) {
            val signal = ManualSignal(kind, TEST_ID, realisticSdp(4))
            val frames = QrSignal.frames(signal.encode())
            assertEquals(1, frames.size)
            assertEquals(signal, QrCollector().accept(scanImage(frames.single())))
        }
    }

    @Test fun measureCapacityForCurrentEnvelopeWithoutDroppingAnyCandidates() {
        for (count in listOf(4, 16, 80, 200)) {
            val signal = ManualSignal(SignalKind.OFFER, TEST_ID, realisticSdp(count))
            val manual = signal.encode()
            val frames = QrSignal.frames(manual)
            println("ICE=$count SDP_BYTES=${signal.sdp.toByteArray().size} MANUAL_CHARS=${manual.length} QR_FRAMES=${frames.size} MAX_FRAME=${frames.maxOf { it.length }}")
            val collector = QrCollector()
            var decoded: ManualSignal? = null
            for (frame in frames) decoded = collector.accept(scanImage(frame))
            assertEquals(signal, decoded)
        }
    }

    @Test fun maximumSingleFrameFitsQrAtMediumErrorCorrection() {
        val value = "a".repeat(QrSignal.SINGLE_LIMIT)
        assertEquals(value, scanImage(value))
    }

    @Test fun multipartWorksOutOfOrderAndDuplicatesDoNotCountTwice() {
        val signal = ManualSignal(SignalKind.ANSWER, TEST_ID, realisticSdp(80))
        val frames = QrSignal.frames(signal.encode())
        assertTrue(frames.size > 1)
        val collector = QrCollector()
        assertNull(collector.accept(frames.last()))
        val before = collector.progress
        assertNull(collector.accept(frames.last()))
        assertEquals(before, collector.progress)
        var result: ManualSignal? = null
        for (frame in frames.dropLast(1).reversed()) result = collector.accept(frame)
        assertEquals(signal, result)
        assertEquals("", collector.progress)
    }

    @Test fun rejectsMixedSessionsAndCanReset() {
        val offer = ManualSignal(SignalKind.OFFER, TEST_ID, realisticSdp(80))
        val a = QrSignal.frames(offer.encode())
        val b = QrSignal.frames(offer.copy(kind = SignalKind.ANSWER).encode())
        val collector = QrCollector()
        collector.accept(a.first())
        assertThrows(IllegalArgumentException::class.java) { collector.accept(b.last()) }
        collector.clear()
        assertNull(collector.accept(b.first()))
    }

    @Test fun rejectsCorruptedFrameSet() {
        val frames = QrSignal.frames(ManualSignal(SignalKind.OFFER, TEST_ID, realisticSdp(80)).encode())
        val collector = QrCollector()
        frames.dropLast(1).forEach { collector.accept(it) }
        val last = frames.last()
        val corrupt = last.dropLast(1) + if (last.last() == 'A') "B" else "A"
        assertThrows(IllegalArgumentException::class.java) { collector.accept(corrupt) }
        assertEquals("", collector.progress)
    }

    @Test fun rejectsMalformedQrAndInvalidCounts() {
        val id = "a".repeat(32)
        for (text in listOf("https://example.org", "P2PQ2.abc", "P2PQ1.%%%",
            "P2PF1.$id.0.2.abc", "P2PF1.$id.1.101.abc", "x".repeat(1901))) {
            assertThrows(IllegalArgumentException::class.java) { QrCollector().accept(text) }
        }
    }

    @Test fun rejectsDecompressionBombAndTrailingBytes() {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(ByteArray(500_000) { 65 }) }
        val bomb = "P2PQ1." + Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
        assertTrue(bomb.length <= QrSignal.SINGLE_LIMIT)
        assertThrows(IllegalArgumentException::class.java) { QrCollector().accept(bomb) }
        val good = QrSignal.frames(ManualSignal(SignalKind.OFFER, TEST_ID, TEST_SDP).encode()).single()
        val trailing = Base64.getUrlDecoder().decode(good.removePrefix("P2PQ1.")) + byteArrayOf(0)
        assertThrows(IllegalArgumentException::class.java) {
            QrCollector().accept("P2PQ1." + Base64.getUrlEncoder().withoutPadding().encodeToString(trailing))
        }
    }

    @Test fun preservesUnicodeAndLineEndingsExactly() {
        val signal = ManualSignal(SignalKind.OFFER, TEST_ID, TEST_SDP + "a=x-note:Привет 👋\r\n")
        assertEquals(signal, QrCollector().accept(QrSignal.frames(signal.encode()).single()))
    }
}
