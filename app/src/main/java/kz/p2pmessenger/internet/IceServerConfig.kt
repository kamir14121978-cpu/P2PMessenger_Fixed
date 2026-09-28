package kz.p2pmessenger.internet

/** Inject runtime TURN credentials here later; never store them in source or signaling data. */
data class IceServerConfig(
    val urls: List<String>,
    val username: String = "",
    val credential: String = ""
) {
    init {
        require(urls.isNotEmpty() && urls.all {
            it.startsWith("stun:") || it.startsWith("stuns:") ||
                it.startsWith("turn:") || it.startsWith("turns:")
        })
    }

    companion object {
        val TEST_STUN = listOf(IceServerConfig(listOf("stun:stun.l.google.com:19302")))
    }
}
