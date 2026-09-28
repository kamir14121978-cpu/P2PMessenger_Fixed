package kz.p2pmessenger.internet

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

/** Native WebRTC adapter. No media tracks, signaling service, or message relay. */
class WebRtcEngine(
    context: Context,
    private val listener: RtcEngine.Listener,
    servers: List<IceServerConfig> = IceServerConfig.TEST_STUN
) : RtcEngine {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var published = false
    private var localSet = false
    private var transportConnected = false
    private var kind = SignalKind.OFFER
    private val gatherTimeout = Runnable {
        if (!closed && !published) listener.onError("Сбор ICE не завершился за 45 секунд. Проверьте сеть и создайте новое подключение.")
    }
    private val connectTimeout = Runnable {
        if (!closed) listener.onError("Время подключения истекло. Создайте новый offer; при ограничениях NAT может понадобиться TURN.")
    }

    private fun post(block: () -> Unit) { main.post { if (!closed) block() } }

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        try {
            initialize(context.applicationContext)
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            val config = PeerConnection.RTCConfiguration(servers.map { server ->
                PeerConnection.IceServer.builder(server.urls)
                    .setUsername(server.username).setPassword(server.credential).createIceServer()
            }).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                // ALL allows direct candidates today and TURN candidates when injected later.
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }
            peer = requireNotNull(factory?.createPeerConnection(config, observer()))
        } catch (e: Throwable) {
            peer?.dispose()
            factory?.dispose()
            throw e
        }
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = post {
            if (state == PeerConnection.IceGatheringState.COMPLETE) publishLocal()
        }
        override fun onIceCandidate(candidate: IceCandidate) = Unit // Included in final local SDP.
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit // A new manual exchange starts a fresh session.
        override fun onDataChannel(dataChannel: DataChannel) {
            main.post {
                if (closed) dataChannel.dispose()
                else if (channel != null || dataChannel.label() != CHANNEL_LABEL) {
                    dataChannel.close()
                    dataChannel.dispose()
                    listener.onError("Получен неожиданный канал WebRTC.")
                } else attach(dataChannel)
            }
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = post {
            transportConnected = state == PeerConnection.PeerConnectionState.CONNECTED
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> notifyChannelState()
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    listener.onLinkState(LinkState.DISCONNECTED)
                    main.removeCallbacks(connectTimeout)
                    main.postDelayed(connectTimeout, 30_000)
                }
                PeerConnection.PeerConnectionState.FAILED -> listener.onLinkState(LinkState.FAILED)
                PeerConnection.PeerConnectionState.CLOSED -> listener.onLinkState(LinkState.CLOSED)
                else -> listener.onLinkState(LinkState.CONNECTING)
            }
        }
    }

    override fun createOffer() {
        kind = SignalKind.OFFER
        attach(requireNotNull(peer?.createDataChannel(CHANNEL_LABEL, DataChannel.Init().apply {
            ordered = true // Reliable ordered SCTP; no retransmission limit.
        })))
        startGatherTimer()
        peer?.createOffer(sdpObserver(created = { setLocal(it) }), MediaConstraints())
    }

    override fun acceptOffer(sdp: String) {
        kind = SignalKind.ANSWER
        startGatherTimer()
        peer?.setRemoteDescription(sdpObserver(set = {
            peer?.createAnswer(sdpObserver(created = { setLocal(it) }), MediaConstraints())
        }), SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    override fun acceptAnswer(sdp: String) {
        peer?.setRemoteDescription(sdpObserver(set = { startConnectTimer() }),
            SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun setLocal(sdp: SessionDescription) {
        peer?.setLocalDescription(sdpObserver(set = {
            localSet = true
            // COMPLETE may arrive before onSetSuccess; both paths converge here.
            if (peer?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) publishLocal()
        }), sdp)
    }

    private fun publishLocal() {
        if (published || !localSet) return
        val sdp = peer?.localDescription?.description ?: return
        published = true
        main.removeCallbacks(gatherTimeout)
        if (kind == SignalKind.ANSWER) startConnectTimer()
        listener.onLocalSignal(kind, sdp)
    }

    private fun sdpObserver(
        created: (SessionDescription) -> Unit = {},
        set: () -> Unit = {}
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = post { created(sdp) }
        override fun onSetSuccess() = post { set() }
        // Native error text can contain SDP; do not log it or expose it in the UI.
        override fun onCreateFailure(error: String) = post {
            listener.onError("Не удалось сформировать offer/answer. Создайте новое подключение.")
        }
        override fun onSetFailure(error: String) = post {
            listener.onError("WebRTC отклонил данные подключения. Проверьте offer/answer и повторите обмен.")
        }
    }

    private fun attach(dataChannel: DataChannel) {
        channel = dataChannel
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = post { notifyChannelState() }
            override fun onMessage(buffer: DataChannel.Buffer) {
                // Native buffer is only valid during this callback: copy BEFORE posting.
                if (buffer.binary || buffer.data.remaining() > InternetSession.MAX_MESSAGE_BYTES) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                post { listener.onMessage(bytes.toString(Charsets.UTF_8)) }
            }
        })
        notifyChannelState()
    }

    private fun notifyChannelState() {
        when (channel?.state()) {
            DataChannel.State.OPEN -> if (transportConnected) {
                main.removeCallbacks(connectTimeout)
                listener.onLinkState(LinkState.CONNECTED)
            }
            DataChannel.State.CLOSED, DataChannel.State.CLOSING -> listener.onLinkState(LinkState.CLOSED)
            else -> Unit
        }
    }

    private fun startGatherTimer() { main.postDelayed(gatherTimeout, 45_000) }
    private fun startConnectTimer() {
        main.removeCallbacks(connectTimeout)
        if (!(transportConnected && channel?.state() == DataChannel.State.OPEN)) {
            main.postDelayed(connectTimeout, 180_000)
        }
    }

    override fun send(text: String): Boolean {
        val dc = channel ?: return false
        if (closed || !transportConnected || dc.state() != DataChannel.State.OPEN ||
            dc.bufferedAmount() > 256 * 1024) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > InternetSession.MAX_MESSAGE_BYTES) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(gatherTimeout)
        main.removeCallbacks(connectTimeout)
        channel?.unregisterObserver()
        channel?.close()
        channel?.dispose()
        channel = null
        peer?.close()
        peer?.dispose()
        peer = null
        factory?.dispose()
        factory = null
    }

    companion object {
        private const val CHANNEL_LABEL = "p2pmessenger-text"
        private var initialized = false
        @Synchronized private fun initialize(context: Context) {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                initialized = true
            }
        }
    }
}
