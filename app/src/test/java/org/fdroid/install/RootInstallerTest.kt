package org.fdroid.install

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RootInstallerTest {

  @Test
  fun normalPackageNamesAreSafeForShellCommands() {
    listOf("org.fdroid.fdroid", "io.github.muntashirakon.AppManager", "com.a_b.c1", "single")
      .forEach { assertTrue(isSafePackageName(it), "'$it' should be safe") }
  }

  @Test
  fun anythingThatCouldInjectShellCommandsIsRejected() {
    listOf(
        "",
        "a b",
        "a;rm -rf /",
        "a.b && reboot",
        "a.b\nreboot",
        "a.\$(id)",
        "a.b`id`",
        "a.b'c",
        "a..b",
        ".a",
        "a.",
        "a/b",
      )
      .forEach { assertFalse(isSafePackageName(it), "'$it' must be rejected") }
  }
}
