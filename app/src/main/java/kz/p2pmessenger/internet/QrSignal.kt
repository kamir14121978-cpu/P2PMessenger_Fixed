package kz.p2pmessenger.internet

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** Lossless zlib + Base64URL. Bounded inflate, version validation, no URI execution. */
object QrSignal {
    const val SINGLE_LIMIT = 1900 // Below QR v40/M byte capacity (2331); ASCII only.
    const val CHUNK_SIZE = 1000
    const val MAX_PARTS = 100
    private const val MAX_RAW_BYTES = 65_000
    private const val MAX_PACKED_CHARS = 90_000

    fun frames(manual: String): List<String> {
        val signal = ManualSignal.decode(manual)
        val raw = "${signal.kind.name}\n${signal.sessionId}\n${signal.sdp}".toByteArray(Charsets.UTF_8)
        val bytes = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try { DeflaterOutputStream(bytes, deflater).use { it.write(raw) } }
        finally { deflater.end() }
        val packed = "P2PQ1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
        require(packed.length <= MAX_PACKED_CHARS) { "Данные слишком большие для QR. Используйте диагностику." }
        if (packed.length <= SINGLE_LIMIT) return listOf(packed)
        val chunks = packed.chunked(CHUNK_SIZE)
        val id = digest(packed)
        return chunks.mapIndexed { index, chunk -> "P2PF1.$id.${index + 1}.${chunks.size}.$chunk" }
    }

    internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.US_ASCII)).take(16).joinToString("") { "%02x".format(it) }

    internal fun unpack(packed: String): ManualSignal {
        require(packed.startsWith("P2PQ1.") && packed.length <= MAX_PACKED_CHARS) { "Это не QR подключения P2PMessenger." }
        val bytes = try { Base64.getUrlDecoder().decode(packed.removePrefix("P2PQ1.")) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("QR повреждён.") }
        val inflater = Inflater()
        val raw = ByteArrayOutputStream()
        try {
            inflater.setInput(bytes)
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                require(raw.size() + count <= MAX_RAW_BYTES) { "Превышен допустимый размер данных QR." }
                if (count > 0) raw.write(buffer, 0, count)
                else require(inflater.finished()) { "QR повреждён или неполон." }
            }
            require(inflater.remaining == 0) { "Лишние данные в QR." }
        } catch (e: java.util.zip.DataFormatException) {
            throw IllegalArgumentException("QR повреждён.", e)
        } finally { inflater.end() }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw.toByteArray())).toString()
        } catch (_: Exception) { throw IllegalArgumentException("Некорректный текст QR.") }
        val fields = text.split('\n', limit = 3)
        require(fields.size == 3) { "Неполные данные QR." }
        val kind = when (fields[0]) {
            "OFFER" -> SignalKind.OFFER
            "ANSWER" -> SignalKind.ANSWER
            else -> throw IllegalArgumentException("Неизвестный тип QR.")
        }
        return ManualSignal(kind, fields[1], fields[2]).also { it.encode() } // Reuse complete SDP validation.
    }
}

/** Survives camera launches via ViewModel; accepts out-of-order and duplicate frames. */
class QrCollector {
    private var id: String? = null
    private var total = 0
    private val chunks = mutableMapOf<Int, String>()
    val progress: String get() = if (total == 0) "" else "Считано QR: ${chunks.size} из $total. Сканируйте следующую часть."

    fun clear() { id = null; total = 0; chunks.clear() }

    fun accept(value: String): ManualSignal? {
        require(value.length <= QrSignal.SINGLE_LIMIT) { "QR слишком большой." }
        if (value.startsWith("P2PQ1.")) {
            return QrSignal.unpack(value).also { clear() }
        }
        val fields = value.split('.', limit = 5)
        require(fields.size == 5 && fields[0] == "P2PF1" &&
            fields[1].matches(Regex("[0-9a-f]{32}"))) { "Это не QR подключения P2PMessenger." }
        val index = fields[2].toIntOrNull() ?: 0
        val count = fields[3].toIntOrNull() ?: 0
        require(count in 2..QrSignal.MAX_PARTS && index in 1..count &&
            fields[4].length in 1..QrSignal.CHUNK_SIZE) { "Некорректная часть QR." }
        require(id == null || (id == fields[1] && total == count)) {
            "Этот QR из другого подключения. Сначала сбросьте сбор QR."
        }
        require(chunks[index] == null || chunks[index] == fields[4]) { "Противоречивые части QR." }
        id = fields[1]
        total = count
        chunks[index] = fields[4]
        if (chunks.size != total) return null
        val packed = (1..total).joinToString("") { chunks.getValue(it) }
        val expected = id
        clear()
        require(QrSignal.digest(packed) == expected) { "Части QR повреждены. Повторите сканирование." }
        return QrSignal.unpack(packed)
    }
}
