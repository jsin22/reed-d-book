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
}
