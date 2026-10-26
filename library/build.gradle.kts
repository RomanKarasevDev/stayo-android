plugins { id("com.android.library"); kotlin("android"); `maven-publish`; signing }
group = "live.stayo"
version = "0.1.0"
android {
    namespace = "live.stayo.sdk"
    compileSdk = 35
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro"); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "stayo-android"
                pom {
                    name.set("Stayo Android")
                    description.set("Independent Stayo Android SDK: Auth, database, storage, functions and realtime.")
                    url.set("https://github.com/RomanKarasevDev/stayo-android")
                    licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
                    developers { developer { id.set("stayo"); name.set("Stayo maintainers"); url.set("https://github.com/RomanKarasevDev") } }
                    scm {
                        url.set("https://github.com/RomanKarasevDev/stayo-android")
                        connection.set("scm:git:https://github.com/RomanKarasevDev/stayo-android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/RomanKarasevDev/stayo-android.git")
                        tag.set("0.1.0")
                    }
                }
            }
        }
        repositories { maven { name = "LocalBuild"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
    }
    // No remote repository or credentials: this project never publishes externally by default.
    signing { isRequired = false }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(rootProject.file("LICENSE")) { into("META-INF") }
    if (name == "javaDocReleaseJar") {
        // Supplement the generated API documentation with the portable handbook.
        from(rootProject.file("README.md"))
        from(rootProject.file("docs")) { into("docs") }
    }
}
