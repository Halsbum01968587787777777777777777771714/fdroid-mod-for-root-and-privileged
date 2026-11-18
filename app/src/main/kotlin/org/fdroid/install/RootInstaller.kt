package org.fdroid.install

import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KotlinLogging

/**
 * Installs apps with root rights (Magisk, KernelSU, ...) by running `pm install` through `su`. The
 * APK is streamed, so no storage paths (scoped storage, SELinux, other users) can get in the way.
 */
@Singleton
class RootInstaller
@Inject
constructor(@param:ApplicationContext private val context: Context) {

  private val log = KotlinLogging.logger {}
  private val mutex = Mutex()

  /** Result of running a shell command via su. */
  data class ShellResult(val exitCode: Int, val output: String)

  companion object {
    /** The first su call can show a Magisk prompt, so give the user time to react. */
    private const val ROOT_CHECK_TIMEOUT_SECONDS = 60L
    private const val INSTALL_TIMEOUT_SECONDS = 300L
  }

  /**
   * Runs `su -c id` which triggers the root prompt of Magisk/KernelSU if root wasn't granted yet.
   *
   * @return true if we got a root shell (uid=0).
   */
  suspend fun isRootAvailable(): Boolean =
    withContext(Dispatchers.IO) {
      try {
        val result = runSu("id", null, ROOT_CHECK_TIMEOUT_SECONDS)
        log.info { "su -c id: ${result.exitCode} ${result.output.trim()}" }
        result.exitCode == 0 && result.output.contains("uid=0")
      } catch (e: Exception) {
        log.error(e) { "Root check failed: " }
        false
      }
    }

  /**
   * Installs the given APK as root.
   *
   * @return `null` if root isn't available, so the caller can fall back to another installer.
   *   Otherwise the final install state.
   */
  suspend fun install(
    apkFile: File,
    packageName: String,
    state: InstallStateWithInfo,
  ): InstallState? =
    withContext(Dispatchers.IO) {
      if (!isRootAvailable()) {
        log.warn { "Root not available, falling back to other installer..." }
        return@withContext null
      }
      mutex.withLock {
        val size = apkFile.length()
        val installer = context.packageName
        // Install for the user F-Droid runs as (0 on normal devices, other for work/secondary profiles)
        val user = Process.myUid() / 100000
        // Updating F-Droid itself makes Android stop this process in the middle of the install, so
        // the root shell starts F-Droid again afterwards. A ";" is needed (not "&&"), because pm may
        // end with an error when nobody reads its output anymore.
        val relaunch =
          if (packageName == context.packageName) {
            "; monkey -p $installer -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"
          } else {
            ""
          }
        // 1. stream the file into `pm install` via stdin (does not depend on any path)
        val streamCmd = "pm install -S $size -r --user $user -i $installer$relaunch"
        // 2. same, but let root read the file with cat (as a shell pipe)
        val catCmd =
          "cat \"${apkFile.absolutePath}\" | pm install -S $size -r --user $user -i $installer$relaunch"
        // 3. last resort: plain `pm install -r` on the path
        val pathCmd =
          "pm install -r --user $user -i $installer \"${apkFile.absolutePath}\"$relaunch"

        val attempts =
          listOf<Pair<String, (() -> ShellResult)>>(
            streamCmd to { runSu(streamCmd, apkFile, INSTALL_TIMEOUT_SECONDS) },
            catCmd to { runSu(catCmd, null, INSTALL_TIMEOUT_SECONDS) },
            pathCmd to { runSu(pathCmd, null, INSTALL_TIMEOUT_SECONDS) },
          )
        var lastError = "Unknown error"
        for ((cmd, run) in attempts) {
          val result =
            try {
              run()
            } catch (e: Exception) {
              log.error(e) { "Error running '$cmd': " }
              lastError = "${e::class.java.simpleName} ${e.message}"
              continue
            }
          log.info { "'$cmd' -> exit ${result.exitCode}: ${result.output.trim()}" }
          if (result.exitCode == 0 && result.output.contains("Success")) {
            return@withLock InstallState.Installed(
              name = state.name,
              versionName = state.versionName,
              currentVersionName = state.currentVersionName,
              lastUpdated = state.lastUpdated,
              iconModel = state.iconModel,
            )
          }
          lastError = parseFailure(result)
          // Don't retry with other methods if the package itself is the problem.
          if (isPackageProblem(result.output)) break
        }
        InstallState.Error(lastError, state)
      }
    }

  /**
   * Uninstalls [packageName] with root rights, without any confirmation.
   *
   * @return true if the app was uninstalled. false if root isn't available or `pm` failed, the caller
   *   should then fall back to the normal system dialog.
   */
  suspend fun uninstall(packageName: String): Boolean =
    withContext(Dispatchers.IO) {
      if (!isSafePackageName(packageName)) {
        log.error { "Refusing to run a shell command for strange package name '$packageName'" }
        return@withContext false
      }
      if (!isRootAvailable()) return@withContext false
      mutex.withLock {
        val user = Process.myUid() / 100000
        val cmd = "pm uninstall --user $user $packageName"
        try {
          val result = runSu(cmd, null, INSTALL_TIMEOUT_SECONDS)
          log.info { "'$cmd' -> exit ${result.exitCode}: ${result.output.trim()}" }
          result.exitCode == 0 && result.output.contains("Success")
        } catch (e: Exception) {
          log.error(e) { "Error running '$cmd': " }
          false
        }
      }
    }

  private fun parseFailure(result: ShellResult): String {
    val failure =
      result.output.lines().firstOrNull { it.contains("Failure") || it.contains("Exception") }
    return failure?.trim() ?: "pm install failed with exit code ${result.exitCode}"
  }

  /** Failures that will happen with every install method, so retrying is pointless. */
  private fun isPackageProblem(output: String): Boolean {
    return listOf(
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
        "INSTALL_FAILED_VERSION_DOWNGRADE",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE",
        "INSTALL_FAILED_OLDER_SDK",
        "INSTALL_FAILED_NO_MATCHING_ABIS",
        "INSTALL_PARSE_FAILED",
      )
      .any { output.contains(it) }
  }

  /**
   * Runs [command] in a root shell. If [stdinFile] is given, its bytes are written to the stdin of
   * the command.
   */
  private fun runSu(command: String, stdinFile: File?, timeoutSeconds: Long): ShellResult {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val outputBuilder = StringBuilder()
    // Read output on another thread, so a full pipe can't block us (or the process).
    val reader = Thread {
      try {
        process.inputStream.bufferedReader().use { r ->
          val buf = CharArray(4096)
          while (true) {
            val n = r.read(buf)
            if (n < 0) break
            synchronized(outputBuilder) { outputBuilder.append(buf, 0, n) }
          }
        }
      } catch (_: IOException) {}
    }
    reader.start()
    try {
      if (stdinFile != null) {
        try {
          process.outputStream.use { out -> stdinFile.inputStream().use { it.copyTo(out) } }
        } catch (e: IOException) {
          // pm may exit early (e.g. on parse errors); the real reason is in its output
          log.warn(e) { "Could not stream the whole file: " }
        }
      } else {
        process.outputStream.close()
      }
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IOException("Timeout after $timeoutSeconds seconds")
      }
      reader.join(2000)
      return ShellResult(process.exitValue(), synchronized(outputBuilder) { outputBuilder.toString() })
    } finally {
      process.destroy()
    }
  }
}

private val SAFE_PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")

/** Package names go into a root shell command, so only allow what a package name can contain. */
internal fun isSafePackageName(name: String): Boolean = SAFE_PACKAGE_NAME.matches(name)
