package org.fdroid.ui.settings

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PreferenceExternalInstallerTest {

  @Test
  fun validPackageNames() {
    listOf(
        "io.github.muntashirakon.AppManager",
        "org.fdroid.fdroid",
        "com.example.app_2",
        "a.b",
        "", // empty means "not set", which is handled when installing
        "   ",
      )
      .forEach { assertTrue(isPackageNameValid(it.trim()), "'$it' should be valid") }
  }

  @Test
  fun invalidPackageNames() {
    listOf(
        "nodots",
        "1abc.def",
        "a..b",
        ".a.b",
        "a.b.",
        "has space.app",
        "a.b-c",
        "a.b/c",
        "a.b;rm",
        "ÄÖÜ.test",
      )
      .forEach { assertFalse(isPackageNameValid(it), "'$it' should be invalid") }
  }
}
