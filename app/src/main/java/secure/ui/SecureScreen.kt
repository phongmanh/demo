package secure.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import secure.biometric.BiometricAuthenticator
import secure.SecureAction
import secure.SecureAction.*
import secure.keystore.SecureProvider.KeyLevel
import secure.SecureUiState
import secure.SecureUiStatus
import secure.SecureViewModel

@Composable
fun SecureScreen(viewModel: SecureViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = LocalActivity.current as? FragmentActivity

    LaunchedEffect(viewModel) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is SecureEvent.Failed -> launch { snackbarHostState.showSnackbar(event.error) }

                is SecureEvent.RequestBiometric -> if (activity == null) {
                    viewModel.onAction(BiometricFailed("No activity to show prompt"))
                } else {
                    BiometricAuthenticator.authenticate(
                        activity = activity,
                        cipher = event.cipher,
                        onSuccess = { viewModel.onAction(BiometricSucceeded(it)) },
                        onError = { viewModel.onAction(BiometricFailed(it)) })
                }
            }
        }
    }

    SecureContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onAction = viewModel::onAction,
        modifier = modifier
    )
}

@Composable
internal fun SecureContent(
    uiState: SecureUiState,
    onAction: (SecureAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    inputFieldState: TextFieldState = rememberTextFieldState(),
) {
    // Only flips when the text goes blank <-> non-blank, not on every keystroke.
    val hasInput by remember(inputFieldState) {
        derivedStateOf { inputFieldState.text.isNotBlank() }
    }
    val isBusy = uiState.isBusy
    val result = uiState.result
    val inputBytes = { inputFieldState.text.toString().toByteArray() }

    LaunchedEffect(inputFieldState.text) {
        snapshotFlow { inputFieldState.text }.collect { text -> if (text.isBlank()) onAction(Reset) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InputField(inputFieldState, readOnly = isBusy, modifier = Modifier.fillMaxWidth())

            KeyCard(
                spec = KeystoreKeySpec,
                isActive = result.isValid && !result.isBiometric,
                actions = KeyActions(
                    encryptEnabled = hasInput && !isBusy,
                    decryptEnabled = result.isValid && !result.isBiometric && !isBusy,
                    onEncrypt = { onAction(Encrypt(inputBytes())) },
                    onDecrypt = { onAction(Decrypt) }),
                trailing = {
                    SecurityLevelChip(
                        level = uiState.secureLevel,
                        enabled = !isBusy,
                        onClick = { onAction(GetSecretKey) })
                })

            KeyCard(
                spec = BiometricKeySpec,
                isActive = result.isValid && result.isBiometric,
                actions = KeyActions(
                    encryptEnabled = hasInput && !isBusy,
                    decryptEnabled = result.isValid && result.isBiometric && !isBusy,
                    onEncrypt = { onAction(SecureAction.BiometricEncrypt(inputBytes())) },
                    onDecrypt = { onAction(SecureAction.BiometricDecrypt) }))

            ResultCard(uiState = uiState, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InputField(state: TextFieldState, readOnly: Boolean, modifier: Modifier = Modifier) {
    // readOnly instead of enabled: disabling a focused field drops focus and hides the IME,
    // which triggers extra animation frames on every Encrypt.
    OutlinedTextField(
        state = state,
        modifier = modifier,
        readOnly = readOnly,
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
        label = { Text("Plaintext") },
        placeholder = { Text("Type something to encrypt") },
        trailingIcon = {
            IconButton(onClick = { state.clearText() }, enabled = !readOnly) {
                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear input")
            }
        })
}

/** Static description of a key, shown in its card header. */
@Immutable
private data class KeySpec(val icon: ImageVector, val title: String, val subtitle: String)

private val KeystoreKeySpec = KeySpec(
    icon = Icons.Default.Lock, title = "Keystore key", subtitle = "AES-256-GCM · StrongBox or TEE"
)

private val BiometricKeySpec = KeySpec(
    icon = Icons.Default.Face,
    title = "Biometric key",
    subtitle = "Needs fingerprint or face unlock for every use"
)

private class KeyActions(
    val encryptEnabled: Boolean,
    val decryptEnabled: Boolean,
    val onEncrypt: () -> Unit,
    val onDecrypt: () -> Unit,
)

@Composable
private fun KeyCard(
    spec: KeySpec,
    isActive: Boolean,
    actions: KeyActions,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    // The card holding the current ciphertext is outlined, so it's obvious which Decrypt applies.
    val border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(modifier = modifier.fillMaxWidth(), border = border) {
        Column(
            modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = spec.icon, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(spec.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        spec.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                trailing()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = actions.onEncrypt,
                    enabled = actions.encryptEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Encrypt")
                }
                OutlinedButton(
                    onClick = actions.onDecrypt,
                    enabled = actions.decryptEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Decrypt")
                }
            }
        }
    }
}

/** Shows the key's security level and doubles as the button that checks it. */
@Composable
private fun SecurityLevelChip(level: KeyLevel, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (label, container) = when (level) {
        KeyLevel.STRONGBOX -> "StrongBox" to scheme.primaryContainer
        KeyLevel.TEE -> "TEE" to scheme.tertiaryContainer
        KeyLevel.SECURE_UNKNOWN -> "Secure HW" to scheme.tertiaryContainer
        KeyLevel.SOFTWARE -> "Software" to scheme.errorContainer
        KeyLevel.UNKNOWN -> "Check level" to Color.Transparent
    }
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(containerColor = container)
    )
}

@Composable
private fun ResultCard(uiState: SecureUiState, modifier: Modifier = Modifier) {
    val status = uiState.status
    val result = uiState.result
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Result",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(status)
            }

            if (status is SecureUiStatus.Failure) {
                Text(
                    status.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Same 4dp slot either way, so the card does not jump while busy.
            if (status == SecureUiStatus.Processing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                HorizontalDivider(modifier = Modifier.padding(vertical = 1.5.dp))
            }

            if (!result.isValid) {
                Text(
                    "Nothing encrypted yet. Enter text and pick a key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                InfoRow(label = "Key", value = if (result.isBiometric) "Biometric" else "Keystore")
                val (icon, text, tint) = verification(uiState.isMatched)
                InfoRow(label = "Check", value = text, icon = icon, tint = tint)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ValueBlock(label = "IV", value = result.iv.orEmpty(), collapsedLines = 1)
                        ValueBlock(
                            label = "Ciphertext",
                            value = result.cipherText.orEmpty(),
                            collapsedLines = 3
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: SecureUiStatus) {
    val scheme = MaterialTheme.colorScheme
    val (label, container, content) = when (status) {
        SecureUiStatus.Idle -> Triple("Ready", scheme.surfaceVariant, scheme.onSurfaceVariant)
        SecureUiStatus.Processing -> Triple(
            "Working…", scheme.secondaryContainer, scheme.onSecondaryContainer
        )

        SecureUiStatus.Success -> Triple("Done", scheme.primaryContainer, scheme.onPrimaryContainer)
        is SecureUiStatus.Failure -> Triple(
            "Failed", scheme.errorContainer, scheme.onErrorContainer
        )
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun verification(isMatched: Boolean?): Triple<ImageVector, String, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (isMatched) {
        true -> Triple(Icons.Default.CheckCircle, "Decrypted, matches input", scheme.primary)
        false -> Triple(Icons.Default.Warning, "Decrypted, does not match", scheme.error)
        null -> Triple(Icons.Default.Info, "Not decrypted yet", scheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    icon: ImageVector? = null,
    tint: Color = Color.Unspecified,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_WIDTH)
        )
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** Monospace Base64 value; tap to expand when it doesn't fit in [collapsedLines]. */
@Composable
private fun ValueBlock(label: String, value: String, collapsedLines: Int) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "$label · ${value.length} chars",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val LABEL_WIDTH = 72.dp
