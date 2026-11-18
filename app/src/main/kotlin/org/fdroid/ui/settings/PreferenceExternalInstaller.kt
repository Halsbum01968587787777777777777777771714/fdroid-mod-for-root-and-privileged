package org.fdroid.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import me.zhanghai.compose.preference.textFieldPreference
import org.fdroid.R
import org.fdroid.settings.SettingsConstants.PREF_DEFAULT_EXTERNAL_INSTALLER
import org.fdroid.settings.SettingsConstants.PREF_KEY_EXTERNAL_INSTALLER

/** Text field for the package name of the installer app used by "Other installer app". */
fun LazyListScope.preferenceExternalInstaller(
  state: MutableState<String>,
  showError: MutableState<Boolean>,
) {
  textFieldPreference(
    key = PREF_KEY_EXTERNAL_INSTALLER,
    defaultValue = PREF_DEFAULT_EXTERNAL_INSTALLER,
    rememberState = { state },
    icon = {
      Icon(
        imageVector = Icons.Default.Apps,
        contentDescription = null,
        modifier = Modifier.semantics { hideFromAccessibility() },
      )
    },
    title = { Text(stringResource(R.string.pref_external_installer_title)) },
    summary = {
      val value = state.value
      Text(value.ifBlank { stringResource(R.string.pref_external_installer_empty) })
    },
    textToValue = {
      val trimmed = it.trim()
      if (isPackageNameValid(trimmed)) {
        showError.value = false
        trimmed
      } else {
        showError.value = true
        // null is treated as an error and won't cause an update
        null
      }
    },
    textField = { value, onValueChange, onOk ->
      OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        keyboardActions = KeyboardActions { onOk() },
        singleLine = true,
        trailingIcon = {
          if (value.text.isNotBlank()) {
            IconButton(onClick = { onValueChange(TextFieldValue("")) }) {
              Icon(Icons.Default.Clear, stringResource(R.string.clear))
            }
          }
        },
        isError = showError.value,
        supportingText = {
          Text(
            stringResource(
              if (showError.value) R.string.pref_external_installer_error
              else R.string.pref_external_installer_hint
            )
          )
        },
      )
    },
  )
}

private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

/** Blank is allowed (means "not set"), otherwise it has to look like an Android package name. */
internal fun isPackageNameValid(name: String): Boolean =
  name.isBlank() || PACKAGE_NAME_REGEX.matches(name)
