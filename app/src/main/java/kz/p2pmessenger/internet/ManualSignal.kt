package kz.p2pmessenger.internet

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

enum class SignalKind { OFFER, ANSWER }

/** Versioned, copyable envelope. SDP includes every ICE candidate (non-trickle ICE). */
data class ManualSignal(val kind: SignalKind, val sessionId: String, val sdp: String) {
    fun encode(): String {
        validate(sessionId, sdp)
        return "P2PM1.${kind.name.lowercase()}.$sessionId." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(sdp.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val MAX_INPUT_CHARS = 100_000
        private const val MAX_SDP_BYTES = 64_000

        fun decode(input: String): ManualSignal {
            require(input.length <= MAX_INPUT_CHARS) { "Данные подключения слишком длинные." }
            val parts = input.filterNot { it.isWhitespace() }.split('.')
            require(parts.size == 4 && parts[0] == "P2PM1") {
                "Вставьте полные данные подключения P2PM1 из P2PMessenger 0.3."
            }
            val kind = when (parts[1]) {
                "offer" -> SignalKind.OFFER
                "answer" -> SignalKind.ANSWER
                else -> throw IllegalArgumentException("Неизвестный тип данных подключения.")
            }
            val sdp = try {
                val bytes = Base64.getUrlDecoder().decode(parts[3])
                require(bytes.size <= MAX_SDP_BYTES)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                throw IllegalArgumentException("Данные повреждены или обрезаны. Скопируйте их целиком.")
            }
            validate(parts[2], sdp)
            return ManualSignal(kind, parts[2], sdp)
        }

        private fun validate(id: String, sdp: String) {
            require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
                "Некорректный идентификатор подключения."
            }
            val lines = sdp.lineSequence().toList()
            require(sdp.toByteArray(Charsets.UTF_8).size <= MAX_SDP_BYTES &&
                lines.firstOrNull() == "v=0" &&
                lines.any { it.startsWith("m=application ") && it.contains("webrtc-datachannel") } &&
                lines.none { it.startsWith("m=audio ") || it.startsWith("m=video ") } &&
                lines.any { it.startsWith("a=ice-ufrag:") } &&
                lines.any { it.startsWith("a=ice-pwd:") } &&
                lines.any { it.startsWith("a=fingerprint:") } &&
                lines.any { it.startsWith("a=candidate:") }) {
                "Неполные данные WebRTC. Дождитесь завершения сбора ICE и скопируйте заново."
            }
        }
    }
}
