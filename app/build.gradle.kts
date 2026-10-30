import java.io.*
import java.util.Locale

plugins {
    id("com.android.application")
}

android {
    namespace = "com.m57.mboard"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.m57.mboard"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    jvmToolchain(21)
}

// TSVs remain the editable source. Package indexed tables to avoid startup text parsing.
val compiledTables = layout.buildDirectory.dir("generated/tableAssets")
val compileTables = tasks.register("compileTables") {
    val sources = fileTree("src/main/assets") { include("next_*.tsv", "context_*.tsv", "jordanian_next.tsv") }
    inputs.files(sources)
    outputs.dir(compiledTables)
    val outputDirectory = compiledTables.get().asFile
    doLast {
        val output = outputDirectory.apply { mkdirs() }
        // Must equal WordSuggestions.matchKey (candidate=false) and candidateKey (candidate=true).
        fun key(value: String, candidate: Boolean): String =
            value.lowercase(Locale.ROOT).let { if (candidate) it.replace('\u2019', '\'') else it }
                .filterNot { it in '\u064B'..'\u065F' || it == '\u0670' || it == '\u0640' || (!candidate && (it == '\'' || it == '\u2019')) }
        sources.forEach { source ->
            val strings = LinkedHashMap<String, Int>()
            fun id(value: String) = strings.getOrPut(value) { strings.size }
            data class TableRow(val key: Int, val total: Long, val words: IntArray, val keys: IntArray, val counts: LongArray)
            val rows = source.useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 2) return@mapNotNull null
                    val context = key(parts[0].trim(), false)
                    val total = parts[1].trim().toLongOrNull()
                    val items = parts.drop(if (total == null) 1 else 2).map { it.trim() }.filter { it.isNotEmpty() }
                    if (context.isEmpty() || items.isEmpty()) return@mapNotNull null
                    val words = ArrayList<String>()
                    val counts = LongArray(items.size)
                    items.forEachIndexed { index, item ->
                        val colon = item.lastIndexOf(':')
                        val count = if (colon > 0) item.substring(colon + 1).toLongOrNull() else null
                        words.add(if (count != null) item.substring(0, colon) else item)
                        counts[index] = count ?: (items.size - index).toLong()
                    }
                    TableRow(id(context), total ?: counts.sum(), words.map { id(it) }.toIntArray(), words.map { id(key(it, true)) }.toIntArray(), counts)
                }.toList()
            }
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(output, source.name + ".bin")))).use { stream ->
                stream.writeInt(0x4d425431)
                stream.writeInt(strings.size)
                strings.keys.forEach { value ->
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    stream.writeInt(bytes.size)
                    stream.write(bytes)
                }
                stream.writeInt(rows.size)
                rows.forEach { row ->
                    stream.writeInt(row.key)
                    stream.writeLong(row.total)
                    stream.writeInt(row.words.size)
                    row.words.indices.forEach { index ->
                        stream.writeInt(row.words[index])
                        stream.writeInt(row.keys[index])
                        stream.writeLong(row.counts[index])
                    }
                }
            }
        }
    }
}
android.sourceSets.getByName("main").assets.directories.add(compiledTables.get().asFile.path)
tasks.named("preBuild").configure { dependsOn(compileTables) }
// Do not ship both representations of these tables.
android.androidResources.ignoreAssetsPatterns.addAll(listOf("next_en.tsv", "next_ar.tsv", "context_en.tsv", "context_ar.tsv", "jordanian_next.tsv"))

val compileWords = tasks.register("compileWords") {
    val sources = fileTree("src/main/assets") { include("words_en.tsv", "words_ar.tsv") }
    val destination = layout.buildDirectory.dir("generated/wordAssets").get().asFile
    inputs.files(sources)
    outputs.dir(destination)
    doLast {
        destination.mkdirs()
        // Change together with WordDictionary.normalize. English alphabet: Languages.ENGLISH.alphabet.
        fun normalizeWord(value: String, latin: Boolean): String? {
            if (latin) {
                val result = value.lowercase(Locale.ROOT)
                val letters = "abcdefghijklmnopqrstuvwxyz"
                return if (result.isNotEmpty() && result.first() in letters && result.last() in letters &&
                    result.all { it in letters || it == '\'' }) result else null
            }
            val result = buildString(value.length) {
                value.forEach { char ->
                    when (char) {
                        'أ', 'إ', 'آ', 'ٱ' -> append('ا')
                        'ى' -> append('ي')
                        'ـ', in '\u064B'..'\u065F', '\u0670', in '\u06D6'..'\u06ED' -> Unit
                        else -> {
                            if (!char.isLetter() || Character.UnicodeScript.of(char.code) != Character.UnicodeScript.ARABIC) return null
                            append(char)
                        }
                    }
                }
            }
            return if (result.isNotEmpty() && result.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC }) result else null
        }
        sources.forEach { source ->
            data class Word(val original: String, val normalized: String, val frequency: Long)
            val words = source.useLines { lines -> lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val original = line.substring(0, tab)
                val frequency = line.substring(tab + 1).trim().toLongOrNull() ?: return@mapNotNull null
                val normalized = normalizeWord(original, source.name == "words_en.tsv") ?: return@mapNotNull null
                Word(original, normalized, frequency)
            }.toList() }
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(destination, source.name + ".bin")))).use { stream ->
                fun text(value: String) {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    stream.writeInt(bytes.size)
                    stream.write(bytes)
                }
                stream.writeInt(0x4d425732)
                stream.writeInt(words.size)
                val ordered = if (source.name == "words_en.tsv") words.sortedBy { it.normalized } else words
                ordered.forEach { word ->
                    text(word.original)
                    text(if (word.normalized == word.original) "" else word.normalized)
                    stream.writeLong(word.frequency)
                }
                // Prefix ties follow the same normalized-word order as WordDictionary.assemble.
                val canonical = LinkedHashMap<String, Int>()
                ordered.forEachIndexed { index, word ->
                    val previous = canonical[word.normalized]
                    if (previous == null || word.frequency > ordered[previous].frequency) canonical[word.normalized] = index
                }
                val prefixes = LinkedHashMap<String, MutableList<Int>>()
                canonical.values.sortedBy { ordered[it].normalized }.forEach { index ->
                    val word = ordered[index]
                    for (length in 1..minOf(3, word.normalized.length)) {
                        val best = prefixes.getOrPut(word.normalized.substring(0, length)) { ArrayList() }
                        val position = best.indexOfFirst { ordered[it].frequency < word.frequency }
                        if (position < 0) best.add(index) else best.add(position, index)
                        if (best.size > 8) best.removeAt(8)
                    }
                }
                stream.writeInt(prefixes.size)
                prefixes.forEach { (prefix, indices) ->
                    text(prefix)
                    stream.writeInt(indices.size)
                    indices.forEach { stream.writeInt(it) }
                }
            }
        }
    }
}
android.sourceSets.getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/wordAssets").get().asFile.path)
tasks.named("preBuild").configure { dependsOn(compileWords) }
android.androidResources.ignoreAssetsPatterns.addAll(listOf("words_en.tsv", "words_ar.tsv"))
