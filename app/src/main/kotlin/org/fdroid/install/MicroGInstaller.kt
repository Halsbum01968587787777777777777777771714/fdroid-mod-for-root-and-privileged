/*
 * The microG install request (intent action, package check, helper activity) follows the
 * MicroGInstaller of Aurora Store 4.8.4, Copyright (C) 2021 Rahul Kumar Patel, licensed under the
 * GNU General Public License, version 2 or later.
 */
package org.fdroid.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging

/**
 * Installs apps by sending them to the installer of microG Companion (package `com.android.vending`,
 * activity `org.microg.vending.installer.AppInstallActivity`).
 *
 * microG does not report back whether the install worked, so we check the package manager.
 */
@Singleton
class MicroGInstaller @Inject constructor(@param:ApplicationContext private val context: Context) {

  private val log = KotlinLogging.logger {}

  companion object {
    const val PACKAGE_NAME_PLAY_STORE = "com.android.vending"
    const val ACTION_INSTALL_PACKAGE = "org.microg.vending.action.INSTALL_PACKAGE"
    private const val VERSION_CODE_MICROG_COMPANION_MIN = 84022620L
    private const val MICROG_INSTALL_ACTIVITY = "org.microg.vending.installer.AppInstallActivity"

    private val START_TIMEOUT = 10.seconds
    private val ACTIVITY_TIMEOUT = 5.minutes
    private val VERIFY_TIMEOUT_AFTER_OK = 3.minutes
    private val VERIFY_TIMEOUT_AFTER_CANCEL = 10.seconds

    /** Completed by [MicroGInstallActivity] when it was started. */
    @Volatile internal var started: CompletableDeferred<Unit>? = null

    /** Completed by [MicroGInstallActivity] with the result code of microG's activity. */
    @Volatile internal var result: CompletableDeferred<Int>? = null

    fun buildMicroGInstallIntent(uris: ArrayList<Uri>): Intent =
      Intent(ACTION_INSTALL_PACKAGE).apply {
        setPackage(PACKAGE_NAME_PLAY_STORE)
        setType("application/vnd.android.package-archive")
        putExtra(Intent.EXTRA_STREAM, uris)
      }
  }

  /** True if microG Companion with its installer activity is installed. */
  fun isAvailable(): Boolean {
    val packageInfo: PackageInfo =
      try {
        context.packageManager.getPackageInfo(PACKAGE_NAME_PLAY_STORE, 0)
      } catch (_: PackageManager.NameNotFoundException) {
        return false
      }
    if (PackageInfoCompat.getLongVersionCode(packageInfo) < VERSION_CODE_MICROG_COMPANION_MIN) {
      return false
    }
    val intent = Intent().setClassName(PACKAGE_NAME_PLAY_STORE, MICROG_INSTALL_ACTIVITY)
    return context.packageManager.resolveActivity(intent, 0) != null
  }

  /**
   * @return `null` if microG Companion can't be used, so the caller can fall back to another
   *   installer. Otherwise the final install state.
   */
  suspend fun install(apkFile: File, packageName: String, state: InstallStateWithInfo): InstallState? =
    withContext(Dispatchers.IO) {
      if (!isAvailable()) {
        log.warn { "microG Companion not available, falling back to other installer..." }
        return@withContext null
      }
      val uri =
        try {
          FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } catch (e: Exception) {
          log.error(e) { "Could not create URI for $apkFile: " }
          return@withContext InstallState.Error("${e::class.java.simpleName} ${e.message}", state)
        }
      context.grantUriPermission(
        PACKAGE_NAME_PLAY_STORE,
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )

      val sinceMs = System.currentTimeMillis()
      val startedSignal = CompletableDeferred<Unit>()
      val resultSignal = CompletableDeferred<Int>()
      started = startedSignal
      result = resultSignal
      try {
        MicroGInstallActivity.start(context, arrayListOf(uri))
        if (withTimeoutOrNull(START_TIMEOUT) { startedSignal.await() } == null) {
          return@withContext InstallState.Error(
            "Could not open the microG installer. Starting it from the background may be blocked, " +
              "keep F-Droid open and try again.",
            state,
          )
        }
        val resultCode = withTimeoutOrNull(ACTIVITY_TIMEOUT) { resultSignal.await() }
        log.info { "microG installer activity returned: $resultCode" }
        val verifyTimeout =
          if (resultCode == null || resultCode == android.app.Activity.RESULT_CANCELED) {
            VERIFY_TIMEOUT_AFTER_CANCEL
          } else {
            VERIFY_TIMEOUT_AFTER_OK
          }
        val installed =
          withTimeoutOrNull(verifyTimeout) {
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
          InstallState.Error("microG did not install the app (cancelled or failed)", state)
        }
      } catch (e: Exception) {
        log.error(e) { "Error installing via microG: " }
        InstallState.Error("${e::class.java.simpleName} ${e.message}", state)
      } finally {
        started = null
        result = null
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    }
}
