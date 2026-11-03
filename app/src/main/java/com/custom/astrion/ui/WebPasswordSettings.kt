package com.custom.astrion.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.config.WebPassword

/**
 * Settings › Web configuration › the web page's password (1.4.6): set, change or remove it. Once
 * set, the browser asks for it on every page of the configurator, and tools send it.
 */
@Composable
internal fun WebPasswordRow() {
    val context = LocalContext.current
    val theme = LocalTheme.current
    val focus = LocalFocusManager.current
    var isSet by remember { mutableStateOf(WebPassword.isSet(context)) }
    var text by remember { mutableStateOf("") }
    val tooShort = stringResource(R.string.web_password_too_short, WebPassword.MIN_LENGTH)
    val saved = stringResource(R.string.web_password_saved)
    val removed = stringResource(R.string.web_password_removed)
    val save = {
        if (WebPassword.set(context, text)) {
            isSet = true
            text = ""
            focus.clearFocus()
            Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, tooShort, Toast.LENGTH_LONG).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (isSet) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = null,
                tint = if (isSet) theme.success else theme.amber
            )
            Text(
                stringResource(if (isSet) R.string.web_password_on else R.string.web_password_off),
                color = theme.primaryText,
                fontSize = 14.sp
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_LENGTH) },
            singleLine = true,
            label = { Text(stringResource(if (isSet) R.string.web_password_change else R.string.web_password_new)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = theme.primaryText,
                unfocusedTextColor = theme.primaryText,
                focusedBorderColor = theme.accent,
                unfocusedBorderColor = theme.mutedText,
                cursorColor = theme.accent,
                focusedLabelColor = theme.accent,
                unfocusedLabelColor = theme.mutedText
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (text.isNotEmpty()) PasswordButton(stringResource(R.string.save_short), theme.accent, theme.background) { save() }
            if (isSet) {
                PasswordButton(stringResource(R.string.web_password_remove), theme.controlBackground, theme.primaryText) {
                    WebPassword.clear(context)
                    isSet = false
                    Toast.makeText(context, removed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        Text(stringResource(R.string.web_password_hint), color = theme.mutedText, fontSize = 11.sp)
    }
}

@Composable
private fun PasswordButton(label: String, background: Color, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 14.sp,
        modifier =
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .tapClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

private const val MAX_LENGTH = 64
