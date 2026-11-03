package net.plumt.qr.scanner.action

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.plumt.qr.scanner.ScannerDefaults
import net.plumt.qr.scanner.R

/**
 * 실행 전 확인 대화상자(기본 모양). 링크면 실제 접속할 호스트(퓨니코드 포함)와 경고를 보인다.
 * 확인을 눌러도 스스로 실행하지 않는다 — [onConfirm] 에서 앱이 [QrActionExecutor.execute] 를 부른다.
 */
@Composable
public fun QrActionConfirmation(action: QrAction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val confirmLabel = stringResource(ScannerDefaults.actionLabelRes(action))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plumt_qr_confirm_cancel)) } },
        title = {
            Text(if (action is QrAction.OpenUrl) stringResource(R.string.plumt_qr_confirm_url_title) else confirmLabel)
        },
        text = if (action is QrAction.OpenUrl) {
            {
                Column {
                    Text(action.displayHost.ifEmpty { action.url }, style = MaterialTheme.typography.titleMedium)
                    if (action.host.isNotEmpty() && action.host != action.displayHost) {
                        Text(stringResource(R.string.plumt_qr_confirm_real_host, action.host), style = MaterialTheme.typography.bodySmall)
                    }
                    action.warnings.forEach {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(ScannerDefaults.warningRes(it)), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else {
            null
        },
    )
}

@Preview
@Composable
private fun QrActionConfirmationPreview() {
    MaterialTheme {
        QrActionConfirmation(QrActions.openUrl("http://xn--80ak6aa92e.example/login"), onConfirm = {}, onDismiss = {})
    }
}
