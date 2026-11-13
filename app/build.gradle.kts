plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}
android {
    namespace = "jp.xiaqui.artifact"
    compileSdk = 35
    defaultConfig {
        applicationId = "jp.xiaqui.artifact.store.preview"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val keyFile = System.getenv("STORE_SIGNING_FILE")
    if (!keyFile.isNullOrBlank()) {
        signingConfigs.getByName("debug") {
            storeFile = file(keyFile)
            storePassword = System.getenv("STORE_SIGNING_PASSWORD")
            keyAlias = System.getenv("STORE_SIGNING_ALIAS")
            keyPassword = System.getenv("STORE_KEY_PASSWORD")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    implementation("androidx.core:core:1.13.1")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
