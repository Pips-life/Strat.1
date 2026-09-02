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
        var text = source.readText()
        val start = text.indexOf("@Composable private fun StrategiesScreen")
        val end = text.indexOf("@Composable private fun StrategyDetail", start)
        check(start >= 0 && end > start) { "Could not locate StrategiesScreen in MainActivity.kt" }
        text = text.substring(0, start) + template + "\n" + text.substring(end)

        // Home uses the same persisted strategy and the same backend control endpoint.
        text = text.replace(
            "Screen.HOME -> HomeScreen(Modifier.padding(pad), api, session) { screen = Screen.MT5 }",
            "Screen.HOME -> HomeScreen(Modifier.padding(pad), api, session, context) { screen = Screen.MT5 }"
        )
        text = text.replace(
            "private fun HomeScreen(modifier: Modifier, api: BackendApiClient, session: BackendSession?, openMt5: () -> Unit) {",
            "private fun HomeScreen(modifier: Modifier, api: BackendApiClient, session: BackendSession?, context: Context, openMt5: () -> Unit) {"
        )
        text = text.replace(
            "var bot by remember { mutableStateOf<BotState?>(null) }\n    var busy by remember { mutableStateOf(false) }",
            "var bot by remember { mutableStateOf<BotState?>(null) }\n    var selectedStrategy by remember { mutableStateOf(\"001\") }\n    var busy by remember { mutableStateOf(false) }"
        )
        text = text.replace(
            "LaunchedEffect(session) {\n        if (session == null) { state = null; bot = null }",
            "LaunchedEffect(session) {\n        selectedStrategy = context.pipsDataStore.data.first()[SELECTED_STRATEGY]?.takeIf { it == \"001\" || it == \"002\" } ?: \"001\"\n        if (session == null) { state = null; bot = null }"
        )
        text = text.replace(
            "api.botStatus(session).onSuccess { bot = it }",
            "api.botStatus(session).onSuccess { it -> bot = it; if (it.configured && it.strategy in setOf(\"001\", \"002\")) selectedStrategy = it.strategy }"
        )
        text = text.replace(
            "EngineActivityCard(bot, running, session != null, busy) { action -> busy = true; scope.launch { api.botCommand(session!!, action).onSuccess { bot = it }; busy = false } }",
            "EngineActivityCard(bot, selectedStrategy, running, session != null, busy) { action -> busy = true; scope.launch { api.botCommand(session!!, action, selectedStrategy).onSuccess { bot = it }; busy = false } }"
        )
        text = text.replace(
            "private fun EngineActivityCard(bot: BotState?, running: Boolean, enabled: Boolean, busy: Boolean, command: (String) -> Unit) {",
            "private fun EngineActivityCard(bot: BotState?, strategy: String, running: Boolean, enabled: Boolean, busy: Boolean, command: (String) -> Unit) {"
        )
        text = text.replace(
            "Text(\"Strategy 001 · QOF\", color = Primary, fontSize = 19.sp, fontWeight = FontWeight.Black)",
            "Text(if (strategy == \"002\") \"Strategy 002 · Velocity Expansion\" else \"Strategy 001 · QOF\", color = Primary, fontSize = 19.sp, fontWeight = FontWeight.Black)"
        )
        text = text.replace(
            "Text(if (enabled) \"Monitoring through the existing strategy engine.\" else \"Connect an MT5 account to activate the engine view.\", color = Muted, fontSize = 11.sp)",
            "Text(if (enabled) \"Monitoring ${'$'}{if (strategy == \"002\") \"Strategy 002 · Velocity Expansion\" else \"Strategy 001 · QOF\"} through the existing strategy engine.\" else \"Connect an MT5 account to activate the engine view.\", color = Muted, fontSize = 11.sp)"
        )
        source.writeText(text)
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
