package kz.p2pmessenger.internet

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

/** Retains the active PeerConnection while rotating or switching apps to copy signaling. */
class InternetViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(InternetState())
        private set
    val session = InternetSession(
        { listener -> WebRtcEngine(application, listener) },
        { state = it }
    )
    var input by mutableStateOf("")
    var draft by mutableStateOf("")
    private val qrCollector = QrCollector()
    var qrProgress by mutableStateOf("")
        private set
    fun resetQr() { qrCollector.clear(); qrProgress = "" }
    fun acceptQr(value: String) {
        try {
            val signal = qrCollector.accept(value)
            qrProgress = qrCollector.progress
            if (signal != null) session.connect(signal.encode())
        } catch (e: IllegalArgumentException) {
            session.reportError(e.message ?: "Не удалось прочитать QR.")
        }
    }
    override fun onCleared() { session.disconnect() }
}
