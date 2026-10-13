package android.content

import android.content.res.AssetManager
import java.io.File

/** Desktop stand-in: assets come from a directory, preferences live in memory. */
open class Context(
    assetsDir: File,
) {
    open val applicationContext: Context get() = this
    val assets = AssetManager(assetsDir)
    private val prefs = HashMap<String, SharedPreferences>()

    fun getSharedPreferences(
        name: String,
        mode: Int,
    ): SharedPreferences = prefs.getOrPut(name) { MemoryPreferences() }

    companion object {
        const val MODE_PRIVATE = 0
    }
}
