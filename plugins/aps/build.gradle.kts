plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
    alias(libs.plugins.dokka)
}

android {
    namespace = "app.aaps.plugins.aps"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:objects"))
    implementation(project(":core:utils"))
    implementation(project(":core:graph"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(project(":core:validators"))
    // Align core + GPU on the same version so AndroidManifest namespace is not duplicated (2.3 GPU + 2.4 core).
    implementation("org.tensorflow:tensorflow-lite:2.4.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.4.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.1.0")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.1.0")
    implementation("androidx.core:core-i18n:1.0.0-alpha01")

    // 🏥 Health Connect - MTR Steps Integration (Android 14+)
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    testImplementation(project(":pump:virtual"))
    testImplementation(project(":shared:tests"))

    api(libs.androidx.appcompat)
    api(kotlin("reflect"))

    // APS (it should be androidTestImplementation but it doesn't work)
    api(libs.org.mozilla.rhino)

    //Logger
    api(libs.org.slf4j.api)

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.hilt.compiler)
    testImplementation("io.mockk:mockk:1.13.8")
    ksp(libs.com.google.dagger.android.processor)

    // Quality & Performance (Truth & JMH)
    testImplementation(libs.com.google.truth)

    // 📺 Jitsi Screen Share: no SDK needed — handled via Android Intent deep-link
    // The app opens meet.jit.si room via browser or the Jitsi Meet app if installed.
}