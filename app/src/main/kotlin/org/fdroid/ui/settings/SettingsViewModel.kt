package org.fdroid.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Process
import android.os.Process.myUid
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.lang.Runtime.getRuntime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.fdroid.R
import org.fdroid.install.DhizukuInstaller
import org.fdroid.install.ExternalAppInstaller
import org.fdroid.install.MicroGInstaller
import org.fdroid.install.PrivilegedInstaller
import org.fdroid.install.RootInstaller
import org.fdroid.install.ShizukuInstaller
import org.fdroid.repo.RepoUpdateWorker
import org.fdroid.settings.SettingsConstants.InstallMethod
import org.fdroid.settings.SettingsManager
import org.fdroid.ui.utils.applyNewTheme
import org.fdroid.updates.AppUpdateWorker
import org.fdroid.updates.UpdatesManager

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
  app: Application,
  updatesManager: UpdatesManager,
  private val settingsManager: SettingsManager,
  private val appIconManager: AppIconManager,
  private val rootInstaller: RootInstaller,
  private val privilegedInstaller: PrivilegedInstaller,
  private val shizukuInstaller: ShizukuInstaller,
  private val dhizukuInstaller: DhizukuInstaller,
  private val microGInstaller: MicroGInstaller,
  private val externalAppInstaller: ExternalAppInstaller,
) : AndroidViewModel(app) {

  private val log = KotlinLogging.logger {}

  val model =
    SettingsModel(
      prefsFlow = settingsManager.prefsFlow,
      nextRepoUpdateFlow = updatesManager.nextRepoUpdateFlow,
      nextAppUpdateFlow = updatesManager.nextAppUpdateFlow,
      currentAppIconFlow = appIconManager.currentAppIcon,
    )

  init {
    viewModelScope.launch {
      // react to theme changes right away
      settingsManager.themeFlow.drop(1).collect { if (it != null) applyNewTheme(it) }
    }
    viewModelScope.launch(Dispatchers.IO) {
      // re-check if the user types another package name for the external installer
      settingsManager.externalInstallerPackageFlow.drop(1).collect {
        if (settingsManager.installMethod == InstallMethod.ExternalApp) {
          checkInstallMethod(InstallMethod.ExternalApp)
        }
      }
    }
    viewModelScope.launch(Dispatchers.IO) {
      // test newly selected install method right away
      settingsManager.installMethodFlow.drop(1).collect { checkInstallMethod(it) }
    }
    viewModelScope.launch {
      // react to repo auto update changes
      settingsManager.repoUpdatesFlow.drop(1).collect { value ->
        RepoUpdateWorker.scheduleOrCancel(application, value)
      }
    }
    viewModelScope.launch {
      // react to app auto update changes
      settingsManager.autoUpdateAppsFlow.drop(1).collect { value ->
        AppUpdateWorker.scheduleOrCancel(application, value)
      }
    }
  }

  /**
   * Verifies the newly chosen install method right away. For root this triggers the Magisk/KernelSU
   * prompt, for the privileged extension we check that it is installed and allows this app.
   * If the method can't be used, we go back to the standard installer.
   */
  private suspend fun checkInstallMethod(method: InstallMethod) {
    when (method) {
      InstallMethod.Standard -> Unit
      InstallMethod.Root -> {
        if (rootInstaller.isRootAvailable()) {
          sendToast(R.string.pref_install_method_root_ok)
        } else {
          settingsManager.setInstallMethod(InstallMethod.Standard)
          sendToast(R.string.pref_install_method_root_failed, Toast.LENGTH_LONG)
        }
      }
      InstallMethod.PrivilegedExtension -> {
        when (val status = privilegedInstaller.checkStatus()) {
          PrivilegedInstaller.Status.Ready ->
            sendToast(R.string.pref_install_method_privileged_ok)
          else -> {
            log.warn { "Privileged extension not usable: $status" }
            settingsManager.setInstallMethod(InstallMethod.Standard)
            val res =
              if (status == PrivilegedInstaller.Status.NotInstalled) {
                R.string.pref_install_method_privileged_not_installed
              } else {
                R.string.pref_install_method_privileged_not_allowed
              }
            sendToast(res, Toast.LENGTH_LONG)
          }
        }
      }
      InstallMethod.Shizuku -> {
        // requests permission from Shizuku if we don't have it yet
        when (val status = shizukuInstaller.checkStatus(requestPermission = true)) {
          ShizukuInstaller.Status.Ready -> sendToast(R.string.pref_install_method_shizuku_ok)
          else -> {
            log.warn { "Shizuku not usable: $status" }
            settingsManager.setInstallMethod(InstallMethod.Standard)
            val res =
              when (status) {
                ShizukuInstaller.Status.PermissionDenied ->
                  R.string.pref_install_method_shizuku_denied
                ShizukuInstaller.Status.Unsupported -> R.string.pref_install_method_shizuku_old
                else -> R.string.pref_install_method_shizuku_not_running
              }
            sendToast(res, Toast.LENGTH_LONG)
          }
        }
      }
      InstallMethod.Dhizuku -> {
        // requests permission from Dhizuku if we don't have it yet
        when (val status = dhizukuInstaller.checkStatus(requestPermission = true)) {
          DhizukuInstaller.Status.Ready -> sendToast(R.string.pref_install_method_dhizuku_ok)
          else -> {
            log.warn { "Dhizuku not usable: $status" }
            settingsManager.setInstallMethod(InstallMethod.Standard)
            val res =
              if (status == DhizukuInstaller.Status.PermissionDenied) {
                R.string.pref_install_method_dhizuku_denied
              } else {
                R.string.pref_install_method_dhizuku_not_available
              }
            sendToast(res, Toast.LENGTH_LONG)
          }
        }
      }
      InstallMethod.MicroG -> {
        if (microGInstaller.isAvailable()) {
          sendToast(R.string.pref_install_method_microg_ok)
        } else {
          settingsManager.setInstallMethod(InstallMethod.Standard)
          sendToast(R.string.pref_install_method_microg_not_available, Toast.LENGTH_LONG)
        }
      }
      InstallMethod.ExternalApp -> {
        val status = externalAppInstaller.checkStatus()
        if (status == ExternalAppInstaller.Status.Ready) {
          sendToast(R.string.pref_install_method_external_ok)
        } else {
          log.warn { "External installer not usable: $status" }
          // keep the method, so the user can fix the package name; installs fall back until then
          val res =
            when (status) {
              ExternalAppInstaller.Status.NoPackageSet ->
                R.string.pref_install_method_external_no_package
              ExternalAppInstaller.Status.NotInstalled ->
                R.string.pref_install_method_external_not_installed
              else -> R.string.pref_install_method_external_cannot_install
            }
          sendToast(res, Toast.LENGTH_LONG)
        }
      }
    }
  }

  fun onChangeAppIcon(appIcon: AppIcon) {
    appIconManager.setAppIcon(appIcon)

    // On Android SDK versions below 26, changing the app icon may require a restart to take effect
    if (SDK_INT < 26) {
      val packageManager = application.packageManager
      val intent = packageManager.getLaunchIntentForPackage(application.packageName)
      val componentName = intent?.component
      val mainIntent = Intent.makeRestartActivityTask(componentName)
      application.startActivity(mainIntent)
      getRuntime().exit(0)
    }
  }

  fun onSaveLogcat(uri: Uri?) =
    viewModelScope.launch(Dispatchers.IO) {
      if (uri == null) {
        sendToast(R.string.export_log_error)
        return@launch
      }
      val command =
        if (SDK_INT < 30) {
          // support for --pid was introduced in SDK 24
          "logcat -d --pid=${Process.myPid()} *:V"
        } else {
          // support for --uid was introduced in SDK 30 and is better,
          // because it gives logs before process death
          "logcat -d --uid=${myUid()} *:V"
        }
      try {
        application.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
          getRuntime().exec(command).inputStream.use { inputStream ->
            // first log command, so we see if it is correct, e.g. has our own pid
            outputStream.write("$command\n\n".toByteArray())
            inputStream.copyTo(outputStream)
          }
        } ?: throw IOException("OutputStream was null")
        sendToast(R.string.export_log_success)
      } catch (e: Exception) {
        log.error(e) { "Error saving logcat " }
        sendToast(R.string.export_log_error)
      }
    }

  private suspend fun sendToast(@StringRes s: Int, duration: Int = LENGTH_SHORT) {
    withContext(Dispatchers.Main) { Toast.makeText(application, s, duration).show() }
  }
}
