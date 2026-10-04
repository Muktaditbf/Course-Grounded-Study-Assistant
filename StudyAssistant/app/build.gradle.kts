import java.util.Properties

plugins {
    id("com.android.application")
}

// Firebase reads its project settings from google-services.json (download it from the Firebase
// console and place it in this folder). The plugin is only applied when the file exists, so the
// project still opens and compiles without it; the app then reports "Firebase not configured"
// at login instead of crashing.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// The OpenAI key lives in local.properties, which is gitignored. It is read at build time
// and surfaced as BuildConfig.OPENAI_API_KEY so no key ever appears in source or resources.
// An absent key is not a build failure: the app ships with an empty string and shows a
// "key not configured" state at runtime.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// AI and RAG configuration comes from local.properties (git-ignored) and is compiled into
// BuildConfig, so no key appears in source, resources or git. The older OPENAI_API_KEY /
// AI_BASE_URL / AI_MODEL names are still honoured so an existing local.properties keeps working.
fun prop(vararg names: String, default: String = ""): String {
    for (n in names) localProps.getProperty(n)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return default
}
fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val llmProvider = prop("LLM_PROVIDER", default = "openai_compatible")
val llmBaseUrl = prop("LLM_BASE_URL", "AI_BASE_URL", default = "https://api.openai.com/v1")
val llmModel = prop("LLM_MODEL", "AI_MODEL", default = "gpt-4o-mini")
val llmApiKey = prop("LLM_API_KEY", "OPENAI_API_KEY")
val llmTimeout = prop("LLM_TIMEOUT_SECONDS", default = "45").toIntOrNull() ?: 45
val llmMaxTokens = prop("LLM_MAX_TOKENS", default = "800").toIntOrNull() ?: 800
val ragProvider = prop("RAG_PROVIDER", default = "local")
val ragBaseUrl = prop("RAG_BASE_URL")
val ragApiKey = prop("RAG_API_KEY")
val ragAuthHeader = prop("RAG_AUTH_HEADER", default = "Authorization")
val ragTopK = prop("RAG_TOP_K", default = "5").toIntOrNull() ?: 5

android {
    namespace = "com.seu.studyassistant"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.seu.studyassistant"
        minSdk = 24
        targetSdk = 37
        versionCode = 5
        versionName = "1.4"

        buildConfigField("String", "LLM_PROVIDER", quoted(llmProvider))
        buildConfigField("String", "LLM_BASE_URL", quoted(llmBaseUrl))
        buildConfigField("String", "LLM_MODEL", quoted(llmModel))
        buildConfigField("String", "LLM_API_KEY", quoted(llmApiKey))
        buildConfigField("int", "LLM_TIMEOUT_SECONDS", llmTimeout.toString())
        buildConfigField("int", "LLM_MAX_TOKENS", llmMaxTokens.toString())
        buildConfigField("String", "RAG_PROVIDER", quoted(ragProvider))
        buildConfigField("String", "RAG_BASE_URL", quoted(ragBaseUrl))
        buildConfigField("String", "RAG_API_KEY", quoted(ragApiKey))
        buildConfigField("String", "RAG_AUTH_HEADER", quoted(ragAuthHeader))
        buildConfigField("int", "RAG_TOP_K", ragTopK.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    // A release keystore is used when local.properties names one (RELEASE_STORE_FILE,
    // RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD). Without it the release
    // build is signed with the debug key: fine for testing on your own phones, and Google
    // sign-in keeps working with the SHA-1 already registered. Use a real key for the Play Store.
    signingConfigs {
        create("release") {
            val store = localProps.getProperty("RELEASE_STORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Shrinking and optimising is the single biggest speed-up for the installed app:
            // a debug build runs with the optimiser off and debugging hooks on.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (localProps.getProperty("RELEASE_STORE_FILE") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // PDF text extraction for teacher uploads (offline, no network at runtime)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // OpenAI Chat Completions. OkHttp only - the one call this app makes is a single POST
    // with a small JSON body, so Retrofit plus a converter would be more moving parts than
    // the job needs, and org.json is already on the platform.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Firebase: Authentication (email and password) and Cloud Firestore (shared course data).
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // "Continue with Google" through Android's Credential Manager.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Frosted-glass blur behind the floating navigation bar (RenderEffect on Android 12+).
    implementation("com.github.Dimezis:BlurView:version-2.0.6")
}
