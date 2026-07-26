package com.MeshLink.android.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.MeshLink.android.ui.theme.Space
import com.MeshLink.android.ui.theme.ThoughtOrb
import com.MeshLink.android.ui.theme.OrbState
import com.MeshLink.android.ui.theme.hairline
import com.MeshLink.android.util.AppConstants

/**
 * First-run gate: the user must choose a display name before reaching the app.
 *
 * Mandatory rather than skippable because on this mesh a person's identity is their nickname next to
 * a key fingerprint. A timeline of "anon4271" and "anon5787" is unreadable when it matters, and it
 * makes an impostor easy to miss — a real name is what gives the verification tick something
 * meaningful to attach to.
 *
 * [validate] returns an error message or null, so the rules live with the view model rather than
 * being duplicated in the UI.
 */
@Composable
fun NicknameSetupScreen(
    onConfirm: (String) -> Unit,
    validate: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    var name by remember { mutableStateOf("") }
    // Errors appear only after a submit attempt, so the field isn't scolding you mid-typing.
    var error by remember { mutableStateOf<String?>(null) }

    val submit: () -> Unit = {
        val problem = validate(name)
        error = problem
        if (problem == null) onConfirm(name.trim())
    }

    Box(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ThoughtOrb(
                state = OrbState.Searching,
                modifier = Modifier.size(120.dp),
            )

            Spacer(Modifier.height(Space.l))

            Text(
                "AKASHA",
                color = scheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 4.sp,
            )

            Spacer(Modifier.height(Space.s))

            Text(
                "Pick a name people will recognise. It's shown next to every message you send on the mesh.",
                color = scheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(Space.l))

            OutlinedTextField(
                value = name,
                onValueChange = {
                    // Hard cap in the field itself so the limit is felt, not just reported.
                    if (it.length <= AppConstants.UI.MAX_NICKNAME_LENGTH) {
                        name = it
                        error = null
                    }
                },
                placeholder = {
                    Text(
                        "your name",
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                    )
                },
                isError = error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = scheme.primary,
                    unfocusedBorderColor = scheme.surfaceVariant,
                    errorBorderColor = scheme.primary,
                    focusedTextColor = scheme.onSurface,
                    unfocusedTextColor = scheme.onSurface,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .hairline(RoundedCornerShape(12.dp)),
            )

            Spacer(Modifier.height(Space.s))

            // Reserve the row whether or not there's an error, so the button doesn't jump.
            Box(Modifier.fillMaxWidth().height(18.dp)) {
                if (error != null) {
                    Text(
                        error!!,
                        color = scheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                } else {
                    Text(
                        "${name.trim().length}/${AppConstants.UI.MAX_NICKNAME_LENGTH}",
                        color = scheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            Spacer(Modifier.height(Space.m))

            Button(
                onClick = submit,
                enabled = name.trim().isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    disabledContainerColor = scheme.surfaceVariant,
                    disabledContentColor = scheme.onSurfaceVariant,
                ),
                // No bounceClick here: it installs its own clickable, which would double-fire
                // alongside Button's onClick. Button already provides press feedback.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    "JOIN AKASHA",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp,
                )
            }

            Spacer(Modifier.height(Space.m))

            Text(
                "No account, no phone number, no internet. This name stays on your device and is only broadcast to devices near you.",
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
