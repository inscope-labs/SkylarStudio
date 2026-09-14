plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.inscopelabs.abx.skylar"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.inscopelabs.abx.skylar"
    minSdk = 24
    targetSdk = 36
    val propVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull()
    val propVersionName = project.findProperty("versionName")?.toString()
    versionCode = propVersionCode ?: 1
    versionName = propVersionName ?: "0.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    buildConfig = true
    aidl = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
      all {
        (it as? org.gradle.api.tasks.testing.Test)?.apply {
          systemProperty("sun.net.client.defaultConnectTimeout", "60000")
          systemProperty("sun.net.client.defaultReadTimeout", "120000")
          systemProperty("http.keepAlive", "false")
        }
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

dependencies {
  implementation(project(":libs:skylar-envelope"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)

  // Phase 2 (libtailscale critical-path spike): MeshNode's StateFlow surface.
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)

  // Phase 2: bound tsnet artifact, produced by `gomobile bind` in
  // .github/workflows/phase-2-libtailscale-bind.yml and committed to
  // app/libs/ by that workflow's own CI job (see that file's "Commit the
  // built tsnet AAR" step — this environment has no way to build or fetch
  // the binary itself, only CI with real Go/NDK toolchains can produce it).
  //
  // Guarded by file existence so a hypothetically missing AAR doesn't
  // break dependency resolution for the rest of the app with an opaque
  // Gradle error. This is NOT a graceful fallback for TsnetMeshNode.kt
  // itself, though: Kotlin can't conditionally skip a class reference, so
  // TsnetMeshNode.kt importing tsnetbind.Server has a hard, unconditional
  // dependency on this file actually being present — if it's ever
  // missing, compilation fails there specifically, with a clear
  // unresolved-reference error rather than this guard silently working
  // around it.
  if (file("libs/tsnet-android.aar").exists()) {
    implementation(files("libs/tsnet-android.aar"))
  }
}
