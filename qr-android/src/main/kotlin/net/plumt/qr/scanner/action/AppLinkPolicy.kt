package net.plumt.qr.scanner.action

import android.content.Intent
import androidx.core.net.toUri
import java.net.URISyntaxException
import java.util.Locale

/**
 * 앱 링크(http·https 가 아닌 스킴 — `<앱>://`·`market://`·`intent://` 등)를 열어도 되는지 정한다. **기본은 [NONE] — 하나도 열지 않는다.**
 *
 * 앱 링크는 누르는 즉시 다른 앱의 화면(결제·송금·로그인)을 연다. 모르는 QR 이 그 화면을 미리 채워 열 수 있으므로, 그 스킴이
 * 필요한 앱만 [allow] 로 고른 스킴을 열고, 열기 전에는 언제나 확인을 받는다([QrActions.requiresConfirmation] 이 참이다).
 *
 * `intent://` 는 다른 앱의 임의 화면을 지정할 수 있어 따로 켠다([allowIntentUri]). 켜도 기본 실행기는 이렇게 다듬어 연다 —
 * 대상 컴포넌트·셀렉터를 지우고, 웹 링크로 열 수 있다고 선언한 화면만 받게 `CATEGORY_BROWSABLE` 을 붙이고, URI 가 정한 실행 플래그
 * (URI 권한 부여 포함)를 지운다. action 은 VIEW 만 받고, `scheme=` 에 적힌 안쪽 스킴은 http·https 또는 [schemes] 안이어야 한다.
 * `package=` 는 남는다(그 앱이 없으면 [QrActionResult.NoHandler]). `S.키=값` 같은 추가 값도 남아 대상 앱에 전달된다.
 *
 * @property schemes 열어도 되는 스킴(소문자)
 * @property allowIntentUri `intent:` 를 열어도 되는지
 */
public class AppLinkPolicy private constructor(
    public val schemes: Set<String>,
    public val allowIntentUri: Boolean,
) {

    /** [url] 의 스킴이 이 정책으로 열리는가. http·https 는 앱 링크가 아니라 거짓이다. 던지지 않는다. */
    public fun allows(url: String): Boolean {
        val scheme = QrActions.schemeOf(url.trim()) ?: return false
        return if (scheme == INTENT) allowIntentUri else scheme in schemes
    }

    override fun equals(other: Any?): Boolean =
        other is AppLinkPolicy && other.schemes == schemes && other.allowIntentUri == allowIntentUri

    override fun hashCode(): Int = schemes.hashCode() * 31 + allowIntentUri.hashCode()

    override fun toString(): String = "AppLinkPolicy(schemes=${schemes.sorted()}, allowIntentUri=$allowIntentUri)"

    public companion object {
        internal const val INTENT = "intent"

        /** 앱 링크를 하나도 열지 않는다(기본). */
        @JvmField
        public val NONE: AppLinkPolicy = AppLinkPolicy(emptySet(), false)

        /** 웹(http·https)·`intent` 와 스크립트·로컬 자원을 여는 스킴. 이것들은 [allow] 에 넣을 수 없다. */
        @JvmField
        public val FORBIDDEN_SCHEMES: Set<String> =
            setOf("http", "https", INTENT, "javascript", "vbscript", "file", "content", "data", "about", "blob", "jar")

        /**
         * 고른 스킴만 연다. 스킴은 대소문자를 가리지 않고 `:`·`://` 없이 쓴다(`examplepay`, `market`).
         *
         * @param allowIntentUri `intent:` 도 연다 — 위험과 다듬는 규칙은 클래스 설명을 본다
         * @throws IllegalArgumentException 스킴 문법(영문으로 시작, 영문·숫자·`+`·`.`·`-`)에 맞지 않거나 [FORBIDDEN_SCHEMES] 일 때
         */
        @JvmStatic
        @JvmOverloads
        public fun allow(schemes: Set<String>, allowIntentUri: Boolean = false): AppLinkPolicy {
            val normalized = schemes.mapTo(LinkedHashSet()) { raw ->
                val s = raw.trim().lowercase(Locale.ROOT)
                require(isScheme(s)) { "스킴($raw) 의 문법이 틀렸다 — 영문으로 시작하고 영문·숫자·+·.·- 만 쓴다(:// 없이)" }
                require(s !in FORBIDDEN_SCHEMES) {
                    if (s == INTENT) "intent 는 schemes 가 아니라 allowIntentUri = true 로 켠다" else "스킴($s) 은 앱 링크로 열 수 없다"
                }
                s
            }
            return AppLinkPolicy(normalized, allowIntentUri)
        }

        private fun isScheme(s: String): Boolean =
            s.isNotEmpty() && s[0] in 'a'..'z' && s.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '.' || it == '-' }
    }
}

/** 앱 링크 Intent 조립. 순수 조립이라 실행하지 않는다. */
internal object AppLinkIntents {

    /**
     * [policy] 가 허용한 앱 링크를 여는 Intent. 허용하지 않거나 다듬을 수 없으면 [IllegalArgumentException].
     * 규칙은 [AppLinkPolicy] 의 설명이 정본이다.
     */
    fun build(url: String, policy: AppLinkPolicy): Intent {
        val trimmed = url.trim()
        val scheme = QrActions.schemeOf(trimmed)
        require(policy.allows(trimmed)) { "앱 링크 정책이 허용하지 않는 스킴이다(스킴: ${scheme ?: "없음"})" }
        if (scheme != AppLinkPolicy.INTENT) {
            return Intent(Intent.ACTION_VIEW, trimmed.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val intent = try {
            Intent.parseUri(trimmed, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("intent: 주소를 읽지 못했다", e)
        }
        val action = intent.action
        require(action == null || action == Intent.ACTION_VIEW) { "intent: 의 action 은 VIEW 만 연다(받은 값: $action)" }
        val inner = intent.data?.scheme?.lowercase(Locale.ROOT)
        require(inner == null || inner == "http" || inner == "https" || inner in policy.schemes) {
            "intent: 안쪽 스킴($inner) 이 앱 링크 정책에 없다"
        }
        return intent.apply {
            this.action = Intent.ACTION_VIEW
            component = null
            selector = null
            // URI 의 launchFlags(권한 부여 FLAG_GRANT_* 포함)를 버린다. 새 작업 플래그는 실행기가 필요할 때 붙인다
            flags = 0
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    /** 확인창에 보일 대상 — `intent:` 면 대상 앱 패키지(없으면 안쪽 스킴), 그 밖은 스킴. 읽지 못하면 null. */
    fun target(url: String): String? {
        val trimmed = url.trim()
        val scheme = QrActions.schemeOf(trimmed) ?: return null
        if (scheme != AppLinkPolicy.INTENT) return scheme
        val intent = try {
            Intent.parseUri(trimmed, Intent.URI_INTENT_SCHEME)
        } catch (_: URISyntaxException) {
            // 확인창은 대상 없이 주소만 보인다 — 실행기는 같은 주소를 Failed 로 거절한다
            return null
        }
        return intent.`package` ?: intent.data?.scheme?.lowercase(Locale.ROOT)
    }
}
