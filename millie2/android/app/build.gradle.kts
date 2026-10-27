import java.util.Properties

plugins { id("com.android.application") }

// Release signing is read from millie2/android/keystore.properties (git-ignored, never committed).
// If that file is absent (fresh clone, CI), release builds are simply unsigned — nothing breaks.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.millie.two"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.millie.civops2"
        testInstrumentationRunner = "com.millie.two.LinkAuditInstrumentation"
        minSdk = 26
        targetSdk = 35
        versionCode = 20014
        versionName = "2.0.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
}
dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
