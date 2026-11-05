plugins {
  id("com.android.application")
}

android {
  namespace = "cl.digitalmonster.opencast"
  compileSdk = 37

  defaultConfig {
    applicationId = "cl.digitalmonster.opencast"
    minSdk = 29
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
  }

  signingConfigs {
    // Clave pública de desarrollo: permite instalar cada build encima de la anterior.
    // Para versiones oficiales se usará una clave privada guardada en GitHub Secrets.
    create("dev") {
      storeFile = file("opencast-dev.keystore")
      storePassword = "opencast-dev"
      keyAlias = "opencast-dev"
      keyPassword = "opencast-dev"
    }
  }

  buildTypes {
    debug {
      signingConfig = signingConfigs.getByName("dev")
    }
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("dev")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("com.google.android.material:material:1.12.0")
}
