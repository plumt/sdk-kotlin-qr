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
import java.time.ZoneId

/**
 * 실행 전 확인 대화상자(기본 모양). 링크면 실제 접속할 호스트(퓨니코드 포함)와 경고를 보인다. 그 밖의 동작은 다룰 값
 * (번호·받는 사람·네트워크 이름 등, [ScannerDefaults.actionDetail])을 본문으로 보인다 — 비밀은 보이지 않는다.
 * 확인을 눌러도 스스로 실행하지 않는다 — [onConfirm] 에서 앱이 [QrActionExecutor.execute] 를 부른다.
 * [UrlWarning.NON_WEB_SCHEME] 링크는 기본 실행기가 열지 않으므로 확인 단추를 보이지 않는다(앱 링크를 열려면 [AppLinkPolicy] 를 받는 오버로드).
 */
@Composable
public fun QrActionConfirmation(action: QrAction, onConfirm: () -> Unit, onDismiss: () -> Unit, zone: ZoneId = ZoneId.systemDefault()) {
    QrActionConfirmation(action, AppLinkPolicy.NONE, onConfirm, onDismiss, zone)
}

/**
 * [appLinks] 가 허용한 앱 링크도 확인 단추를 보이는 확인 대화상자. 앱 링크면 「다른 앱 열기」 제목, 열릴 대상(`intent:` 는 대상 앱 패키지,
 * 그 밖은 스킴), 주소, 다른 앱이 바로 실행된다는 경고를 보인다. 실행기에도 같은 정책을 준다(`QrActionExecutor.default(options, appLinks)`).
 */
@Composable
public fun QrActionConfirmation(
    action: QrAction,
    appLinks: AppLinkPolicy,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val confirmLabel = stringResource(ScannerDefaults.actionLabelRes(action))
    val nonWeb = action is QrAction.OpenUrl && UrlWarning.NON_WEB_SCHEME in action.warnings
    val appLink = nonWeb && appLinks.allows((action as QrAction.OpenUrl).url)
    val blocked = nonWeb && !appLink
    val detail = ScannerDefaults.actionDetail(action, zone)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { if (!blocked) TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plumt_qr_confirm_cancel)) } },
        title = {
            Text(
                when {
                    appLink -> stringResource(R.string.plumt_qr_confirm_app_link_title)
                    action is QrAction.OpenUrl -> stringResource(R.string.plumt_qr_confirm_url_title)
                    else -> confirmLabel
                },
            )
        },
        text = if (appLink) {
            {
                Column {
                    AppLinkIntents.target((action as QrAction.OpenUrl).url)?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.plumt_qr_warning_app_link), color = MaterialTheme.colorScheme.error)
                }
            }
        } else if (action is QrAction.OpenUrl) {
            {
                Column {
                    Text(action.displayHost.ifEmpty { detail }, style = MaterialTheme.typography.titleMedium)
                    if (action.host.isNotEmpty() && action.host != action.displayHost) {
                        Text(stringResource(R.string.plumt_qr_confirm_real_host, action.host), style = MaterialTheme.typography.bodySmall)
                    }
                    action.warnings.forEach {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(ScannerDefaults.warningRes(it)), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else if (detail.isNotEmpty()) {
            { Text(detail) }
        } else {
            null
        },
    )
}

@Preview
@Composable
private fun QrActionConfirmationDialPreview() {
    MaterialTheme {
        QrActionConfirmation(QrAction.SendSms("010-0000-0000", "안녕하세요"), onConfirm = {}, onDismiss = {})
    }
}

@Preview
@Composable
private fun QrActionConfirmationPreview() {
    MaterialTheme {
        QrActionConfirmation(QrActions.openUrl("http://xn--80ak6aa92e.example/login"), onConfirm = {}, onDismiss = {})
    }
}

@Preview
@Composable
private fun QrActionConfirmationAppLinkPreview() {
    MaterialTheme {
        QrActionConfirmation(
            QrActions.openUrl("intent://pay?id=1#Intent;scheme=examplepay;package=com.example.pay;end"),
            AppLinkPolicy.allow(setOf("examplepay"), allowIntentUri = true),
            onConfirm = {},
            onDismiss = {},
        )
    }
}
