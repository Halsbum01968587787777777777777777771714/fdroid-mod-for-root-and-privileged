package org.fdroid.install

import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.RemoteException
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.fdroid.fdroid.privileged.IPrivilegedCallback
import org.fdroid.fdroid.privileged.IPrivilegedService

/**
 * Installer that only works if the "F-Droid Privileged Extension" is installed as a privileged app
 * (priv-app), e.g. via a Magisk module. Backported from F-Droid 1.23 (`PrivilegedInstaller.java`).
 *
 * The extension exposes a bound service which executes install/uninstall inside its own privileged
 * context (it holds INSTALL_PACKAGES / DELETE_PACKAGES). We only talk to it via AIDL. The extension
 * checks the package name and signing certificate of the caller against its own whitelist, so this
 * build must be signed with a certificate the installed extension trusts.
 */
@Singleton
class PrivilegedInstaller
@Inject
constructor(@param:ApplicationContext private val context: Context) {

  private val log = KotlinLogging.logger {}

  /** The extension stores a single callback, so installs must not run in parallel. */
  private val mutex = Mutex()

  companion object {
    const val EXTENSION_PACKAGE_NAME = "org.fdroid.fdroid.privileged"
    private const val SERVICE_ACTION = "org.fdroid.fdroid.privileged.IPrivilegedService"

    /** From AOSP: PackageManager.INSTALL_REPLACE_EXISTING */
    private const val INSTALL_REPLACE_EXISTING = 2

    /** Return codes are the legacy PackageManager codes copied from AOSP. */
    private const val INSTALL_SUCCEEDED = 1

    private val BIND_TIMEOUT = 15.seconds
    private val INSTALL_TIMEOUT = 5.minutes

    private val INSTALL_RETURN_CODES =
      mapOf(
        1 to "Success",
        -1 to "Package is already installed.",
        -2 to "The package archive file is invalid.",
        -3 to "The URI passed in is invalid.",
        -4 to "Not enough storage space.",
        -5 to "A package is already installed with the same name.",
        -6 to "The requested shared user does not exist.",
        -7 to "Signature of the installed package differs from the new package.",
        -8 to "The new package requested a shared user with a non-matching signature.",
        -9 to "The new package uses a shared library that is not available.",
        -10 to "Could not delete the package that should be replaced.",
        -11 to "Failed while optimizing and validating dex files.",
        -12 to "The current SDK version is older than required by the package.",
        -13 to "Conflicting content provider authority.",
        -14 to "The current SDK version is newer than allowed by the package.",
        -15 to "The package is test-only.",
        -16 to "No compatible CPU ABI.",
        -17 to "The package uses a feature that is not available.",
        -18 to "A secure container mount point couldn't be accessed.",
        -19 to "Invalid install location.",
        -20 to "Install media not available.",
        -21 to "Verification timed out.",
        -22 to "Verification failed.",
        -23 to "The package changed from what the caller expected.",
        -24 to "The new package is assigned a different UID than before.",
        -25 to "Version downgrade is not allowed.",
        -100 to "The parser was given a path that is not an APK.",
        -101 to "The parser was unable to retrieve AndroidManifest.xml.",
        -102 to "The parser encountered an unexpected exception.",
        -103 to "The parser did not find any certificates in the APK.",
        -104 to "The parser found inconsistent certificates.",
        -105 to "The parser encountered a certificate encoding exception.",
        -106 to "Bad or missing package name in the manifest.",
        -107 to "Bad shared user id in the manifest.",
        -108 to "Structural problem in the manifest.",
        -109 to "No actionable tags in the manifest.",
        -110 to "The system failed to install the package because of system issues.",
        -111 to "The user is restricted from installing apps.",
        -112 to "The package defines a permission that already exists.",
        -113 to "The native code does not match any ABI of this device.",
      )
  }

  /** Result of [checkStatus]. */
  sealed class Status {
    /** The extension is installed, privileged and allows this app. */
    data object Ready : Status()

    data object NotInstalled : Status()

    /**
     * The extension is installed, but either has no INSTALL_PACKAGES/DELETE_PACKAGES permission (not
     * installed as priv-app, or missing permission allow-list) or it rejected this app's package
     * name / signing certificate.
     */
    data object NotPrivilegedOrNotAllowed : Status()

    data class Error(val msg: String) : Status()
  }

  private fun isExtensionInstalled(): Boolean {
    return try {
      // needs <queries> entry in the manifest on Android 11+
      context.packageManager.getApplicationInfo(EXTENSION_PACKAGE_NAME, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }
  }

  private fun createServiceIntent() = Intent(SERVICE_ACTION).setPackage(EXTENSION_PACKAGE_NAME)

  /** Binds to the service and returns it. The caller must call [unbind] with [connection]. */
  private class Connection {
    val service = CompletableDeferred<IPrivilegedService>()
    val serviceConnection =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
          service.complete(IPrivilegedService.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName) {
          service.completeExceptionally(IllegalStateException("Service disconnected"))
        }

        override fun onBindingDied(name: ComponentName) {
          service.completeExceptionally(IllegalStateException("Binding died"))
        }

        override fun onNullBinding(name: ComponentName) {
          service.completeExceptionally(IllegalStateException("Service returned null binding"))
        }
      }
  }

  private suspend fun <T> withService(block: suspend (IPrivilegedService) -> T): T {
    val connection = Connection()
    val bound =
      try {
        context.applicationContext.bindService(
          createServiceIntent(),
          connection.serviceConnection,
          BIND_AUTO_CREATE,
        )
      } catch (e: SecurityException) {
        throw IllegalStateException("Not allowed to bind to privileged extension", e)
      }
    if (!bound) {
      try {
        context.applicationContext.unbindService(connection.serviceConnection)
      } catch (_: IllegalArgumentException) {}
      throw IllegalStateException("Could not bind to privileged extension")
    }
    try {
      val service = withTimeout(BIND_TIMEOUT) { connection.service.await() }
      return block(service)
    } finally {
      try {
        context.applicationContext.unbindService(connection.serviceConnection)
      } catch (_: IllegalArgumentException) {
        // already unbound
      }
    }
  }

  /** Checks if the extension is installed, privileged and if this app is allowed to use it. */
  suspend fun checkStatus(): Status =
    withContext(Dispatchers.IO) {
      if (!isExtensionInstalled()) return@withContext Status.NotInstalled
      try {
        withService { service ->
          if (service.hasPrivilegedPermissions()) Status.Ready
          else Status.NotPrivilegedOrNotAllowed
        }
      } catch (e: Exception) {
        log.error(e) { "Error checking privileged extension: " }
        Status.Error("${e::class.java.simpleName} ${e.message}")
      }
    }

  /**
   * Installs the given APK via the extension without any user interaction.
   *
   * @return `null` if the extension can't be used (not installed, not privileged, not allowing this
   *   app), so the caller can fall back to another installer. Otherwise the final install state.
   */
  suspend fun install(apkFile: File, state: InstallStateWithInfo): InstallState? =
    withContext(Dispatchers.IO) {
      if (checkStatus() != Status.Ready) {
        log.warn { "Privileged extension not usable, falling back to other installer..." }
        return@withContext null
      }
      mutex.withLock {
        val uri: Uri =
          try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
          } catch (e: Exception) {
            log.error(e) { "Could not create URI for $apkFile: " }
            return@withLock InstallState.Error("${e::class.java.simpleName} ${e.message}", state)
          }
        // The extension runs in another process and needs read access to our APK.
        context.grantUriPermission(EXTENSION_PACKAGE_NAME, uri, FLAG_GRANT_READ_URI_PERMISSION)
        try {
          installViaService(uri, state)
        } catch (e: Exception) {
          log.error(e) { "Error installing via privileged extension: " }
          InstallState.Error("${e::class.java.simpleName} ${e.message}", state)
        } finally {
          context.revokeUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)
        }
      }
    }

  private suspend fun installViaService(uri: Uri, state: InstallStateWithInfo): InstallState =
    withService { service ->
      val result = CompletableDeferred<Int>()
      val callback =
        object : IPrivilegedCallback.Stub() {
          override fun handleResult(packageName: String?, returnCode: Int) {
            log.info { "Privileged extension result for $packageName: $returnCode" }
            result.complete(returnCode)
          }
        }
      try {
        service.installPackage(uri, INSTALL_REPLACE_EXISTING, EXTENSION_PACKAGE_NAME, callback)
      } catch (e: RemoteException) {
        return@withService InstallState.Error("Connecting to privileged service failed", state)
      }
      val returnCode = withTimeoutOrNull(INSTALL_TIMEOUT) { result.await() }
      when (returnCode) {
        null -> InstallState.Error("Privileged extension did not answer in time", state)
        INSTALL_SUCCEEDED ->
          InstallState.Installed(
            name = state.name,
            versionName = state.versionName,
            currentVersionName = state.currentVersionName,
            lastUpdated = state.lastUpdated,
            iconModel = state.iconModel,
          )
        0 -> InstallState.Error("Privileged extension is not compatible with this Android", state)
        else ->
          InstallState.Error("Error $returnCode: ${INSTALL_RETURN_CODES[returnCode] ?: "Unknown"}", state)
      }
    }
}
