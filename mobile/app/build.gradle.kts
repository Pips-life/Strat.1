plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseProps = java.util.Properties().apply {
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
        buildConfigField("String", "BACKEND_BASE_URL", "\"https://strat-1-pips-life.vercel.app\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
