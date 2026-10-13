package android.os

/** Runs posted work immediately on the calling thread; the bench waits for READY itself. */
class Handler(
    @Suppress("UNUSED_PARAMETER") looper: Looper,
) {
    fun post(r: Runnable): Boolean {
        r.run()
        return true
    }

    fun removeCallbacksAndMessages(token: Any?) {}
}
