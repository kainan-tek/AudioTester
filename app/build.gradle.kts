plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.example.audiotester"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.example.audiotester"
        minSdk = 32
        versionCode = 10000
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        // Allow stubbed android.util.Log etc. in JVM unit tests (return default values)
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.viewpager2)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.mockito.core)
}

// Test JDK is newer than the Byte Buddy version pulled in by Mockito supports
tasks.withType<Test>().configureEach {
    jvmArgs("-Dnet.bytebuddy.experimental=true")
}
