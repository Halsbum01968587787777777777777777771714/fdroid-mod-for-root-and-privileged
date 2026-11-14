/*
 * Based on MicroGInstallerActivity of Aurora Store 4.8.4, Copyright (C) 2021 Rahul Kumar Patel,
 * licensed under the GNU General Public License, version 2 or later.
 */
package org.fdroid.install

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import mu.KotlinLogging

/**
 * Invisible activity that sends the install request to microG Companion and reports the result
 * back to [MicroGInstaller]. An activity is needed, because microG's installer is started with
 * startActivityForResult.
 */
class MicroGInstallActivity : Activity() {

  private val log = KotlinLogging.logger {}

  companion object {
    private const val EXTRA_URIS = "uris"
    private const val REQUEST_CODE = 1001

    fun start(context: Context, uris: ArrayList<Uri>) {
      val intent =
        Intent(context, MicroGInstallActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          putParcelableArrayListExtra(EXTRA_URIS, uris)
        }
      context.startActivity(intent)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // recreated (e.g. rotation) while microG's activity is already showing, wait for its result
    if (savedInstanceState != null) return
    MicroGInstaller.started?.complete(Unit)

    val uris: ArrayList<Uri>? =
      IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java)
    if (uris.isNullOrEmpty()) {
      log.error { "No files provided, can't install via microG" }
      finishWithResult(RESULT_CANCELED)
      return
    }
    try {
      @Suppress("DEPRECATION")
      startActivityForResult(MicroGInstaller.buildMicroGInstallIntent(uris), REQUEST_CODE)
    } catch (e: ActivityNotFoundException) {
      log.error(e) { "microG installer not found: " }
      finishWithResult(RESULT_CANCELED)
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_CODE) finishWithResult(resultCode)
  }

  private fun finishWithResult(resultCode: Int) {
    MicroGInstaller.result?.complete(resultCode)
    finish()
  }
}
