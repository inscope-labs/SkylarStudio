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
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
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

  // Phase 2 (libtailscale critical-path spike): MeshNode's StateFlow surface.
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)

  // TODO(phase-2): once `gomobile bind` produces the tsnet Android artifact
  // (Phase 2 §4 work item 1-2), add it here, e.g.:
  //   implementation(files("libs/tsnet-android.aar"))
  // or, if published to a registry:
  //   implementation("com.tailscale:tsnet-android:<version>")
  // Not added yet — no bound artifact exists in this repo as of this scaffold.
}
