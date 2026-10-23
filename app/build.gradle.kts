plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") version "2.0.21" }

android {
    namespace = "dev.bridge.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.bridge.assistant"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = false }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
