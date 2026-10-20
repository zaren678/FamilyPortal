import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val googleDeviceClientId = localProperties.getProperty(
    "GOOGLE_DEVICE_OAUTH_CLIENT_ID",
    "000000000000-placeholder.apps.googleusercontent.com",
)
val googleDeviceClientSecret = localProperties.getProperty(
    "GOOGLE_DEVICE_OAUTH_CLIENT_SECRET",
    "placeholder",
)

android {
    namespace = "com.johnanderson.familyportal.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        resValue("string", "google_device_oauth_client_id", googleDeviceClientId)
        resValue("string", "google_device_oauth_client_secret", googleDeviceClientSecret)
    }

    buildFeatures.buildConfig = false

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    api(libs.androidx.lifecycle.viewmodel.ktx)

    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    api(libs.okhttp)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.appauth)
    implementation(libs.jmdns)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
}
