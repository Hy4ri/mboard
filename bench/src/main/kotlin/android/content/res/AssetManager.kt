package android.content.res

import java.io.File
import java.io.InputStream

class AssetManager(
    private val dir: File,
) {
    fun open(name: String): InputStream = File(dir, name).inputStream()
}
