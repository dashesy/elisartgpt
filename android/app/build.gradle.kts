import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing comes from android/keystore.properties (gitignored) so the
// same key signs every build you hand out; without it Gradle falls back to the
// debug key, which still installs fine for personal testing.
fun git(vararg args: String): String = ProcessBuilder("git", *args)
    .directory(rootProject.projectDir).redirectErrorStream(true).start()
    .inputStream.bufferedReader().readText().trim()
fun gitCommitCount() = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
fun gitShortSha() = git("rev-parse", "--short", "HEAD").ifBlank { "dev" }

val dotenv = Properties().apply {
    val f = rootProject.file("../.env")
    if (f.exists()) f.inputStream().use { load(it) }
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "art.elisa"
    compileSdk = 35

    defaultConfig {
        applicationId = "art.elisa"
        // ImageDecoder (photo downscaling with EXIF rotation) arrived in 28 / Android 9.
        minSdk = 26
        targetSdk = 35
        // Monotonic from git so `make publish` never needs a manual bump; the
        // update check in the app compares this number against the server's.
        versionCode = gitCommitCount()
        versionName = "0.${gitCommitCount()}-${gitShortSha()}"
        // The server people install against: ELISART_PUBLIC_URL from the repo's .env,
        // overridable with -PserverUrl=...; without either, the emulator-to-host address.
        val serverUrl = (project.findProperty("serverUrl") as String?)
            ?: dotenv.getProperty("ELISART_PUBLIC_URL")
            ?: "http://10.0.2.2:8787"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Keep the dex deflated inside the APK. AGP stores it uncompressed once
    // minSdk reaches 28, which turns the 12 MB download into 45 MB; pin the
    // behavior so a future minSdk bump cannot do that silently.
    packaging { dex { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "elisart.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Camera JPEGs carry their rotation in EXIF; without this a sideways hand gets uploaded.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
