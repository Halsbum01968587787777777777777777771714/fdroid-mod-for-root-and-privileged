import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins { alias(libs.plugins.android.application) }

// Same keystore.properties as the F-Droid app module. The extension will trust apps (package
// org.fdroid.fdroid) that are signed with this key, and is itself signed with it.
val keystoreProps =
  Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
  }

fun certSha256(): String {
  if (keystoreProps.isEmpty) return ""
  val ks = KeyStore.getInstance(KeyStore.getDefaultType())
  FileInputStream(rootProject.file(keystoreProps.getProperty("storeFile"))).use {
    ks.load(it, keystoreProps.getProperty("storePassword").toCharArray())
  }
  val cert = ks.getCertificate(keystoreProps.getProperty("keyAlias"))
  return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") {
    "%02x".format(it)
  }
}

android {
  namespace = "org.fdroid.fdroid.privileged"
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "org.fdroid.fdroid.privileged"
    minSdk = 24
    // Must stay low on purpose: the extension builds mutable PendingIntents with implicit intents
    // and registers receivers without export flags. Both are rejected for apps targeting
    // Android 12/14+. This is a system app that is not subject to Play Store target requirements.
    @Suppress("OldTargetApi") targetSdk = 25
    versionCode = 2130
    versionName = "0.2.13"
    buildConfigField("String", "CLIENT_CERT_SHA256", "\"${certSha256()}\"")
  }

  buildFeatures {
    aidl = true
    buildConfig = true
  }

  signingConfigs {
    if (!keystoreProps.isEmpty) {
      create("own") {
        storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      signingConfigs.findByName("own")?.let { signingConfig = it }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
}
