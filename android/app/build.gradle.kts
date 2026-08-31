import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

plugins {
    // No kotlin-android plugin: AGP 9 compiles Kotlin itself and fails the build
    // if the standalone plugin is also applied. See kotl.in/gradle/agp-built-in-kotlin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

/**
 * Writes `dev.reedd.BuildInfo` with the git commit and time this specific APK was
 * built, so a build installed minutes apart from the last one is distinguishable on
 * the device itself -- see BUGS.md, "Getting the build number".
 *
 * Runs via [ExecOperations] rather than `project.exec`, which is the
 * configuration-cache-compatible way to shell out from a task action. Forced to run
 * every build (`upToDateWhen { false }`): `git describe` is invisible to Gradle's own
 * up-to-date and configuration-cache input tracking, so without this the stamp would
 * silently freeze at whatever it was on the last build the *script* re-evaluated,
 * which defeats the entire point during rapid device-testing iteration.
 */
abstract class GenerateBuildInfoTask : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = ByteArrayOutputStream()
        val sha = runCatching {
            execOps.exec {
                commandLine("git", "describe", "--always", "--dirty", "--abbrev=8")
                standardOutput = out
                isIgnoreExitValue = true
            }
            out.toString().trim()
        }.getOrNull()?.ifBlank { null } ?: "unknown"

        val builtAt = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        val file = outputDir.get().asFile.resolve("dev/reedd/BuildInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package dev.reedd

            /** Which commit and when this specific APK was built. Generated at build time -- see app/build.gradle.kts. */
            object BuildInfo {
                const val GIT_SHA = "$sha"
                const val BUILT_AT = "$builtAt"
            }

            """.trimIndent(),
        )
    }
}

val buildInfoDir = layout.buildDirectory.dir("generated/buildInfo/main")
val generateBuildInfo = tasks.register<GenerateBuildInfoTask>("generateBuildInfo") {
    outputDir.set(buildInfoDir)
    outputs.upToDateWhen { false }
}

/**
 * Decompresses the bundled zstd dictionary asset once, at build time, using
 * the system `zstd` binary -- entirely outside any JVM, and specifically
 * outside Robolectric's, which is the whole point: see [Dictionary]'s own
 * `preDecompressed` docstring for why zstd-jni itself can't run inside a
 * Robolectric-hosted unit test. Its output path is handed to the test JVM as
 * a system property (wired below, on the unit test tasks themselves) rather
 * than added as a source/resources directory, since `Dictionary` reads it as
 * a plain [java.io.File] path, not a classpath resource.
 */
val testDictionaryFile = layout.buildDirectory.file("generated/testDictionary/dictionary.db")
// Resolved and created eagerly, at configuration time -- a `doFirst {}`
// closure here would capture this script's own `testDictionaryFile`/`layout`
// references, which the configuration cache's serializer rejects outright
// ("cannot serialize Gradle script object references"). A plain, idempotent
// mkdir at configuration time sidesteps that; it's cheap enough not to matter.
val testDictionaryOutputFile = testDictionaryFile.get().asFile.also { it.parentFile.mkdirs() }
val extractTestDictionary = tasks.register<Exec>("extractTestDictionary") {
    val zstAsset = layout.projectDirectory.file("src/main/assets/dictionary.db.zst")
    inputs.file(zstAsset)
    outputs.file(testDictionaryOutputFile)
    commandLine("zstd", "-d", "-f", "-q", zstAsset.asFile.path, "-o", testDictionaryOutputFile.path)
}

android {
    namespace = "dev.reedd"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.reedd"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // zstd-jni ships native libraries for arm64-v8a, armeabi-v7a, x86 and
        // x86_64; every real phone this app is sideloaded onto is arm64, and
        // bundling all four in one APK (there is no Play-style per-ABI split
        // set up here) would quadruple that dependency's actual on-device
        // cost for nothing. Revisit if this is ever installed on something
        // other than a real arm64 phone (an x86_64 emulator, notably).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // Left unminified for now: R8 rules for Readium's reflection-heavy
            // resource loading are their own piece of work, and nothing here
            // ships through Play.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The bundled dictionary ships pre-compressed with zstd (see Dictionary.kt);
    // AAPT's own deflate pass over already-high-entropy zstd output does nothing
    // but spend build time re-trying compression that can't shrink it further --
    // this tells the packager to store that one asset as-is instead.
    androidResources {
        noCompress += "zst"
    }

    // Readium targets Java 17, and both readium-shared and readium-streamer
    // declare that they need core library desugaring -- without it the build
    // fails outright, minSdk 26 notwithstanding.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        // Puts the exported Room schemas on the unit-test classpath, so the
        // migration test builds its "old" database from the real version 1 schema
        // rather than a hand-copied one that could drift from it.
        getByName("test") {
            resources.srcDir("schemas")
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Most of Readium's navigator and preferences API is marked experimental,
        // including the parts any reader app has to use. Opting in once here beats
        // sprinkling @OptIn over every call site; their own test app does the same.
        freeCompilerArgs.add("-opt-in=org.readium.r2.shared.ExperimentalReadiumApi")
    }
}

// Every unit test task gets the pre-decompressed dictionary's path (see
// extractTestDictionary above) as a system property DictionaryTest reads
// directly -- applies to all of them (`testDebugUnitTest`, etc.) rather than
// naming one by hand, and via `tasks.withType` so it also covers whichever
// variant's unit test task actually runs.
tasks.withType<Test>().configureEach {
    dependsOn(extractTestDictionary)
    systemProperty("reedd.testDictionaryPath", testDictionaryFile.get().asFile.path)
}

// Room's generated schema, checked in so migrations are reviewable in diffs.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The Variant API's `addGeneratedSourceDirectory` (rather than the classic
// `AndroidSourceDirectorySet.srcDir`) is what both marks the directory as
// generated/read-only for Android Studio and wires compileKotlin to depend on the
// task that fills it, for every variant, without naming their task names by hand.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateBuildInfo, GenerateBuildInfoTask::outputDir)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.fragment.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    // Reads epub resource text for the read-along aligner.
    implementation(libs.jsoup)
    // Decompresses the bundled dictionary asset -- see Dictionary.kt.
    // `@aar` is required, not cosmetic: zstd-jni's POM declares plain
    // `<packaging>jar</packaging>` with no Gradle Module Metadata, so nothing
    // tells Gradle/AGP to prefer its separately-published .aar over the
    // default .jar -- confirmed live: without this, the app shipped the
    // plain multi-platform jar (bundling desktop darwin/win natives as inert
    // loose files, since AGP doesn't recognize their paths as installable
    // native libraries) with *no* native library packaged for Android at
    // all, and every real-device decompression failed with
    // `UnsatisfiedLinkError`. The .aar's `jni/arm64-v8a/*.so` is what AGP
    // actually knows how to install into the APK's own native library
    // directory, matching this module's own `ndk.abiFilters`.
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)

    // DictionaryTest runs zstd-jni for real against the actual bundled asset
    // (see Dictionary.kt), on the host JVM under Robolectric -- not an
    // Android device. The `implementation(...@aar)` above resolves the AAR,
    // whose native library is arm64-v8a Android-only (matches this module's
    // own `ndk.abiFilters`) and can't be loaded by a plain desktop JVM at all;
    // without this, every DictionaryTest failed with
    // `UnsupportedOperationException` at `ZstdInputStream`'s own constructor,
    // not even getting as far as a native-linking error. `@jar` pins
    // resolution to zstd-jni's plain (non-AAR) published artifact, which
    // bundles desktop natives (linux-x86_64 among them) alongside the same
    // Android ones -- present here only for the test classpath, not the app.
    testImplementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@jar")
}
