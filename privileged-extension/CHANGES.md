# Changes compared to F-Droid Privileged Extension 0.2.13

This module contains the source of the F-Droid Privileged Extension 0.2.13
(https://gitlab.com/fdroid/privileged-extension, Apache License 2.0, see `LICENSE`), modified as follows.

## Build
- Built as a module of this project with the same Gradle/Android Gradle Plugin as the app
  (the original build files from 2016 can't be used with current tools).
- `minSdk` 24 (was 8), `targetSdk` stays at 25 on purpose, see `build.gradle.kts`.
- Signed with the key from `keystore.properties`.

## Trust
- `ClientWhitelist.java`: besides the official F-Droid certificate, the SHA-256 of the certificate in
  `keystore.properties` is trusted for the package `org.fdroid.fdroid`. This is what allows a self-built,
  self-signed F-Droid to use the extension. It is computed at build time (`BuildConfig.CLIENT_CERT_SHA256`).

## Stability (`PrivilegedService.java`)
- The callback is stored **before** the install is committed. Before, a fast result could arrive while the
  callback was still `null`.
- Every failure while staging/committing/uninstalling is reported to the client with an error code.
  Before, an exception (e.g. `openInputStream` returning `null`) crashed the service and the client never
  got an answer.
- A missing status extra is treated as a failure. The old default (`PackageInstaller.STATUS_FAILURE` = 1)
  is the legacy code for **success**.
- Toasts are shown on the main thread (binder threads have no Looper, `Toast` threw there).
- Broadcasts of the install/uninstall result are restricted to this package (`setPackage`).
- Removed the code for Android older than 7.0 and the stubs for hidden classes
  (`IPackageInstallObserver`, `IPackageDeleteObserver`) that it needed.

## AIDL
- `IPrivilegedService.aidl`: the parcelable types `Uri` and `PackageInfo` are imported explicitly and the redundant
  `in` on primitive parameters was removed. The interface on the wire is unchanged.
