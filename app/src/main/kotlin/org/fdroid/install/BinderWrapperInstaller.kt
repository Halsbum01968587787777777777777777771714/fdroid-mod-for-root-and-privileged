package org.fdroid.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Installs an APK through the system's [PackageInstaller], but executes every call into the
 * system with the identity of a privileged helper. The helper is chosen by the [wrap] function:
 * - Shizuku: `ShizukuBinderWrapper` (calls run as shell or root)
 * - Dhizuku: `Dhizuku.binderWrapper` (calls run as the device owner)
 *
 * This is the same approach Aurora Store (Shizuku installer) and InstallerX (Dhizuku installer)
 * use. It needs hidden framework APIs, which are accessed via reflection after lifting the hidden
 * API restrictions with [HiddenApiBypass]. No compile-time stubs are needed.
 */
internal class BinderWrapperInstaller(private val context: Context) {

  private val log = KotlinLogging.logger {}
  private val mutex = Mutex()

  private companion object {
    /** From AOSP: PackageManager.INSTALL_REPLACE_EXISTING */
    const val INSTALL_REPLACE_EXISTING = 0x00000002
    val INSTALL_TIMEOUT = 10.minutes
    val hiddenApiLock = Any()
    @Volatile var hiddenApiLifted = false
  }

  private fun liftHiddenApiRestrictions() {
    if (hiddenApiLifted || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    synchronized(hiddenApiLock) {
      if (hiddenApiLifted) return
      hiddenApiLifted = HiddenApiBypass.addHiddenApiExemptions("L")
      log.info { "Hidden API exemptions added: $hiddenApiLifted" }
    }
  }

  /**
   * @param installerPackageName package that is recorded as the installer. It must be the package
   *   of the privileged helper if the helper is not shell/root (Dhizuku), because the system
   *   checks that the caller owns it.
   * @param wrap wraps a system binder, so transactions run with the helper's identity.
   */
  suspend fun install(
    apkFile: File,
    packageName: String,
    state: InstallStateWithInfo,
    installerPackageName: String,
    wrap: (IBinder) -> IBinder,
  ): InstallState =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        var session: PackageInstaller.Session? = null
        var receiver: BroadcastReceiver? = null
        try {
          liftHiddenApiRestrictions()
          val iPackageInstaller = wrappedIPackageInstaller(wrap)
          val packageInstaller = createPackageInstaller(iPackageInstaller, installerPackageName)

          val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
              .apply {
                setAppPackageName(packageName)
                addInstallFlags(this, INSTALL_REPLACE_EXISTING)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                  setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
              }
          val sessionId = packageInstaller.createSession(params)
          val openedSession = openWrappedSession(iPackageInstaller, sessionId, wrap)
          session = openedSession

          openedSession
            .openWrite("${packageName}_${System.currentTimeMillis()}", 0, apkFile.length())
            .use { out ->
              apkFile.inputStream().use { it.copyTo(out) }
              openedSession.fsync(out)
            }

          // commit and wait for the result
          val results = Channel<Intent>(Channel.UNLIMITED)
          val action = "${context.packageName}.action.BINDER_INSTALL_RESULT.$sessionId"
          receiver =
            object : BroadcastReceiver() {
              override fun onReceive(c: Context, intent: Intent) {
                results.trySend(intent)
              }
            }
          ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
          )
          val pendingIntent =
            PendingIntentCompat.getBroadcast(
              context,
              sessionId,
              Intent(action).setPackage(context.packageName),
              PendingIntent.FLAG_UPDATE_CURRENT,
              true, // must be mutable, the system adds the status extras
            ) ?: return@withLock InstallState.Error("Could not create PendingIntent", state)
          openedSession.commit(pendingIntent.intentSender)

          val result =
            withTimeoutOrNull(INSTALL_TIMEOUT) {
              var finalState: InstallState? = null
              while (finalState == null) {
                finalState = evaluate(results.receive(), state)
              }
              finalState
            }
          if (result == null) {
            runCatching { openedSession.abandon() }
            InstallState.Error("Timeout while waiting for the install result", state)
          } else {
            result
          }
        } catch (e: Throwable) {
          log.error(e) { "Install via binder wrapper failed: " }
          runCatching { session?.abandon() }
          InstallState.Error("${e::class.java.simpleName} ${e.message}", state)
        } finally {
          runCatching { session?.close() }
          receiver?.let { runCatching { context.unregisterReceiver(it) } }
        }
      }
    }

  /** Returns the final state, or null if we need to keep waiting (e.g. user action needed). */
  private fun evaluate(intent: Intent, state: InstallStateWithInfo): InstallState? {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
    log.info { "Install status $status: $message" }
    return when (status) {
      PackageInstaller.STATUS_SUCCESS ->
        InstallState.Installed(
          name = state.name,
          versionName = state.versionName,
          currentVersionName = state.currentVersionName,
          lastUpdated = state.lastUpdated,
          iconModel = state.iconModel,
        )
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        // the privileged helper did not avoid the confirmation, so ask the user
        val confirm =
          IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (confirm != null) {
          try {
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          } catch (e: Exception) {
            log.error(e) { "Could not start user confirmation: " }
            return InstallState.Error("User confirmation needed: ${e.message}", state)
          }
          null
        } else {
          InstallState.Error("User confirmation needed", state)
        }
      }
      else ->
        InstallState.Error(message ?: "Install failed (status $status)", state)
    }
  }

  /** Gets the system's IPackageInstaller and wraps its binder. */
  private fun wrappedIPackageInstaller(wrap: (IBinder) -> IBinder): Any {
    val base = context.packageManager.packageInstaller
    val field = PackageInstaller::class.java.getDeclaredField("mInstaller")
    field.isAccessible = true
    val mInstaller = field.get(base) as IInterface
    val stub = Class.forName("android.content.pm.IPackageInstaller\$Stub")
    val asInterface = stub.getMethod("asInterface", IBinder::class.java)
    return checkNotNull(asInterface.invoke(null, wrap(mInstaller.asBinder()))) {
      "IPackageInstaller.Stub.asInterface returned null"
    }
  }

  /**
   * Creates a [PackageInstaller] that uses [iPackageInstaller] (hidden constructor). The parameters
   * differ between Android versions, so we fill the constructor by parameter type.
   */
  private fun createPackageInstaller(
    iPackageInstaller: Any,
    installerPackageName: String,
  ): PackageInstaller {
    val iPackageInstallerClass = Class.forName("android.content.pm.IPackageInstaller")
    val constructor =
      PackageInstaller::class.java.declaredConstructors.firstOrNull {
        it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == iPackageInstallerClass
      } ?: error("No suitable PackageInstaller constructor found (SDK ${Build.VERSION.SDK_INT})")
    constructor.isAccessible = true
    val userId = Process.myUid() / 100000
    var stringsSeen = 0
    val args =
      constructor.parameterTypes.mapIndexed { index, type ->
        when {
          index == 0 -> iPackageInstaller
          type == String::class.java -> if (stringsSeen++ == 0) installerPackageName else null
          type == Int::class.javaPrimitiveType -> userId
          else -> error("Unexpected PackageInstaller constructor parameter: $type")
        }
      }
    return constructor.newInstance(*args.toTypedArray()) as PackageInstaller
  }

  /** Opens the session and replaces its binder by a wrapped one, so writing/commit is privileged. */
  private fun openWrappedSession(
    iPackageInstaller: Any,
    sessionId: Int,
    wrap: (IBinder) -> IBinder,
  ): PackageInstaller.Session {
    val iPackageInstallerClass = Class.forName("android.content.pm.IPackageInstaller")
    val openSession =
      iPackageInstallerClass.getMethod("openSession", Int::class.javaPrimitiveType)
    val rawSession = openSession.invoke(iPackageInstaller, sessionId) as IInterface

    val sessionClass = Class.forName("android.content.pm.IPackageInstallerSession")
    val sessionStub = Class.forName("android.content.pm.IPackageInstallerSession\$Stub")
    val wrappedSession =
      checkNotNull(
        sessionStub
          .getMethod("asInterface", IBinder::class.java)
          .invoke(null, wrap(rawSession.asBinder()))
      )
    val constructor = PackageInstaller.Session::class.java.getDeclaredConstructor(sessionClass)
    constructor.isAccessible = true
    return constructor.newInstance(wrappedSession)
  }

  /** SessionParams#installFlags is a hidden field. */
  private fun addInstallFlags(params: PackageInstaller.SessionParams, flags: Int) {
    val field = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
    field.isAccessible = true
    field.setInt(params, field.getInt(params) or flags)
  }
}
