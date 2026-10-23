plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") version "2.0.21" }

android {
    namespace = "dev.bridge.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.bridge.assistant"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    testImplementation("junit:junit:4.13.2")
}
