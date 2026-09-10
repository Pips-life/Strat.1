import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseProps = Properties().apply { file("../release.properties").inputStream().use(::load) }
val flashAlphaKey = System.getenv("FLASHALPHA_API_KEY").orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "life.pips.strat1"
    compileSdk = 35
    defaultConfig {
        applicationId = "life.pipslife.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseProps.getProperty("versionCode").toInt()
        versionName = releaseProps.getProperty("versionName")
        buildConfigField("String", "FLASHALPHA_API_KEY", "\"$flashAlphaKey\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/ASL2.0",
                "log4j2.xml",
                "log4j.properties"
            )
        }
    }

    val storeFile = System.getenv("PIPS_LIFE_KEYSTORE_PATH") ?: System.getenv("PIPSLIFE_KEYSTORE_FILE")
    val storePassword = System.getenv("PIPS_LIFE_KEYSTORE_PASSWORD") ?: System.getenv("PIPSLIFE_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("PIPS_LIFE_KEY_ALIAS") ?: System.getenv("PIPSLIFE_KEY_ALIAS")
    val keyPassword = System.getenv("PIPS_LIFE_KEY_PASSWORD") ?: System.getenv("PIPSLIFE_KEY_PASSWORD")
    if (!storeFile.isNullOrBlank() && !storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                this.storeFile = file(storeFile); this.storePassword = storePassword; this.keyAlias = keyAlias; this.keyPassword = keyPassword
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("cloud.metaapi.sdk:metaapi-java-sdk:14.0.9")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
