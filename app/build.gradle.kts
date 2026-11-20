import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.richardluo.mifocusconverter"
  compileSdk {
    version = release(37)
  }

  val versionProps =
      Properties().apply { load(FileInputStream("${project.rootDir}/version.properties")) }
  val major = versionProps["majorVersion"].toString().toInt()
  val minor = versionProps["minorVersion"].toString().toInt()
  val patch = versionProps["patchVersion"].toString().toInt()

  defaultConfig {
    applicationId = "com.richardluo.mifocusconverter"
    minSdk = 36
    targetSdk = 37
    versionCode = major * 10000 + minor * 100 + patch
    versionName = "$major.$minor.$patch"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  val keystoreProps =
      Properties().apply { load(FileInputStream("${project.rootDir}/keystore.properties")) }
  signingConfigs {
    create("release") {
      keyAlias = keystoreProps["keyAlias"].toString()
      keyPassword = keystoreProps["keyPassword"].toString()
      storeFile = file(keystoreProps["storeFile"].toString())
      storePassword = keystoreProps["storePassword"].toString()
    }
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    create("debugApp") {
      isDebuggable = true
      signingConfig = signingConfigs.getByName("debug")
      isDefault = true
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    buildConfig = true
  }
  androidResources {
    @Suppress("UnstableApiUsage")
    generateLocaleConfig = true
  }

  dependenciesInfo {
    // Disables dependency metadata when building APKs.
    includeInApk = false
    // Disables dependency metadata when building Android App Bundles.
    includeInBundle = false
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  compileOnly(libs.libxposed.api)
  implementation(libs.androidx.core.ktx)
}
