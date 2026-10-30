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
