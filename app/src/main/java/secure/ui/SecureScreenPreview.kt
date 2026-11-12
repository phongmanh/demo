package secure.ui

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import secure.keystore.SecureProvider.KeyLevel
import secure.SecureResult
import secure.SecureUiState
import secure.SecureUiStatus

private class SecureUiStatePreviewProvider : PreviewParameterProvider<SecureUiState> {
    override val values = sequenceOf(
        SecureUiState(),
        SecureUiState(
            status = SecureUiStatus.Success,
            result = SecureResult(
                iv = "q2Zx3n8JvP0rTk1a",
                cipherText = "Z3Vlc3Mgd2hhdCB0aGlzIGlzIGp1c3QgYSBwcmV2aWV3IGNpcGhlcnRleHQ=",
                isBiometric = false
            ),
            isMatched = true,
            secureLevel = KeyLevel.STRONGBOX
        ),
        SecureUiState(
            status = SecureUiStatus.Failure("Key permanently invalidated"),
            result = SecureResult(iv = "q2Zx3n8JvP0rTk1a", cipherText = "AAAA", isBiometric = true)
        )
    )
}

@Preview(showBackground = true, heightDp = 900)
@Composable
fun SecureContentPreview(
    @PreviewParameter(SecureUiStatePreviewProvider::class) uiState: SecureUiState,
) {
    MaterialTheme {
        SecureContent(
            uiState = uiState,
            onAction = {},
            inputFieldState = rememberTextFieldState("hello")
        )
    }
}
