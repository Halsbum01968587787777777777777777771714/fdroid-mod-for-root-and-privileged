package org.fdroid.install

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.fdroid.settings.SettingsManager

/**
 * Hands the APK to another installer app that the user picked by package name (for example App
 * Manager, `io.github.muntashirakon.AppManager`). The request is the standard "open this APK"
 * intent (ACTION_VIEW with the APK mime type), restricted to the chosen package.
 *
 * Such apps don't report back, so we check the package manager to see if the install happened.
 * Whether a dialog still shows up depends on the other app.
 */
@Singleton
class ExternalAppInstaller
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
  private val settingsManager: SettingsManager,
) {

  private val log = KotlinLogging.logger {}

  private companion object {
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    val INSTALL_TIMEOUT = 5.minutes
  }

  sealed class Status {
    data object Ready : Status()

    /** No package name was entered. */
    data object NoPackageSet : Status()

    data object NotInstalled : Status()

    /** The app is installed, but has no screen that accepts APK files. */
    data object CannotInstall : Status()
  }

  private fun buildInstallIntent(uri: Uri, installerPackage: String): Intent =
    Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, APK_MIME_TYPE)
      setPackage(installerPackage)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

  /** Checks if [installerPackage] (default: the one from the settings) can install APKs. */
  fun checkStatus(installerPackage: String = settingsManager.externalInstallerPackage): Status {
    if (installerPackage.isBlank()) return Status.NoPackageSet
    try {
      context.packageManager.getPackageInfo(installerPackage, 0)
    } catch (_: PackageManager.NameNotFoundException) {
      return Status.NotInstalled
    }
    // an example URI is enough to find out if the app handles APK files from content URIs
    val example = Uri.parse("content://${context.packageName}.fileprovider/example.apk")
    val canHandle =
      context.packageManager.resolveActivity(buildInstallIntent(example, installerPackage), 0)
    return if (canHandle != null) Status.Ready else Status.CannotInstall
  }

  /**
   * @return `null` if the chosen app can't be used, so the caller can fall back to another
   *   installer. Otherwise the final install state.
   */
  suspend fun install(apkFile: File, packageName: String, state: InstallStateWithInfo): InstallState? =
    withContext(Dispatchers.IO) {
      val installerPackage = settingsManager.externalInstallerPackage
      if (checkStatus(installerPackage) != Status.Ready) {
        log.warn { "'$installerPackage' can't install APKs, falling back to other installer..." }
        return@withContext null
      }
      val uri =
        try {
          FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } catch (e: Exception) {
          log.error(e) { "Could not create URI for $apkFile: " }
          return@withContext InstallState.Error("${e::class.java.simpleName} ${e.message}", state)
        }
      context.grantUriPermission(installerPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      val sinceMs = System.currentTimeMillis()
      try {
        try {
          context.startActivity(buildInstallIntent(uri, installerPackage))
        } catch (e: ActivityNotFoundException) {
          log.error(e) { "Could not start $installerPackage: " }
          return@withContext null
        } catch (e: SecurityException) {
          log.error(e) { "Not allowed to start $installerPackage: " }
          return@withContext null
        }
        val installed =
          withTimeoutOrNull(INSTALL_TIMEOUT) {
            while (!context.isInstalledSince(packageName, sinceMs)) delay(1000)
            true
          } ?: false
        if (installed) {
          InstallState.Installed(
            name = state.name,
            versionName = state.versionName,
            currentVersionName = state.currentVersionName,
            lastUpdated = state.lastUpdated,
            iconModel = state.iconModel,
          )
        } else {
          InstallState.Error(
            "$installerPackage did not install the app (cancelled, failed or took too long)",
            state,
          )
        }
      } finally {
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    }
}
