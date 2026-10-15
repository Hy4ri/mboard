// Desktop replay bench: compiles the keyboard's real suggestion code for the JVM and scores it on text.
// Not part of the app build. Run: ../gradlew -p bench installDist && bench/run.sh
plugins {
    kotlin("jvm") version "2.4.21"
    application
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin {
            // The keyboard sources under test, unchanged, plus small Android stand-ins.
            srcDir("../app/src/main/java")
            include(
                "com/m57/mboard/Languages.kt",
                "com/m57/mboard/Layouts.kt",
                "com/m57/mboard/EnglishWords.kt",
                "com/m57/mboard/ArabicWords.kt",
                "com/m57/mboard/WordDictionary.kt",
                "com/m57/mboard/KnownWordForms.kt",
                "com/m57/mboard/WordSuggestions.kt",
                "com/m57/mboard/PredictionTrail.kt",
                "com/m57/mboard/UserWords.kt",
                "android/**",
                "com/m57/bench/**",
            )
        }
    }
}

dependencies {
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("com.m57.bench.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx3g")
}
