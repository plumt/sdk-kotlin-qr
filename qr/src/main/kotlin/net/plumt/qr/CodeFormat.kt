package net.plumt.qr

/**
 * 코드(기호)의 형식 — 무엇을 만들지, 무엇을 읽을지, 무엇을 읽었는지.
 *
 * 엔진의 형식 상수와 분리된 이 라이브러리의 타입이다. 생성([Barcode.encode])과 읽기([CodeReader]) 모두 이 값을 쓴다.
 *
 * @property kind 기호의 갈래. 같은 숫자 문자열이라도 상품 바코드면 상품 번호, QR 이면 일반 텍스트로 풀린다
 */
public enum class CodeFormat(public val kind: Kind) {
    QR_CODE(Kind.MATRIX),
    DATA_MATRIX(Kind.MATRIX),
    AZTEC(Kind.MATRIX),
    PDF_417(Kind.MATRIX),
    EAN_13(Kind.PRODUCT),
    EAN_8(Kind.PRODUCT),
    UPC_A(Kind.PRODUCT),
    UPC_E(Kind.PRODUCT),
    CODE_128(Kind.LINEAR),
    CODE_39(Kind.LINEAR),
    CODE_93(Kind.LINEAR),
    CODABAR(Kind.LINEAR),
    ITF(Kind.LINEAR),
    ;

    /** 기호의 갈래. */
    public enum class Kind {
        /** 2차원 기호 — 링크·Wi-Fi 같은 구조화된 내용을 담는다. */
        MATRIX,

        /** 상품 바코드(EAN·UPC) — 내용은 언제나 상품 번호다. */
        PRODUCT,

        /** 그 밖의 1차원 바코드 — 송장·재고 번호처럼 짧은 문자열을 담는다. */
        LINEAR,
    }

    public companion object {
        /** QR 만. 실시간 카메라처럼 오인식을 줄여야 하는 곳의 권장값이다. */
        @JvmField
        public val QR_ONLY: Set<CodeFormat> = setOf(QR_CODE)

        /** 상품 바코드(EAN·UPC) 전부. */
        @JvmField
        public val PRODUCT: Set<CodeFormat> = entries.filter { it.kind == Kind.PRODUCT }.toSet()

        /** 지원하는 모든 형식. */
        @JvmField
        public val ALL: Set<CodeFormat> = entries.toSet()

        private val BY_KEY: Map<String, CodeFormat> = entries.associateBy { key(it.name) }

        /**
         * 저장해 둔 형식 이름을 되읽는다. 대소문자와 `_`·`-`·공백을 가리지 않는다 — `PDF_417`·`PDF417`·`pdf-417`, `EAN13`·`ean_13` 이
         * 모두 같은 형식이다(다른 라이브러리·이전 버전이 밑줄 없이 쓴 이름도 읽는다). 모르는 이름이면 null 이다 — `valueOf` 와 달리
         * 던지지 않아, 기록을 읽다가 손상된 행 하나로 화면이 죽지 않는다. 저장할 때는 [name] 을 쓴다.
         */
        @JvmStatic
        public fun fromName(name: String): CodeFormat? = BY_KEY[key(name)]

        // ASCII 소문자만 대문자로 바꾼다 — uppercaseChar 는 'ı'(터키어)를 'I' 로 바꿔 다른 이름을 같은 형식으로 읽는다
        private fun key(name: String): String = buildString {
            name.forEach { c ->
                when (c) {
                    '_', '-', ' ' -> Unit
                    in 'a'..'z' -> append(c - ('a' - 'A'))
                    else -> append(c)
                }
            }
        }
    }
}
