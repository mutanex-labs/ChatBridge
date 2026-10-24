plugins { id("com.android.application") }

android {
    namespace = "dev.bridge.assistant"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.bridge.assistant"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = false }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.core:core:1.15.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
