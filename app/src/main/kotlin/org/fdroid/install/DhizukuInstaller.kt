package org.fdroid.install

import android.content.Context
import android.content.pm.PackageManager
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
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

/**
 * Silent installs as device owner through [Dhizuku](https://github.com/iamr0s/Dhizuku). Dhizuku
 * has to be set up as device owner/active and this app needs to be granted access in Dhizuku.
 */
@Singleton
class DhizukuInstaller @Inject constructor(@param:ApplicationContext private val context: Context) {

  private val log = KotlinLogging.logger {}
  private val engine = BinderWrapperInstaller(context)

  private companion object {
    val PERMISSION_TIMEOUT = 60.seconds
  }

  sealed class Status {
    data object Ready : Status()

    /** Dhizuku is not installed, not active or not working. */
    data object NotAvailable : Status()

    data object PermissionDenied : Status()
  }

  /**
   * Checks if Dhizuku is usable.
   *
   * @param requestPermission if true and we don't have permission yet, Dhizuku shows its dialog.
   */
  suspend fun checkStatus(requestPermission: Boolean): Status =
    withContext(Dispatchers.IO) {
      try {
        if (!Dhizuku.init(context)) return@withContext Status.NotAvailable
        if (Dhizuku.isPermissionGranted()) return@withContext Status.Ready
        if (!requestPermission) return@withContext Status.PermissionDenied
        if (requestPermissionAndWait()) Status.Ready else Status.PermissionDenied
      } catch (e: Throwable) {
        log.error(e) { "Error checking Dhizuku: " }
        Status.NotAvailable
      }
    }

  private suspend fun requestPermissionAndWait(): Boolean =
    withTimeoutOrNull(PERMISSION_TIMEOUT) {
      suspendCancellableCoroutine<Boolean> { cont ->
        Dhizuku.requestPermission(
          object : DhizukuRequestPermissionListener() {
            override fun onRequestPermission(grantResult: Int) {
              if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
            }
          }
        )
      }
    } ?: false

  /**
   * @return `null` if Dhizuku can't be used, so the caller can fall back to another installer.
   *   Otherwise the final install state.
   */
  suspend fun install(apkFile: File, packageName: String, state: InstallStateWithInfo): InstallState? {
    if (checkStatus(requestPermission = false) != Status.Ready) {
      log.warn { "Dhizuku not usable, falling back to other installer..." }
      return null
    }
    return engine.install(
      apkFile = apkFile,
      packageName = packageName,
      state = state,
      // the system checks that the caller owns the installer package, so use the owner's package
      installerPackageName = Dhizuku.getOwnerPackageName(),
      wrap = { Dhizuku.binderWrapper(it) },
    )
  }
}
