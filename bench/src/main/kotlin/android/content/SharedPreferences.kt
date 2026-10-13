package android.content

interface SharedPreferences {
    fun getString(
        key: String,
        defValue: String?,
    ): String?

    fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean

    fun edit(): Editor

    interface Editor {
        fun putString(
            key: String,
            value: String?,
        ): Editor

        fun putBoolean(
            key: String,
            value: Boolean,
        ): Editor

        fun clear(): Editor

        fun apply()
    }
}

class MemoryPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getString(
        key: String,
        defValue: String?,
    ) = values[key] as String? ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ) = values[key] as Boolean? ?: defValue

    override fun edit(): SharedPreferences.Editor =
        object : SharedPreferences.Editor {
            override fun putString(
                key: String,
                value: String?,
            ) = apply { values[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { values[key] = value }

            override fun clear() = apply { values.clear() }

            override fun apply() {}
        }
}
