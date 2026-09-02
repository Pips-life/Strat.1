import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseProps = Properties().apply {
    file("../release.properties").inputStream().use(::load)
}

android {
    namespace = "life.pips.strat1"
    compileSdk = 35
    defaultConfig {
        applicationId = "life.pipslife.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseProps.getProperty("versionCode").toInt()
        versionName = releaseProps.getProperty("versionName")
        buildConfigField("String", "BACKEND_BASE_URL", "\"https://strat-1.vercel.app\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }

    val storeFile = System.getenv("PIPS_LIFE_KEYSTORE_PATH") ?: System.getenv("PIPSLIFE_KEYSTORE_FILE")
    val storePassword = System.getenv("PIPS_LIFE_KEYSTORE_PASSWORD") ?: System.getenv("PIPSLIFE_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("PIPS_LIFE_KEY_ALIAS") ?: System.getenv("PIPSLIFE_KEY_ALIAS")
    val keyPassword = System.getenv("PIPS_LIFE_KEY_PASSWORD") ?: System.getenv("PIPSLIFE_KEY_PASSWORD")
    if (!storeFile.isNullOrBlank() && !storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                this.storeFile = file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
}

val patchStrategyUi by tasks.registering {
    doLast {
        val source = file("src/main/java/life/pips/strat1/MainActivity.kt")
        val template = file("../strategy_ui/StrategiesScreen.ktfrag").readText()
        val text = source.readText()
        val start = text.indexOf("@Composable private fun StrategiesScreen")
        val end = text.indexOf("@Composable private fun StrategyDetail", start)
        check(start >= 0 && end > start) { "Could not locate StrategiesScreen in MainActivity.kt" }
        var patched = text.substring(0, start) + template + "\n" + text.substring(end)
        patched = patched.replace(
            "Text(\"Strategy 001 · QOF\", color = Primary, fontSize = 19.sp, fontWeight = FontWeight.Black)",
            "Text(if (bot?.strategy == \"002\") \"Strategy 002 · Velocity Expansion\" else \"Strategy 001 · QOF\", color = Primary, fontSize = 19.sp, fontWeight = FontWeight.Black)"
        )
        source.writeText(patched)
    }
}

tasks.named("preBuild").configure { dependsOn(patchStrategyUi) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
