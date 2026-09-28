package kz.p2pmessenger.internet

import java.util.UUID

enum class Phase { IDLE, GATHERING_OFFER, WAITING_ANSWER, GATHERING_ANSWER, CONNECTING, CONNECTED, DISCONNECTED, FAILED }
data class InternetState(
    val phase: Phase = Phase.IDLE,
    val status: String = "Создайте подключение или вставьте offer второго телефона.",
    val output: String = "",
    val error: String? = null,
    val messages: List<String> = emptyList()
) {
    val canStart: Boolean get() = phase == Phase.IDLE || phase == Phase.FAILED
}

/** Pure Kotlin signaling state machine; no Android/native dependency in its tests. */
class InternetSession(
    private val engineFactory: (RtcEngine.Listener) -> RtcEngine,
    private val onChanged: (InternetState) -> Unit
) {
    var state = InternetState()
        private set
    private var engine: RtcEngine? = null
    private var generation = 0
    private var sessionId = ""

    private fun update(value: InternetState) { state = value; onChanged(value) }
    fun reportError(message: String) { update(state.copy(error = message)) }

    fun createOffer() {
        if (!state.canStart) return reportError("Сначала завершите текущее подключение.")
        begin(UUID.randomUUID().toString(), Phase.GATHERING_OFFER)
        runEngine { createOffer() }
    }

    fun connect(input: String) {
        val signal = try { ManualSignal.decode(input) }
        catch (e: IllegalArgumentException) { return reportError(e.message ?: "Некорректные данные.") }
        when (signal.kind) {
            SignalKind.OFFER -> {
                if (!state.canStart) return reportError("Сначала завершите текущее подключение. Ожидается answer, если вы создали offer.")
                begin(signal.sessionId, Phase.GATHERING_ANSWER)
                runEngine { acceptOffer(signal.sdp) }
            }
            SignalKind.ANSWER -> {
                if (state.phase != Phase.WAITING_ANSWER || signal.sessionId != sessionId) {
                    return reportError("Этот answer не относится к текущему offer либо уже применён.")
                }
                update(state.copy(phase = Phase.CONNECTING, status = "Устанавливается соединение…", error = null))
                runEngine { acceptAnswer(signal.sdp) }
            }
        }
    }

    private fun begin(id: String, phase: Phase) {
        release()
        sessionId = id
        update(InternetState(phase, "Сбор ICE-кандидатов… Дождитесь данных для копирования."))
        val current = generation
        val listener = object : RtcEngine.Listener {
            override fun onLocalSignal(kind: SignalKind, sdp: String) {
                if (current != generation) return
                try {
                    val output = ManualSignal(kind, sessionId, sdp).encode()
                    update(state.copy(
                        phase = if (state.phase == Phase.CONNECTED) Phase.CONNECTED
                            else if (kind == SignalKind.OFFER) Phase.WAITING_ANSWER else Phase.CONNECTING,
                        status = if (state.phase == Phase.CONNECTED) "Канал открыт"
                            else if (kind == SignalKind.OFFER) "Offer готов. Передайте его второму телефону и вставьте его answer."
                            else "Answer готов. Передайте его телефону, создавшему offer.",
                        output = output, error = null
                    ))
                } catch (e: IllegalArgumentException) { fail(e.message ?: "Ошибка SDP.") }
            }
            override fun onLinkState(state: LinkState) {
                if (current != generation) return
                when (state) {
                    LinkState.CONNECTED -> update(this@InternetSession.state.copy(
                        phase = Phase.CONNECTED, status = "Канал открыт • WebRTC DataChannel", error = null))
                    LinkState.DISCONNECTED -> update(this@InternetSession.state.copy(
                        phase = Phase.DISCONNECTED, status = "Связь прервана. Ожидание восстановления…"))
                    LinkState.FAILED -> fail("Прямое соединение не удалось. Попробуйте другую сеть и новый offer; этой сети может требоваться TURN.")
                    LinkState.CLOSED -> fail("Канал закрыт. Создайте новое подключение.")
                    LinkState.CONNECTING -> Unit // Keep offer/answer instructions visible.
                }
            }
            override fun onMessage(text: String) {
                if (current == generation && text.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
                    append("Собеседник: $text")
                }
            }
            override fun onError(message: String) { if (current == generation) fail(message) }
        }
        try { engine = engineFactory(listener) }
        catch (_: Exception) { fail("Не удалось запустить WebRTC на этом устройстве.") }
        catch (_: LinkageError) { fail("WebRTC недоступен для этого устройства.") }
    }

    private fun runEngine(action: RtcEngine.() -> Unit) {
        try { engine?.action() }
        catch (_: Exception) { fail("Ошибка WebRTC. Завершите подключение и создайте новое.") }
    }

    fun send(text: String): Boolean {
        if (state.phase != Phase.CONNECTED) { reportError("Дождитесь открытия канала."); return false }
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
            reportError("Введите сообщение размером до 16 КиБ."); return false
        }
        val sent = runCatching { engine?.send(text) == true }.getOrDefault(false)
        if (sent) {
            update(state.copy(error = null))
            append("Я: $text")
        } else reportError("Канал не принял сообщение. Дождитесь соединения или повторите отправку.")
        return sent
    }

    private fun append(message: String) {
        update(state.copy(messages = (state.messages + message).takeLast(200)))
    }
    private fun fail(message: String) {
        release()
        update(state.copy(phase = Phase.FAILED, output = "", status = "Соединение завершено", error = message))
    }
    private fun release() {
        generation++ // Invalidate callbacks before native disposal.
        val old = engine
        engine = null
        old?.close()
    }
    fun disconnect() {
        release()
        sessionId = ""
        update(InternetState(messages = state.messages))
    }
    companion object { const val MAX_MESSAGE_BYTES = 16 * 1024 }
}
