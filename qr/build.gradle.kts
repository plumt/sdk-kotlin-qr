plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

// Android(D8)·서버 양쪽에서 읽히도록 바이트코드는 Java 11 로 낸다. 빌드 JDK 는 무엇이든 된다
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // 인코딩·디코딩 엔진. 공개 API 에 ZXing 타입을 드러내지 않는다 — 엔진을 바꿔도 소비자 코드가 깨지지 않게
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    // 테스트에서만 PNG 를 읽고 쓴다
    testImplementation(libs.zxing.javase)
}

// 라인 커버리지 90% 미만이면 check(build) 가 실패한다. 제외 파일 없음
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}

mavenPublishing {
    coordinates(artifactId = "qr", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("qr")
        description.set("QR·바코드 생성과 읽기, Wi-Fi·연락처·일정 등 내용 해석을 하는 Kotlin 라이브러리")
        inceptionYear.set("2026")
    }
}
