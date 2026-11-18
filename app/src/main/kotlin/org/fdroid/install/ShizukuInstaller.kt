package org.fdroid.install

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Silent installs through [Shizuku](https://shizuku.rikka.app). Shizuku has to be running (started
 * via wireless adb or root) and this app needs to be granted access in Shizuku.
 */
@Singleton
class ShizukuInstaller @Inject constructor(@param:ApplicationContext private val context: Context) {

  private val log = KotlinLogging.logger {}
  private val engine = BinderWrapperInstaller(context)

  private companion object {
    const val REQUEST_CODE = 4242
    val PERMISSION_TIMEOUT = 60.seconds
  }

  sealed class Status {
    data object Ready : Status()

    /** Shizuku is not installed or its service is not running. */
    data object NotRunning : Status()

    /** Shizuku is too old (pre v11). */
    data object Unsupported : Status()

    data object PermissionDenied : Status()
  }

  /**
   * Checks if Shizuku is usable.
   *
   * @param requestPermission if true and we don't have permission yet, Shizuku shows its dialog.
   */
  suspend fun checkStatus(requestPermission: Boolean): Status =
    withContext(Dispatchers.IO) {
      try {
        if (!Shizuku.pingBinder()) return@withContext Status.NotRunning
        if (Shizuku.isPreV11()) return@withContext Status.Unsupported
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
          return@withContext Status.Ready
        }
        // "deny and don't ask again" was chosen, a request would silently do nothing
        if (!requestPermission || Shizuku.shouldShowRequestPermissionRationale()) {
          return@withContext Status.PermissionDenied
        }
        if (requestPermissionAndWait()) Status.Ready else Status.PermissionDenied
      } catch (e: Throwable) {
        log.error(e) { "Error checking Shizuku: " }
        Status.NotRunning
      }
    }

  private suspend fun requestPermissionAndWait(): Boolean =
    withTimeoutOrNull(PERMISSION_TIMEOUT) {
      suspendCancellableCoroutine<Boolean> { cont ->
        val listener =
          object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
              if (requestCode != REQUEST_CODE) return
              Shizuku.removeRequestPermissionResultListener(this)
              if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
            }
          }
        Shizuku.addRequestPermissionResultListener(listener)
        cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
        Shizuku.requestPermission(REQUEST_CODE)
      }
    } ?: false

  /**
   * @return `null` if Shizuku can't be used, so the caller can fall back to another installer.
   *   Otherwise the final install state.
   */
  suspend fun install(apkFile: File, packageName: String, state: InstallStateWithInfo): InstallState? {
    if (checkStatus(requestPermission = false) != Status.Ready) {
      log.warn { "Shizuku not usable, falling back to other installer..." }
      return null
    }
    return engine.install(
      apkFile = apkFile,
      packageName = packageName,
      state = state,
      // shell/root may name any package as the installer, we use ourselves
      installerPackageName = context.packageName,
      wrap = { ShizukuBinderWrapper(it) },
    )
  }
}
