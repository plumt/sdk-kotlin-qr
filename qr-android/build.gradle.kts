import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "net.plumt.qr.scanner"
    compileSdk = 36
    // 소비 앱의 리소스와 이름이 겹치지 않게 접두사를 강제한다
    resourcePrefix = "plumt_qr_"

    defaultConfig {
        // SDK 공통 하한은 23 이지만 26 으로 올린다 — 결과 타입(qr 의 QrPayload·EventTime)과 요약·일정 실행이 java.time 을 쓰고,
        // java.time 은 API 26 부터 기기에 있다. 26 미만을 받으려면 앱마다 core library desugaring 을 켜야 해 기본값으로 두지 않는다(README)
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // 코루틴 디버그 스택 복구가 예외를 복사해 cause 가 달라지는 것을 막아 cause 보존을 정확히 검증한다
            it.systemProperty("kotlinx.coroutines.stacktrace.recovery", "false")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // 결과 타입(ScanResult·QrPayload·CodeFormat)이 공개 API 에 나온다. 앱은 qr-android 하나만 추가한다
    api(project(":qr"))
    // StateFlow 가 공개 API 에 나온다
    api(libs.kotlinx.coroutines.core)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    // Modifier 가 공개 API 에 나온다
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core)

    // 카메라. 공개 API 에 CameraX 타입을 드러내지 않는다 — 렌즈·배율 모드는 이 SDK 의 enum 이다
    implementation(libs.camerax.core)
    // import 가 없어도 빼지 않는다 — CameraX 의 Camera2 백엔드 구현을 등록한다
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // 기본 인식 엔진(번들 모델 — 기기에 Google Play 서비스가 없어도 돈다). ZXing 엔진만 쓰는 앱은 exclude 할 수 있다(README)
    implementation(libs.mlkit.barcode)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(artifactId = "qr-android", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("qr-android")
        description.set("CameraX 실시간 QR·바코드 스캐너 Compose 컴포넌트 — 가운데 고정·다중 추적·ML Kit/ZXing 엔진·내용별 실행")
        inceptionYear.set("2026")
    }
}

kover {
    reports {
        filters {
            excludes {
                // 유닛 테스트로 실행할 수 없는 접착 코드만 뺀다. 판단 로직은 모두 internal 순수 클래스에 있고 테스트가 검증한다.
                classes(
                    // @Composable 함수 — 컴포지션·AndroidView(PreviewView)·포인터 입력이 있어야 돈다
                    "net.plumt.qr.scanner.ScannerComposablesKt*",
                    "net.plumt.qr.scanner.CodeScannerPreviewKt*",
                    "net.plumt.qr.scanner.action.QrActionConfirmationKt*",
                    "net.plumt.qr.scanner.ComposableSingletons*",
                    "net.plumt.qr.scanner.action.ComposableSingletons*",
                    // CameraX ProcessCameraProvider 바인딩 — 실제 카메라 HAL 이 있어야 돈다(Robolectric 은 카메라를 열지 못한다)
                    "net.plumt.qr.scanner.internal.CameraXBinder*",
                    "net.plumt.qr.scanner.internal.CameraXHandle*",
                    // ML Kit 네이티브 인식기 생성·호출 — 번들 모델이 JVM 에서 돌지 않는다. 결과 변환(MlKitMapper)은 테스트한다
                    "net.plumt.qr.scanner.internal.MlKitEngine*",
                )
            }
        }
        verify {
            rule {
                // 라인 커버리지 90% 미만이면 check(build) 가 실패한다
                minBound(90)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
