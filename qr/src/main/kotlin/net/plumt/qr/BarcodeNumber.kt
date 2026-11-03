package net.plumt.qr

/**
 * 상품·도서 번호의 종류.
 *
 * @property length 체크 숫자를 포함한 자릿수
 * @property format 이 번호를 담는 바코드 형식. ISBN-10 은 바코드로 찍지 않아(ISBN-13 으로 바꿔 EAN-13 에 담는다) null 이다
 */
public enum class BarcodeNumberType(public val length: Int, public val format: CodeFormat?) {
    EAN_13(13, CodeFormat.EAN_13),
    EAN_8(8, CodeFormat.EAN_8),
    UPC_A(12, CodeFormat.UPC_A),

    /** 8자리(번호 체계 0·1 + 6자리 + 체크 숫자). 체크 숫자는 UPC-A 로 펼쳐 계산한다. */
    UPC_E(8, CodeFormat.UPC_E),

    /** GTIN-14. 물류 상자에 ITF 로 찍는다. */
    ITF_14(14, CodeFormat.ITF),

    /** 마지막 자리는 0~9 또는 `X`(10). */
    ISBN_10(10, null),

    /** 978·979 로 시작하는 EAN-13. */
    ISBN_13(13, CodeFormat.EAN_13),
}

/**
 * 상품 바코드(GTIN)·도서 번호(ISBN)의 판별·검증·체크 숫자 계산·변환. 순수 함수이고 상태가 없다.
 *
 * 입력의 `-` 와 공백은 무시한다(`978-89-...` 처럼 찍힌 번호를 그대로 받는다). 돌려주는 번호는 숫자만이다.
 *
 * ## 오류
 * - 판별·검증([detect]·[isValid])은 던지지 않는다 — 틀린 번호는 null·false 다
 * - 계산·변환([checkDigit]·[withCheckDigit]·`...To...`)은 입력이 틀리면 [IllegalArgumentException] — 자릿수, 숫자 아님,
 *   체크 숫자 불일치, 변환할 수 없는 번호(979 로 시작하는 ISBN-13 → ISBN-10 등)
 *
 * ```
 * BarcodeNumber.detect("9788966262472")           // ISBN_13
 * BarcodeNumber.withCheckDigit("880123456789", BarcodeNumberType.EAN_13)
 * BarcodeNumber.isbn13ToIsbn10("9788966262472")    // "8966262473"
 * ```
 */
public object BarcodeNumber {

    /**
     * [code] 의 종류. 체크 숫자까지 맞아야 한다. 맞는 종류가 없으면 null.
     *
     * 13자리는 978·979 로 시작하면 [BarcodeNumberType.ISBN_13], 아니면 [BarcodeNumberType.EAN_13] 이다.
     * 8자리는 EAN-8 과 UPC-E 가 겹칠 수 있어 EAN-8 을 먼저 본다. 확실한 종류를 알면 [isValid] 를 쓴다.
     */
    @JvmStatic
    public fun detect(code: String): BarcodeNumberType? {
        val c = normalize(code)
        return when (c.length) {
            13 -> when {
                !isValid(c, BarcodeNumberType.EAN_13) -> null
                isBookland(c) -> BarcodeNumberType.ISBN_13
                else -> BarcodeNumberType.EAN_13
            }
            12 -> BarcodeNumberType.UPC_A.takeIf { isValid(c, it) }
            8 -> when {
                isValid(c, BarcodeNumberType.EAN_8) -> BarcodeNumberType.EAN_8
                isValid(c, BarcodeNumberType.UPC_E) -> BarcodeNumberType.UPC_E
                else -> null
            }
            14 -> BarcodeNumberType.ITF_14.takeIf { isValid(c, it) }
            10 -> BarcodeNumberType.ISBN_10.takeIf { isValid(c, it) }
            else -> null
        }
    }

    /** [code] 가 [type] 의 자릿수·문자·체크 숫자를 모두 지키는지. ISBN-13 은 EAN-13 이면서 978·979 로 시작해야 한다. */
    @JvmStatic
    public fun isValid(code: String, type: BarcodeNumberType): Boolean {
        val c = normalize(code)
        if (c.length != type.length) return false
        return when (type) {
            BarcodeNumberType.ISBN_10 ->
                c.take(9).all { it in DIGITS } && (c[9] in DIGITS || c[9] == 'X' || c[9] == 'x') &&
                    isbn10Check(c.take(9)) == c[9].uppercaseChar()
            BarcodeNumberType.UPC_E ->
                c.all { it in DIGITS } && (c[0] == '0' || c[0] == '1') && gtinCheck(expandUpcE(c.take(7))) == c[7]
            BarcodeNumberType.ISBN_13 -> isBookland(c) && isValid(c, BarcodeNumberType.EAN_13)
            else -> c.all { it in DIGITS } && gtinCheck(c.dropLast(1)) == c.last()
        }
    }

    /**
     * 체크 숫자를 뺀 [body] 의 체크 숫자. [body] 는 [type] 의 자릿수보다 하나 짧아야 한다.
     * ISBN-10 은 `0`~`9` 또는 `X`, 그 밖은 `0`~`9` 다.
     */
    @JvmStatic
    public fun checkDigit(body: String, type: BarcodeNumberType): Char {
        val b = normalize(body)
        require(b.length == type.length - 1) { "${type.name} 의 체크 숫자 앞 부분은 ${type.length - 1}자리여야 한다 (받은 값: $body)" }
        require(b.all { it in DIGITS }) { "숫자만 받는다 (받은 값: $body)" }
        return when (type) {
            BarcodeNumberType.ISBN_10 -> isbn10Check(b)
            BarcodeNumberType.UPC_E -> {
                require(b[0] == '0' || b[0] == '1') { "UPC-E 의 번호 체계(첫 자리)는 0 또는 1 이어야 한다 (받은 값: $body)" }
                gtinCheck(expandUpcE(b))
            }
            BarcodeNumberType.ISBN_13 -> {
                require(isBookland(b)) { "ISBN-13 은 978 또는 979 로 시작해야 한다 (받은 값: $body)" }
                gtinCheck(b)
            }
            else -> gtinCheck(b)
        }
    }

    /** [body] 뒤에 [checkDigit] 을 붙인 완전한 번호. */
    @JvmStatic
    public fun withCheckDigit(body: String, type: BarcodeNumberType): String {
        val b = normalize(body)
        return b + checkDigit(b, type)
    }

    /** 8자리 UPC-E 를 12자리 UPC-A 로 펼친다. */
    @JvmStatic
    public fun upcEToUpcA(upcE: String): String {
        val c = requireValid(upcE, BarcodeNumberType.UPC_E)
        return expandUpcE(c.take(7)) + c[7]
    }

    /** 12자리 UPC-A 를 13자리 EAN-13 으로(앞에 0). */
    @JvmStatic
    public fun upcAToEan13(upcA: String): String = "0" + requireValid(upcA, BarcodeNumberType.UPC_A)

    /** 0 으로 시작하는 EAN-13 을 UPC-A 로. 0 으로 시작하지 않으면 UPC-A 로 나타낼 수 없어 [IllegalArgumentException]. */
    @JvmStatic
    public fun ean13ToUpcA(ean13: String): String {
        val c = requireValid(ean13, BarcodeNumberType.EAN_13)
        require(c[0] == '0') { "0 으로 시작하는 EAN-13 만 UPC-A 로 바꿀 수 있다 (받은 값: $ean13)" }
        return c.substring(1)
    }

    /** ISBN-10 을 ISBN-13(978 접두사)으로. */
    @JvmStatic
    public fun isbn10ToIsbn13(isbn10: String): String {
        val c = requireValid(isbn10, BarcodeNumberType.ISBN_10)
        return withCheckDigit("978" + c.take(9), BarcodeNumberType.EAN_13)
    }

    /** 978 로 시작하는 ISBN-13 을 ISBN-10 으로. 979 는 ISBN-10 이 없어 [IllegalArgumentException]. */
    @JvmStatic
    public fun isbn13ToIsbn10(isbn13: String): String {
        val c = requireValid(isbn13, BarcodeNumberType.ISBN_13)
        require(c.startsWith("978")) { "978 로 시작하는 ISBN-13 만 ISBN-10 으로 바꿀 수 있다 (받은 값: $isbn13)" }
        val body = c.substring(3, 12)
        return body + isbn10Check(body)
    }

    internal fun normalize(code: String): String = code.filterNot { it == '-' || it == ' ' }

    private fun requireValid(code: String, type: BarcodeNumberType): String {
        val c = normalize(code)
        require(isValid(c, type)) { "올바른 ${type.name} 가 아니다 — ${type.length}자리·체크 숫자를 확인한다 (받은 값: $code)" }
        return c.uppercase()
    }

    private fun isBookland(c: String) = c.startsWith("978") || c.startsWith("979")

    /** GTIN(EAN·UPC·ITF-14) 공통 mod 10: 오른쪽 끝부터 3·1 가중치. */
    private fun gtinCheck(body: String): Char {
        var sum = 0
        body.reversed().forEachIndexed { i, ch -> sum += (ch - '0') * (if (i % 2 == 0) 3 else 1) }
        return '0' + (10 - sum % 10) % 10
    }

    private fun isbn10Check(body: String): Char {
        var sum = 0
        body.forEachIndexed { i, ch -> sum += (ch - '0') * (10 - i) }
        val check = (11 - sum % 11) % 11
        return if (check == 10) 'X' else '0' + check
    }

    /** UPC-E 의 앞 7자리(번호 체계 + 6자리)를 UPC-A 의 앞 11자리로 펼친다. */
    private fun expandUpcE(body: String): String {
        val ns = body[0]
        val d = body.substring(1, 7)
        val core = when (d[5]) {
            '0', '1', '2' -> d.substring(0, 2) + d[5] + "0000" + d.substring(2, 5)
            '3' -> d.substring(0, 3) + "00000" + d.substring(3, 5)
            '4' -> d.substring(0, 4) + "00000" + d[4]
            else -> d.substring(0, 5) + "0000" + d[5]
        }
        return ns + core
    }

    private val DIGITS = '0'..'9'
}
