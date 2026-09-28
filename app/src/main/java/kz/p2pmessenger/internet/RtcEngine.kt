package kz.p2pmessenger.internet

enum class LinkState { CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** Single-use transport. Calls and listener events are serialized on the UI thread. */
interface RtcEngine {
    interface Listener {
        fun onLocalSignal(kind: SignalKind, sdp: String)
        fun onLinkState(state: LinkState)
        fun onMessage(text: String)
        fun onError(message: String)
    }
    fun createOffer()
    fun acceptOffer(sdp: String)
    fun acceptAnswer(sdp: String)
    fun send(text: String): Boolean
    fun close()
}
