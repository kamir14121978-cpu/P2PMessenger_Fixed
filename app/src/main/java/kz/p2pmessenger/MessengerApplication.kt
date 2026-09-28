package kz.p2pmessenger

import android.app.Application

// One TCP listener per process, including after Activity recreation.
// The v0.1 wire protocol and LanMessenger implementation stay unchanged.
class MessengerApplication : Application() {
    val messenger = LanMessenger()

    override fun onCreate() {
        super.onCreate()
        messenger.start()
    }
}
