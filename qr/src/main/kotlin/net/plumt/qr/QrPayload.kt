package net.plumt.qr

import net.plumt.qr.internal.ContactCodec
import net.plumt.qr.internal.EventCodec
import net.plumt.qr.internal.PayloadParser
import net.plumt.qr.internal.Summaries
import net.plumt.qr.internal.TextCodec
import java.math.BigDecimal
import java.time.ZoneId

/**
 * 코드에 담는 내용의 구조화된 형태. 만들 때는 [encode] 로 문자열이 되고, 읽을 때는 [parse] 로 되돌아온다.
 *
 * 두 방향은 짝이다 — 어떤 값이든 `parse(payload.encode()) == payload` 가 성립한다(문서에 적은 손실 제외).
 * 반대로 [parse] 가 돌려준 구조화된 값은 언제나 [encode] 가 성공한다. 그렇게 쓸 수 없는 입력은 [Text] 로 돌려준다.
 *
 * ## 오류
 * - [parse] 는 던지지 않는다. 모르는 형식이나 깨진 입력은 원문 그대로의 [Text] 다
 * - [encode] 는 쓸 수 없는 값이면 [IllegalArgumentException] — 빈 SSID, 범위 밖 위도·경도, base32 가 아닌 OTP 비밀 등.
 *   메시지에 필드 이름과 받은 값(비밀값 제외)이 들어간다
 *
 * ```
 * val wifi = QrPayload.Wifi("Home", "pw123456")
 * val text = wifi.encode()                    // WIFI:T:WPA;S:Home;P:pw123456;;
 * val back = QrPayload.parse(text)            // == wifi
 * back.summary()                              // "Home" — 비밀번호는 요약에 넣지 않는다
 * ```
 */
public sealed interface QrPayload {

    /** 코드에 실제로 들어갈 문자열. 값이 쓸 수 없으면 [IllegalArgumentException]. */
    public fun encode(): String

    /**
     * 카드·목록에 보일 한 줄. 원문(`WIFI:...`, 여러 줄의 vCard) 대신 사람이 알아보는 값이다.
     *
     * Wi-Fi 비밀번호·OTP 비밀처럼 화면에 드러나면 안 되는 값은 넣지 않는다 — 구조로 읽지 못해 [Text] 가 된
     * `WIFI:`·`otpauth` 원문도 비밀 대신 표지(`Wi-Fi`·`OTP`)나 계정 이름만 보인다.
     * 일정 시각은 [zone] 의 현지 시각으로 보인다.
     */
    public fun summary(zone: ZoneId = ZoneId.systemDefault()): String

    /** 웹 주소. */
    public data class Url(val url: String) : QrPayload {
        /** 스킴이 없으면 `https://` 를 붙인다 — 카메라 앱은 스킴 없는 주소를 텍스트로 읽는다. */
        override fun encode(): String {
            val trimmed = url.trim()
            require(trimmed.isNotEmpty()) { "url 이 비어 있다" }
            require(trimmed.none(Char::isWhitespace)) { "url 에 공백이 있다: $trimmed" }
            return if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        }

        override fun summary(zone: ZoneId): String = url.trim()

        // `https://사용자:비밀번호@호스트` 의 사용자 정보를 가린다
        override fun toString(): String = "Url(url=${Summaries.maskUserInfo(url)})"

        private companion object {
            val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        }
    }

    /**
     * 구조가 없는 글. 모르는 형식과 깨진 입력도 원문 그대로 이것이 된다.
     * [toString] 은 비밀이 들 수 있는 원문(`WIFI:`·`otpauth` 로 시작)을 가린다.
     */
    public data class Text(val text: String) : QrPayload {
        override fun encode(): String = text

        override fun summary(zone: ZoneId): String = Summaries.text(text)

        // 깨진 `WIFI:`·`otpauth` 원문은 Text 로 남는데 비밀번호·OTP 비밀이 들 수 있다 — 로그에 새지 않게 가린다
        override fun toString(): String =
            if (Summaries.mayHoldSecret(text)) "Text(text=****, length=${text.length})" else "Text(text=$text)"
    }

    /**
     * Wi-Fi 접속 정보. 형식은 `WIFI:T:<보안>;S:<이름>;P:<비밀번호>;H:<숨김>;;` 이고 `\ ; , : "` 는 역슬래시로 이스케이프한다.
     *
     * 기업용(WPA2-EAP) 망은 [eapMethod]·[identity]·[anonymousIdentity]·[phase2Method] 를 더 쓴다(`E:`·`I:`·`A:`·`PH2:`).
     * 이 넷은 [security] 가 [WifiSecurity.WPA2_EAP] 일 때만 쓰고 읽는다. 개방망([WifiSecurity.NONE])의 비밀번호는 쓰지 않는다.
     *
     * [toString] 은 비밀번호를 가린다 — 로그에 그대로 찍혀도 새지 않게.
     *
     * @property eapMethod EAP 방식(예: `PEAP`·`TTLS`·`TLS`)
     * @property phase2Method 2단계 인증(예: `MSCHAPV2`·`PAP`)
     */
    public data class Wifi(
        val ssid: String,
        val password: String = "",
        val security: WifiSecurity = WifiSecurity.WPA,
        val hidden: Boolean = false,
        val eapMethod: String = "",
        val identity: String = "",
        val anonymousIdentity: String = "",
        val phase2Method: String = "",
    ) : QrPayload {
        override fun encode(): String {
            require(ssid.isNotEmpty()) { "ssid 가 비어 있다" }
            val specials = TextCodec.WIFI_SPECIALS
            return buildString {
                append("WIFI:")
                append("T:").append(security.token).append(';')
                append("S:").append(TextCodec.escapeBackslash(ssid, specials)).append(';')
                if (security != WifiSecurity.NONE && password.isNotEmpty()) {
                    append("P:").append(TextCodec.escapeBackslash(password, specials)).append(';')
                }
                if (hidden) append("H:true;")
                if (security == WifiSecurity.WPA2_EAP) {
                    listOf("E" to eapMethod, "A" to anonymousIdentity, "I" to identity, "PH2" to phase2Method)
                        .filter { it.second.isNotEmpty() }
                        .forEach { (key, value) -> append(key).append(':').append(TextCodec.escapeBackslash(value, specials)).append(';') }
                }
                append(';')
            }
        }

        override fun summary(zone: ZoneId): String = ssid.trim().ifEmpty { Summaries.WIFI_LABEL }

        override fun toString(): String =
            "Wifi(ssid=$ssid, password=${if (password.isEmpty()) "" else "****"}, security=$security, hidden=$hidden, " +
                "eapMethod=$eapMethod, identity=$identity, anonymousIdentity=$anonymousIdentity, phase2Method=$phase2Method)"
    }

    /**
     * 연락처. [encode] 는 vCard 3.0, [encodeMeCard] 는 MECARD 다.
     *
     * 읽기는 vCard 2.1(QUOTED-PRINTABLE 포함)·3.0·4.0 의 기본 필드와 MECARD 를 받는다.
     * 이름(`FN`)이 없으면 `N` 을 "이름 성" 순서로 이어 쓴다. 주소는 구성 요소를 `, ` 로 이은 한 줄이다.
     * 전화·이메일·주소 등이 여러 개면 전화·이메일은 모두, 나머지는 처음 것만 읽는다.
     *
     * 이름·소속·전화·이메일 중 하나는 있어야 쓸 수 있다.
     */
    public data class Contact(
        val name: String = "",
        val organization: String = "",
        val title: String = "",
        val phones: List<ContactPhone> = emptyList(),
        val emails: List<String> = emptyList(),
        val address: String = "",
        val url: String = "",
        val note: String = "",
    ) : QrPayload {
        /** vCard 3.0. 줄바꿈은 `\n` 이다(코드 용량을 아끼려고 CRLF 를 쓰지 않는다 — 읽는 쪽은 둘 다 받는다). */
        override fun encode(): String = ContactCodec.encodeVCard(this)

        /**
         * MECARD(`MECARD:N:..;TEL:..;;`). vCard 보다 짧아 같은 내용을 더 작은 코드에 담는다.
         * 직함과 전화 종류는 MECARD 에 자리가 없어 빠진다 — 다시 읽으면 [title] 은 비고 전화 종류는 [PhoneType.OTHER] 다.
         */
        public fun encodeMeCard(): String = ContactCodec.encodeMeCard(this)

        override fun summary(zone: ZoneId): String =
            listOf(name, organization, phones.firstOrNull()?.number.orEmpty(), emails.firstOrNull().orEmpty())
                .map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    }

    /** 이메일. `mailto:` 로 쓰고, `mailto:`·`MATMSG:`·`SMTP:` 를 읽는다. */
    public data class Email(val address: String, val subject: String = "", val body: String = "") : QrPayload {
        override fun encode(): String {
            val trimmed = address.trim()
            require(trimmed.isNotEmpty()) { "address 가 비어 있다" }
            require(trimmed.none { it.isWhitespace() || it == '?' || it == '&' }) { "address 에 공백·?·& 가 있다: $trimmed" }
            val query = listOfNotNull(
                subject.takeIf { it.isNotEmpty() }?.let { "subject=${TextCodec.percentEncode(it)}" },
                body.takeIf { it.isNotEmpty() }?.let { "body=${TextCodec.percentEncode(it)}" },
            )
            // 주소에 든 % 나 한글은 인코딩한다 — 읽을 때 주소도 퍼센트 디코딩하므로. 주소에 흔한 기호는 그대로 둔다
            return "mailto:" + TextCodec.percentEncode(trimmed, keep = "@!$'()*+,;=") + if (query.isEmpty()) "" else "?" + query.joinToString("&")
        }

        override fun summary(zone: ZoneId): String = address.trim()
    }

    /** 전화번호(`tel:`). */
    public data class Phone(val number: String) : QrPayload {
        override fun encode(): String {
            require(number.isNotBlank()) { "number 가 비어 있다" }
            return "tel:${number.trim()}"
        }

        override fun summary(zone: ZoneId): String = number.trim()
    }

    /** 문자 메시지. `smsto:<번호>:<본문>` 으로 쓰고, `smsto:`·`sms:`(`?body=` 포함)·`mmsto:` 를 읽는다. */
    public data class Sms(val number: String, val message: String = "") : QrPayload {
        override fun encode(): String {
            val trimmed = number.trim()
            require(trimmed.isNotEmpty()) { "number 가 비어 있다" }
            require(':' !in trimmed && '?' !in trimmed) { "number 에 : 나 ? 가 있다: $trimmed" }
            return if (message.isEmpty()) "smsto:$trimmed" else "smsto:$trimmed:$message"
        }

        override fun summary(zone: ZoneId): String = number.trim()
    }

    /**
     * 일정(iCalendar `VEVENT`). 시각은 [EventTime] 으로 받아 형식(종일·현지·시간대 지정·UTC)을 그대로 보존한다.
     *
     * 읽기는 `BEGIN:VEVENT` 와 `VEVENT` 를 품은 `BEGIN:VCALENDAR` 를 받는다. 시각을 해석할 수 없으면 그 시각은 null 이다.
     * `DURATION`·반복 규칙·알림은 읽지 않는다. 제목은 비어 있으면 안 되고, 같은 종류의 두 시각이면 끝이 시작보다 앞설 수 없다.
     */
    public data class Event(
        val title: String,
        val start: EventTime? = null,
        val end: EventTime? = null,
        val location: String = "",
        val description: String = "",
    ) : QrPayload {
        override fun encode(): String = EventCodec.encode(this)

        override fun summary(zone: ZoneId): String =
            listOf(title.trim(), start?.let { Summaries.eventTime(it, zone) }.orEmpty())
                .filter(String::isNotEmpty).joinToString(" · ")
    }

    /**
     * 위치(`geo:<위도>,<경도>[,<고도>][?q=<검색어>]`). 위도는 -90..90, 경도는 -180..180, 값은 유한해야 한다.
     *
     * @property altitude 고도(미터). 없으면 null
     * @property query 지도 앱에 넘길 검색어·장소 이름(`?q=`)
     */
    public data class Geo(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double? = null,
        val query: String = "",
    ) : QrPayload {
        override fun encode(): String {
            require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude($latitude) 는 -90..90 범위여야 한다" }
            require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude($longitude) 는 -180..180 범위여야 한다" }
            require(altitude == null || altitude.isFinite()) { "altitude($altitude) 는 유한한 수여야 한다" }
            return buildString {
                append("geo:").append(plain(latitude)).append(',').append(plain(longitude))
                if (altitude != null) append(',').append(plain(altitude))
                if (query.isNotEmpty()) append("?q=").append(TextCodec.percentEncode(query))
            }
        }

        override fun summary(zone: ZoneId): String = query.trim().ifEmpty { "$latitude, $longitude" }

        private fun plain(value: Double): String = BigDecimal.valueOf(value).toPlainString()
    }

    /**
     * 일회용 비밀번호 등록(`otpauth://totp|hotp/<발급자>:<계정>?secret=..`). 인증 앱이 읽는 형식이다.
     *
     * [secret] 은 base32(`A-Z`·`2-7`, 끝의 `=` 허용, 대소문자 무시)다. 계정·발급자에는 `:` 를 쓸 수 없다.
     * [digits] 는 6..8, [period] 는 1 이상, [counter] 는 0 이상이다. [period] 는 TOTP, [counter] 는 HOTP 에만 쓴다.
     * 기본값(SHA1·6자리·30초)은 문자열에 쓰지 않는다.
     *
     * [summary] 와 [toString] 은 비밀을 넣지 않는다.
     */
    public data class Otp(
        val account: String,
        val secret: String,
        val issuer: String = "",
        val type: OtpType = OtpType.TOTP,
        val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
        val digits: Int = 6,
        val period: Int = 30,
        val counter: Long = 0,
    ) : QrPayload {
        override fun encode(): String {
            require(account.isNotBlank()) { "account 가 비어 있다" }
            require(':' !in account) { "account 에 : 를 쓸 수 없다: $account" }
            require(':' !in issuer) { "issuer 에 : 를 쓸 수 없다: $issuer" }
            require(BASE32.matches(secret)) { "secret 은 base32(A-Z, 2-7) 여야 한다 (길이 ${secret.length})" }
            require(digits in 6..8) { "digits($digits) 는 6..8 범위여야 한다" }
            require(period >= 1) { "period($period) 는 1 이상이어야 한다" }
            require(counter >= 0) { "counter($counter) 는 0 이상이어야 한다" }
            val label = if (issuer.isBlank()) {
                TextCodec.percentEncode(account.trim())
            } else {
                TextCodec.percentEncode(issuer.trim()) + ":" + TextCodec.percentEncode(account.trim())
            }
            val params = buildList {
                add("secret=$secret")
                if (issuer.isNotBlank()) add("issuer=${TextCodec.percentEncode(issuer.trim())}")
                if (algorithm != OtpAlgorithm.SHA1) add("algorithm=${algorithm.name}")
                if (digits != 6) add("digits=$digits")
                if (type == OtpType.TOTP && period != 30) add("period=$period")
                if (type == OtpType.HOTP) add("counter=$counter")
            }
            return "otpauth://${type.name.lowercase()}/$label?" + params.joinToString("&")
        }

        override fun summary(zone: ZoneId): String =
            if (issuer.isBlank()) account.trim() else "${issuer.trim()}:${account.trim()}"

        override fun toString(): String =
            "Otp(account=$account, secret=****, issuer=$issuer, type=$type, algorithm=$algorithm, " +
                "digits=$digits, period=$period, counter=$counter)"

        private companion object {
            val BASE32 = Regex("^[A-Za-z2-7]+=*$")
        }
    }

    /**
     * 상품 바코드(EAN·UPC)의 상품 번호. 내용만으론 판별할 수 없어 `parse(text, format)` 에 상품 바코드 형식을
     * 넘길 때만 이것이 된다. 번호의 종류·유효성은 [BarcodeNumber] 로 본다.
     */
    public data class Product(val code: String) : QrPayload {
        override fun encode(): String {
            require(code.isNotBlank()) { "code 가 비어 있다" }
            return code.trim()
        }

        override fun summary(zone: ZoneId): String = code.trim()
    }

    public companion object {
        /**
         * 읽은 문자열을 구조로 푼다. 던지지 않는다 — 모르는 형식·깨진 입력은 원문 그대로의 [Text] 다.
         *
         * 스킴이 있는 형식(`http(s)://`·`WIFI:`·`BEGIN:VCARD`·`MECARD:`·`BEGIN:VEVENT`·`BEGIN:VCALENDAR`·`mailto:`·
         * `MATMSG:`·`SMTP:`·`tel:`·`smsto:`·`sms:`·`mmsto:`·`geo:`·`otpauth://`)만 구조로 푼다. 접두사는 대소문자를 가리지 않고
         * 앞뒤 공백은 무시한다. 맨 이메일 주소·전화번호처럼 추정이 필요한 것은 [Text] 로 둔다 — 추정하면 사용자가 쓴 글이
         * 다른 종류로 바뀐다.
         */
        @JvmStatic
        public fun parse(text: String): QrPayload = PayloadParser.parse(text)

        /**
         * 어떤 기호에서 읽었는지 아는 문자열을 푼다. 상품 바코드([CodeFormat.Kind.PRODUCT])면 내용과 무관하게
         * [Product] 이고, 그 밖의 형식은 [parse] 와 같다. 던지지 않는다.
         */
        @JvmStatic
        public fun parse(text: String, format: CodeFormat): QrPayload =
            if (format.kind == CodeFormat.Kind.PRODUCT && text.isNotBlank()) Product(text.trim()) else parse(text)

        /**
         * 원문의 한 줄 요약. [parse] 한 값의 [summary] 이고, 그것이 비면(이름 없는 연락처 등) 원문 첫 줄로 돌아간다.
         * 비밀이 든 원문(`WIFI:`·`otpauth`)으로는 돌아가지 않는다. 던지지 않는다.
         */
        @JvmStatic
        @JvmOverloads
        public fun summarize(text: String, zone: ZoneId = ZoneId.systemDefault()): String =
            parse(text).summary(zone).ifBlank { Summaries.text(text) }
    }
}

/** Wi-Fi 보안 방식. [token] 은 `WIFI:` 문자열의 `T:` 값이다. */
public enum class WifiSecurity(public val token: String) {
    /** 개방망. 비밀번호를 쓰지 않는다. */
    NONE("nopass"),
    WEP("WEP"),

    /** WPA·WPA2 개인용. 토큰을 빠뜨렸거나 모르는 토큰도 비밀번호가 있으면 이것으로 읽는다. */
    WPA("WPA"),

    /** WPA3 개인용(SAE). `SAE`·`WPA3` 토큰을 이것으로 읽는다. */
    WPA3("SAE"),

    /** WPA2 기업용(802.1X). `WPA2-EAP`·`WPA-EAP`·`EAP` 토큰을 이것으로 읽는다. */
    WPA2_EAP("WPA2-EAP"),
    ;

    public companion object {
        /** `T:` 값을 보안 방식으로. 비거나 null 이면 [NONE], 모르는 값은 [WPA] 다. */
        @JvmStatic
        public fun fromToken(token: String?): WifiSecurity {
            val t = token?.trim()?.uppercase().orEmpty()
            return when {
                t.isEmpty() || t == "NOPASS" -> NONE
                t == "WEP" -> WEP
                t == "SAE" || t == "WPA3" -> WPA3
                t.endsWith("EAP") -> WPA2_EAP
                else -> WPA
            }
        }
    }
}

/** 연락처의 전화번호 하나. */
public data class ContactPhone(val number: String, val type: PhoneType = PhoneType.OTHER)

/** 전화번호 종류. vCard 의 `TYPE` 값(`CELL`·`HOME`·`WORK`·`FAX`)에 대응한다. */
public enum class PhoneType {
    MOBILE,
    HOME,
    WORK,
    FAX,

    /** 종류 없음 또는 그 밖의 종류. */
    OTHER,
}

/** OTP 종류. */
public enum class OtpType {
    /** 시각 기반(RFC 6238). */
    TOTP,

    /** 횟수 기반(RFC 4226). */
    HOTP,
}

/** OTP 해시 알고리즘. */
public enum class OtpAlgorithm {
    SHA1,
    SHA256,
    SHA512,
}
