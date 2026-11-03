package net.plumt.qr.internal

import net.plumt.qr.OtpAlgorithm
import net.plumt.qr.OtpType
import net.plumt.qr.QrPayload
import net.plumt.qr.WifiSecurity

/**
 * 문자열 → [QrPayload]. 던지지 않는다.
 *
 * 구조로 읽은 값은 마지막에 [QrPayload.encode] 로 한 번 써 본다. 쓸 수 없는 값(빈 SSID, 범위 밖 좌표 등)은 원문 [QrPayload.Text] 로
 * 돌려, "parse 가 준 구조화된 값은 언제나 다시 쓸 수 있다" 를 지킨다.
 */
internal object PayloadParser {

    fun parse(text: String): QrPayload {
        val structured = try {
            dispatch(text.trim())
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // 어떤 입력에도 던지지 않는 것이 계약이다. 깨진 입력은 원문 텍스트로 돌린다
            null
        } ?: return QrPayload.Text(text)
        return try {
            structured.encode()
            structured
        } catch (e: IllegalArgumentException) {
            QrPayload.Text(text)
        }
    }

    private fun dispatch(t: String): QrPayload? {
        fun has(prefix: String) = t.startsWith(prefix, ignoreCase = true)
        return when {
            has("http://") || has("https://") -> QrPayload.Url(t)
            has("WIFI:") -> parseWifi(t)
            has("BEGIN:VCARD") -> ContactCodec.parseVCard(t)
            has("MECARD:") -> ContactCodec.parseMeCard(t)
            has("BEGIN:VEVENT") || has("BEGIN:VCALENDAR") -> EventCodec.parse(t)
            has("mailto:") -> parseMailto(t.substring(7))
            has("MATMSG:") -> parseMatmsg(t.substring(7))
            has("SMTP:") -> parseSmtp(t.substring(5))
            has("tel:") -> QrPayload.Phone(t.substring(4).trim())
            has("smsto:") || has("mmsto:") -> parseSms(t.substring(6))
            has("sms:") -> parseSms(t.substring(4))
            has("geo:") -> parseGeo(t.substring(4))
            has("otpauth://") -> parseOtp(t.substring(10))
            else -> null
        }
    }

    private fun parseWifi(content: String): QrPayload.Wifi? {
        val fields = TextCodec.prefixedFields(content.substring(5))
            .distinctBy { it.first }
            .associate { (key, raw) -> key to wifiValue(raw) }
        val ssid = fields["S"] ?: return null
        val password = fields["P"].orEmpty()
        // T 를 빠뜨린 생성기가 흔하다. 비밀번호가 있는데 개방망으로 읽으면 연결이 실패하므로 WPA 로 본다
        val security = if (fields["T"].isNullOrBlank() && password.isNotEmpty()) WifiSecurity.WPA else WifiSecurity.fromToken(fields["T"])
        val eap = security == WifiSecurity.WPA2_EAP
        return QrPayload.Wifi(
            ssid = ssid,
            password = if (security == WifiSecurity.NONE) "" else password,
            security = security,
            hidden = fields["H"]?.trim()?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: false,
            eapMethod = if (eap) fields["E"].orEmpty() else "",
            identity = if (eap) fields["I"].orEmpty() else "",
            anonymousIdentity = if (eap) fields["A"].orEmpty() else "",
            phase2Method = if (eap) fields["PH2"].orEmpty() else "",
        )
    }

    /** 일부 생성기는 값을 큰따옴표로 감싼다(`S:"My Net";`). 이스케이프되지 않은 감싸기만 벗긴다. */
    private fun wifiValue(raw: String): String {
        val quoted = raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') && !raw.endsWith("\\\"")
        return TextCodec.unescapeBackslash(if (quoted) raw.substring(1, raw.length - 1) else raw)
    }

    private fun parseMailto(rest: String): QrPayload.Email {
        val address = TextCodec.percentDecode(rest.substringBefore('?'), plusAsSpace = false).trim()
        val params = TextCodec.queryParameters(rest.substringAfter('?', ""))
        return QrPayload.Email(address.ifEmpty { params["to"].orEmpty().trim() }, params["subject"].orEmpty(), params["body"].orEmpty())
    }

    /** `MATMSG:TO:주소;SUB:제목;BODY:본문;;` */
    private fun parseMatmsg(rest: String): QrPayload.Email {
        val fields = TextCodec.prefixedFields(rest).distinctBy { it.first }.associate { it.first to TextCodec.unescapeBackslash(it.second) }
        return QrPayload.Email(fields["TO"].orEmpty().trim(), fields["SUB"].orEmpty(), fields["BODY"].orEmpty())
    }

    /** `SMTP:주소:제목:본문` */
    private fun parseSmtp(rest: String): QrPayload.Email {
        val parts = rest.split(':', limit = 3)
        return QrPayload.Email(parts[0].trim(), parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }

    /** `smsto:<번호>:<본문>` 또는 `sms:<번호>?body=<본문>` */
    private fun parseSms(rest: String): QrPayload.Sms {
        // 번호 뒤의 첫 구분자가 형식을 정한다 — 본문에 든 ? 나 : 를 구분자로 잘못 읽지 않게
        val end = rest.indexOfFirst { it == ':' || it == '?' }
        if (end < 0) return QrPayload.Sms(rest.trim())
        val number = rest.substring(0, end).trim()
        return if (rest[end] == ':') {
            QrPayload.Sms(number, rest.substring(end + 1))
        } else {
            QrPayload.Sms(number, TextCodec.queryParameters(rest.substring(end + 1))["body"].orEmpty())
        }
    }

    /** `geo:<위도>,<경도>[,<고도>][;매개변수][?q=..]` */
    private fun parseGeo(rest: String): QrPayload.Geo? {
        val coords = rest.substringBefore('?').substringBefore(';').split(',').map(String::trim)
        if (coords.size !in 2..3) return null
        val lat = coords[0].toDoubleOrNull() ?: return null
        val lon = coords[1].toDoubleOrNull() ?: return null
        val alt = if (coords.size == 3) coords[2].toDoubleOrNull() ?: return null else null
        val query = TextCodec.queryParameters(rest.substringAfter('?', ""))["q"].orEmpty()
        return QrPayload.Geo(lat, lon, alt, query)
    }

    /**
     * `otpauth://<totp|hotp>/<발급자>:<계정>?secret=..&issuer=..`. 발급자는 `issuer` 매개변수가 라벨 접두사보다 앞선다.
     * 라벨의 구분자는 `:` 또는 `%3A` 다.
     */
    private fun parseOtp(rest: String): QrPayload.Otp? {
        val type = when (rest.substringBefore('/').lowercase()) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> return null
        }
        val label = TextCodec.percentDecode(rest.substringAfter('/', "").substringBefore('?'), plusAsSpace = false)
        val params = TextCodec.queryParameters(rest.substringAfter('?', ""))
        val labelIssuer = if (':' in label) label.substringBefore(':').trim() else ""
        val account = label.substringAfter(':').trim()
        val algorithm = params["algorithm"]?.let { name ->
            OtpAlgorithm.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: return null
        } ?: OtpAlgorithm.SHA1
        return QrPayload.Otp(
            account = account,
            secret = params["secret"]?.trim() ?: return null,
            issuer = params["issuer"]?.trim()?.takeIf(String::isNotEmpty) ?: labelIssuer,
            type = type,
            algorithm = algorithm,
            digits = params["digits"]?.let { it.trim().toIntOrNull() ?: return null } ?: 6,
            period = if (type == OtpType.TOTP) params["period"]?.let { it.trim().toIntOrNull() ?: return null } ?: 30 else 30,
            counter = if (type == OtpType.HOTP) params["counter"]?.trim()?.toLongOrNull() ?: return null else 0,
        )
    }
}
