plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kover) apply false
    // 공개 API 를 모듈별 .api 파일로 고정한다. 호환이 깨지는 변경은 apiCheck 가 막는다
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    // Compose 컴파일러가 만드는 내부 싱글턴(멤버 이름에 해시가 붙는다) — 공개 API 가 아니다
    ignoredClasses.addAll(
        listOf(
            "net.plumt.qr.scanner.ComposableSingletons\$ScannerComposablesKt",
            "net.plumt.qr.scanner.action.ComposableSingletons\$QrActionConfirmationKt",
        ),
    )
}
