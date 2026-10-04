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
val openAiKey: String = (localProps.getProperty("OPENAI_API_KEY") ?: "").trim()

// Provider is swappable because OpenAI, Google Gemini, Groq, OpenRouter and Cerebras all
// expose the SAME Chat Completions request/response shape - only the host and model name
// differ. Override AI_BASE_URL and AI_MODEL in local.properties to move to a free provider
// without touching a line of Java.
val aiBaseUrl: String = (localProps.getProperty("AI_BASE_URL")
    ?: "https://api.openai.com/v1/chat/completions").trim()
val aiModel: String = (localProps.getProperty("AI_MODEL") ?: "gpt-4o-mini").trim()

android {
    namespace = "com.seu.studyassistant"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.seu.studyassistant"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiKey\"")
        buildConfigField("String", "AI_BASE_URL", "\"$aiBaseUrl\"")
        buildConfigField("String", "OPENAI_MODEL", "\"$aiModel\"")
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
}
