plugins {
    id("com.android.application")
}

val releaseSmokeTest = providers.gradleProperty("releaseSmokeTest").map { it.toBooleanStrict() }.getOrElse(false)
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").map { it.toBooleanStrict() }.getOrElse(false)
val productionSigningConfigured = providers.gradleProperty("releaseSigningKeystorePath").isPresent ||
    listOf("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_BASE64", "ANDROID_KEY_ALIAS", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_PASSWORD").any {
        providers.environmentVariable(it).isPresent
    }
require(!releaseSmokeTest || (!requireReleaseSigning && !productionSigningConfigured)) {
    "Release smoke signing is test-only and cannot coexist with publication or production signing configuration"
}
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").getOrElse("1").toInt()
require(releaseVersionCode > 0) { "releaseVersionCode must be a positive Android version code" }
val releaseKeystore = providers.gradleProperty("releaseSigningKeystorePath")
    .orElse(providers.environmentVariable("ANDROID_KEYSTORE_PATH")).getOrElse("")
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
val releaseSigningValues = listOf(releaseKeystore, releaseKeyAlias, releaseStorePassword, releaseKeyPassword)
val hasReleaseSigning = releaseSigningValues.all { it.isNotEmpty() }
require(releaseSigningValues.none { it.isNotEmpty() } || hasReleaseSigning) {
    "Incomplete release signing configuration; provide the keystore path and all three signing environment variables"
}
require(!requireReleaseSigning || hasReleaseSigning) {
    "Publication requires manually configured release signing; unsigned APK publication is forbidden"
}

android {
    namespace = "org.screencopy.receiver"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "org.screencopy.receiver"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = providers.gradleProperty("releaseVersionName").getOrElse("0.1.0")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isReturnDefaultValues = false
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets["test"].resources.directories.add(rootProject.file("protocol/fixtures").path)
    lint {
        abortOnError = true
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    if (hasReleaseSigning) {
        val publicationSigning = signingConfigs.create("publication") {
            storeFile = file(releaseKeystore)
            keyAlias = releaseKeyAlias
            storePassword = releaseStorePassword
            keyPassword = releaseKeyPassword
        }
        buildTypes.getByName("release").signingConfig = publicationSigning
    }
    if (releaseSmokeTest) {
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("debug")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
