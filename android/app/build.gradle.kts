plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.ragtime.radio"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "org.ragtime.radio"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { targets += "ragtime_jni" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    // Shared builds say what they are: ragtime-android-0.1.0-b2-debug.apk.
    // The archive name must come after defaultConfig's version.
    base.archivesName.set("ragtime-android-${defaultConfig.versionName}-b${defaultConfig.versionCode}")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// The notices the app shows (About > Open source licences, Privacy) come
// from the repository's own files, so the app cannot drift from them:
// Ragtime's LICENSE, docs/ANDROID_PRIVACY.md and the Cool-chic port's
// notices. The Apache text and OpenJPEG's are checked-in assets.
val noticeAssets = layout.buildDirectory.dir("generated/notices")
val copyNotices by tasks.registering(Copy::class) {
    from(rootProject.file("../LICENSE")) { into("licenses"); rename { "ragtime-mit.txt" } }
    // The Cool-chic port's notices (Orange, BSD-3; the SOAP optimiser, MIT).
    from(rootProject.file("../image/src/coolchic/LICENSE.txt")) { into("licenses"); rename { "coolchic-bsd-3.txt" } }
    from(rootProject.file("../image/src/coolchic/LICENSE-SOAP.txt")) { into("licenses"); rename { "soap-mit.txt" } }
    from(rootProject.file("../docs/ANDROID_PRIVACY.md")) { rename { "privacy.md" } }
    into(noticeAssets)
}
android.sourceSets["main"].assets.srcDir(noticeAssets.get().asFile)
tasks.named("preBuild") { dependsOn(copyNotices) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose's older transitive Espresso uses InputManager.getInstance,
    // removed on API 37. Keep the fixed input injector explicit.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
