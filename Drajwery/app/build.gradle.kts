plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // Kotlin 2.x
}

// Klucze poza kodem: zmienne środowiskowe (np. sekrety CI) albo plik local.properties (nie wrzucaj go do repozytorium).
val props = java.util.Properties().apply {
    val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = System.getenv(name) ?: props.getProperty(name) ?: ""

android {
    namespace = "pl.example.trasa"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.example.trasa"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TOMTOM_KEY", "\"${secret("TOMTOM_KEY")}\"")
        buildConfigField("String", "CONTACT_EMAIL", "\"${secret("CONTACT_EMAIL").ifEmpty { "zmien-na-swoj-email@example.com" }}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("release") {   // podpis wydaniowy tylko gdy podano keystore (zmienne środowiskowe)
            val p = System.getenv("KEYSTORE_PATH")
            if (p != null) {
                storeFile = file(p); storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS"); keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.google.android.material:material:1.12.0") // motyw Theme.Material3 w manifeście
    implementation("org.osmdroid:osmdroid-android:6.1.20")         // mapa OpenStreetMap

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
