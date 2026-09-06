import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * The Google OAuth client id lives outside version control. Put
 * `google.oauth.client.id=...` in local.properties, or set it at runtime in
 * uwuMail's settings screen.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * Release signing credentials, kept out of version control. `build.sh` creates
 * both the keystore and this file on the first release build; when it is absent
 * the release variant is simply left unsigned rather than failing the build.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

android {
    namespace = "de.uwumail"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.uwumail"
        minSdk = 31
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField(
            "String",
            "GOOGLE_OAUTH_CLIENT_ID",
            "\"${localProperties.getProperty("google.oauth.client.id", "")}\""
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v1 (JAR) signing is only consulted below API 24; minSdk is 31.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Builds locales_config.xml from the values-* folders, which is what
        // puts uwuMail in Android's own per-app language list as well.
        generateLocaleConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/*.kotlin_module"
            )
        }
    }
    lint {
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.javamail.android.mail)
    implementation(libs.javamail.android.activation)
    implementation(libs.jsoup)
    implementation(libs.androidx.browser)
    // Per-app language, which the framework only offers from API 33 and this app runs from 31.
    implementation(libs.androidx.appcompat)
    // Algorithmic darkening, so a white mail is not a flashbang in a dark theme.
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    // Virtual time, so a test for "wait until the typing stops" takes no time.
    testImplementation(libs.kotlinx.coroutines.test)
    // Runs the Compose and navigation tests on the JVM. A navigation bug that
    // empties the back stack cannot be caught by testing pure functions.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Android ships org.json as stubs; the real implementation lets the rule
    // engine's header parsing run under plain JVM tests.
    testImplementation(libs.json)
}
