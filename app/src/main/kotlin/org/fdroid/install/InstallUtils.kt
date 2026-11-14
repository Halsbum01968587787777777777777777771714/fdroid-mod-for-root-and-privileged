package org.fdroid.install

import android.content.Context
import android.content.pm.PackageManager

/**
 * True if [packageName] was installed or updated at or after [sinceMs]. Used by installers that
 * can't report their result back (microG, external installer apps).
 */
internal fun Context.isInstalledSince(packageName: String, sinceMs: Long): Boolean =
  try {
    val info = packageManager.getPackageInfo(packageName, 0)
    // allow a little clock tolerance
    maxOf(info.firstInstallTime, info.lastUpdateTime) >= sinceMs - 2000
  } catch (_: PackageManager.NameNotFoundException) {
    false
  }
