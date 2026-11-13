# F-Droid (master) mit Installationsmethoden: Standard / Privileged Extension / Root

Stand: gepatcht auf F-Droid `master` (2.0.3). **Nicht kompiliert** (in der Entstehungsumgebung gab es kein Android SDK).
Der Code wurde sorgfältig gegen die vorhandenen Klassen geprüft, aber ein erster Build kann trotzdem eine
Fehlermeldung liefern. Schick sie mir einfach, dann behebe ich sie.

## Was geändert wurde

| Bereich | Datei |
|---|---|
| Einstellung "Installationsmethode" | `settings/SettingsConstants.kt`, `settings/SettingsManager.kt`, `ui/settings/Settings.kt`, `ui/settings/SettingsViewModel.kt`, `res/values*/strings.xml` (EN + DE) |
| Privileged Extension (Backport aus 1.23) | `install/PrivilegedInstaller.kt`, `aidl/.../IPrivilegedService.aidl`, `IPrivilegedCallback.aidl`, `<queries>` im Manifest |
| Root | `install/RootInstaller.kt` |
| Umschaltung beim Installieren | `install/AppInstallManager.kt` |
| Migrations-Hinweis ausgeblendet, wenn Extension gewählt | `utils/Utils.kt` |
| Neues Modul: Extension selbst bauen, vertraut deinem Schlüssel | `privileged-extension/` |
| Signing mit eigenem Schlüssel | `app/build.gradle.kts`, `keystore.properties.example` |
| Magisk-Modul packen | `tools/make_magisk_module.py`, `tools/magisk-module-template/` |

Verhalten: Ist die gewählte Methode nicht nutzbar (Extension fehlt, Root verweigert), fällt F-Droid beim Installieren
auf den Standard-Installer zurück. In den Einstellungen wird die Methode beim Auswählen sofort getestet
(Root: `su -c id` löst den Magisk-Dialog aus; Extension: wird gesucht und per AIDL geprüft). Schlägt der Test fehl,
springt die Einstellung zurück auf "Standard" und ein Hinweis erscheint.

## Warum ein eigenes Extension-Modul nötig ist

Die Extension erlaubt nur den Client `org.fdroid.fdroid` mit dem **offiziellen F-Droid-Zertifikat**. Ein selbst
gebauter F-Droid ist anders signiert und würde abgelehnt. Deshalb ist die Extension (v0.2.13, unverändert bis auf die
Whitelist) als Modul `privileged-extension` im Projekt. Beim Bauen liest sie dein Zertifikat aus
`keystore.properties` und nimmt es in die Whitelist auf. Das offizielle Zertifikat bleibt ebenfalls erlaubt.

## Bauen unter Windows

1. **Android Studio** installieren (bringt JDK mit). Beim ersten Öffnen die **Android SDK Platform 37** installieren lassen.
2. Projektordner entpacken, in Android Studio **Open** wählen und den Sync abwarten.
3. **Eigenen Schlüssel erzeugen** (einmalig), in der Android-Studio-Konsole oder CMD im Projektordner:
   ```
   "C:\Program Files\Android\Android Studio\jbr\bin\keytool" -genkeypair -v -keystore my-fdroid.jks -alias fdroid -keyalg RSA -keysize 4096 -validity 36500
   ```
   Den Schlüssel gut sichern. Ohne ihn gibt es keine Updates.
4. `keystore.properties.example` nach `keystore.properties` kopieren und Passwörter eintragen.
5. Bauen:
   ```
   gradlew.bat :privileged-extension:assembleRelease :app:assembleFullDefaultRelease
   ```
   - F-Droid: `app\build\outputs\apk\fullDefault\release\`
   - Extension: `privileged-extension\build\outputs\apk\release\`
   
   Nimm die **full**-Variante (`org.fdroid.fdroid`). Die Basic-Variante (`org.fdroid.basic`) und Debug-Builds
   (`.debug`) werden von der Extension nicht akzeptiert.
6. Magisk-Modul packen (Python nötig):
   ```
   python tools\make_magisk_module.py
   ```
   Ergebnis: `fdroid-privileged-extension-own.zip`.

## Installieren

1. In Magisk **alte "F-Droid Privileged Extension"-Module deaktivieren/entfernen** (gleiche Paket-ID, würde kollidieren).
2. Das neue ZIP in Magisk (Module → Aus Speicher installieren) flashen und **neu starten**.
3. Offizielles F-Droid **deinstallieren** (gleiche Paket-ID, andere Signatur; deine installierten Apps bleiben) und die selbst
   gebaute, signierte F-Droid-APK installieren.
4. F-Droid → Einstellungen → **Installationsmethode** → "F-Droid Privileged Extension" oder "Root (Magisk)".

## Fehlersuche

- *"Privileged Extension ist nicht installiert"*: Modul nicht aktiv oder kein Neustart.
- *"hat keine Systemrechte oder vertraut der Signatur nicht"*: F-Droid wurde nicht mit demselben Schlüssel signiert
  wie die Extension, es ist die Basic-/Debug-Variante, oder die Rechte-Datei wurde nicht geladen.
- Logs: `adb logcat | findstr /i "PrivilegedService PrivilegedInstaller RootInstaller"`

## Bekannte Grenzen

- **MicroG** ist nicht enthalten, siehe Nachricht im Chat (es gibt dafür keine Installations-API).
- **Deinstallieren** läuft weiter über den normalen Systemdialog. Umgebaut wurde nur das Installieren.
- Root-Installation setzt F-Droid als Installer-Paket (`pm install -i`), dadurch klappen spätere Updates sauber.
