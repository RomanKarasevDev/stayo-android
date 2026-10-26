plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "live.stayo.sample"
    compileSdk = 35
    defaultConfig { applicationId = "live.stayo.sample"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    if (providers.gradleProperty("stayoPackaged").isPresent) implementation("live.stayo:stayo-android:0.1.0")
    else implementation(project(":library"))
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
