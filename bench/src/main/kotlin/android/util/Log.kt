package android.util

object Log {
    @JvmStatic
    fun isLoggable(
        tag: String,
        level: Int,
    ): Boolean = false

    @JvmStatic
    fun d(
        tag: String,
        msg: String,
    ): Int = 0

    @JvmStatic
    fun i(
        tag: String,
        msg: String,
    ): Int = 0

    @JvmStatic
    fun w(
        tag: String,
        msg: String,
    ): Int = 0

    @JvmStatic
    fun e(
        tag: String,
        msg: String,
    ): Int = 0
}
