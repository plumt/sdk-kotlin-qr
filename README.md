# sdk-kotlin-qr

QR·바코드를 만들고 읽고, 그 안의 내용(Wi-Fi·연락처·일정·OTP 등)을 해석하는 Kotlin 라이브러리다.
`qr` 은 순수 JVM(jar)이라 Android·서버·데스크톱에서 같이 쓴다. 플랫폼 그래픽 타입(`Bitmap`·`BufferedImage`)을 받거나 돌려주지 않고
픽셀 배열과 흑백 격자로 주고받는다. `qr-android` 는 그 위에 실시간 카메라 스캐너(Compose)를 얹는다.

| 모듈 | 좌표 | 상태 | 내용 |
|---|---|---|---|
| `qr` | `net.plumt.qr:qr` | 0.0.1 미발행 | 만들기·읽기·내용 해석(jar) |
| `qr-android` | `net.plumt.qr:qr-android` | 0.0.1 미발행 | 실시간 카메라 스캐너 Compose 컴포넌트·내용별 실행(aar). `qr` 을 함께 가져온다 |

## qr

```kotlin
dependencies {
    implementation("net.plumt.qr:qr:0.0.1")
}
```

엔진은 [ZXing](https://github.com/zxing/zxing) core 3.5.3 이다. 공개 API 에는 ZXing 타입이 하나도 없어 엔진을 바꿔도 호출부가 깨지지 않는다.

### 내용 만들기·해석하기 — `QrPayload`

```kotlin
import net.plumt.qr.*

val wifi = QrPayload.Wifi("Home", "pw123456")
wifi.encode()                                  // WIFI:T:WPA;S:Home;P:pw123456;;

when (val p = QrPayload.parse(scannedText)) {
    is QrPayload.Wifi -> connect(p.ssid, p.password, p.security)
    is QrPayload.Contact -> save(p.name, p.phones, p.emails)
    is QrPayload.Event -> addToCalendar(p.title, p.start?.toInstant(zone))
    is QrPayload.Otp -> register(p.issuer, p.account, p.secret)
    is QrPayload.Url, is QrPayload.Email, is QrPayload.Phone, is QrPayload.Sms,
    is QrPayload.Geo, is QrPayload.Product, is QrPayload.Text -> Unit
}

QrPayload.summarize(scannedText)               // 목록에 보일 한 줄. Wi-Fi 비밀번호·OTP 비밀은 넣지 않는다
QrPayload.parse("4006381333931", CodeFormat.EAN_13)   // Product — 상품 바코드에서 읽은 번호
```

| 타입 | 쓰기 | 읽기 |
|---|---|---|
| `Url` | 스킴이 없으면 `https://` | `http(s)://` |
| `Text` | 그대로 | 그 밖의 모든 것, 깨진 입력 |
| `Wifi` | `WIFI:T:..;S:..;P:..;H:..;;` (WPA2-EAP 의 `E`·`A`·`I`·`PH2` 포함) | 같은 형식, 순서·대소문자·따옴표 무관 |
| `Contact` | vCard 3.0, `encodeMeCard()` 로 MECARD | vCard 2.1(QUOTED-PRINTABLE)·3.0·4.0, MECARD |
| `Email` | `mailto:` | `mailto:`·`MATMSG:`·`SMTP:` |
| `Phone`·`Sms` | `tel:`·`smsto:` | `tel:`·`smsto:`·`sms:?body=`·`mmsto:` |
| `Event` | `VEVENT`(종일·현지·TZID·UTC) | `VEVENT`, `VCALENDAR` 안의 첫 `VEVENT` |
| `Geo` | `geo:위도,경도[,고도][?q=]` | 같은 형식과 `;u=` 같은 매개변수 |
| `Otp` | `otpauth://totp|hotp/...` | 같은 형식 |
| `Product` | 번호 그대로 | `parse(text, format)` 에 상품 바코드 형식을 줄 때만 |

- `parse` 는 던지지 않는다. 구조로 읽은 값은 언제나 `encode` 가 성공하고 `parse(p.encode()) == p` 다
- `encode` 는 쓸 수 없는 값(빈 SSID, 범위 밖 좌표, base32 가 아닌 OTP 비밀 등)이면 `IllegalArgumentException`
- 맨 이메일 주소·전화번호는 추정하지 않고 `Text` 로 둔다. 사용자가 쓴 글이 다른 종류로 바뀌지 않게 하려는 것이다

### 상품·도서 번호 — `BarcodeNumber`

```kotlin
BarcodeNumber.detect("9780306406157")                       // ISBN_13
BarcodeNumber.isValid("96385074", BarcodeNumberType.EAN_8)  // true
BarcodeNumber.withCheckDigit("400638133393", BarcodeNumberType.EAN_13)   // "4006381333931"
BarcodeNumber.upcEToUpcA("04252614")                         // "042100005264"
BarcodeNumber.isbn10ToIsbn13("080442957X")                   // "9780804429573"
```

EAN-13·EAN-8·UPC-A·UPC-E·ITF-14(GTIN-14)·ISBN-10·ISBN-13 을 다룬다. 판별·검증은 null·false 로, 계산·변환은 `IllegalArgumentException` 으로 틀린 입력을 알린다.

### 코드 만들기 — `QrCode`·`Barcode`

```kotlin
val qr: CodeMatrix = QrCode.encode(wifi, QrCodeOptions(errorCorrection = ErrorCorrection.H, margin = 4))
val ean = Barcode.encode("4006381333931", CodeFormat.EAN_13, BarcodeOptions(width = 600, height = 200))
```

결과 `CodeMatrix` 는 불변 흑백 격자다(`true` = 어두운 칸). `QrCode.encode` 는 한 칸 = 한 모듈로 내고, 크게 그릴 때는
`scaled(n)` 으로 보간 없이 키운다. `Barcode.encode` 는 요청 크기에 맞춰 정수 배로 키우고 남는 칸은 가운데 정렬의 여백으로 둔다.

Android 에서 비트맵으로 그리기:

```kotlin
val m = QrCode.encode("https://example.com").scaled(8)
val bitmap = Bitmap.createBitmap(m.toArgb(), m.width, m.height, Bitmap.Config.ARGB_8888)
// 색을 바꾸려면 m.toArgb(foreground = Color.BLACK, background = Color.TRANSPARENT)
```

Compose `Canvas` 에서 칸마다 사각형을 칠해도 된다(`for (y) for (x) if (m[x, y]) drawRect(...)`). 서버에서는 `m.toPng()` 이
`java.util.zip` 만으로 1비트 PNG 바이트를 낸다(`java.awt` 없음).

### 코드 읽기 — `CodeReader`

```kotlin
// 카메라 프레임의 Y 평면 (CameraX ImageProxy.planes[0])
val result = CodeReader.read(yBytes, width, height, rowStride = plane.rowStride,
    options = ReadOptions(formats = CodeFormat.QR_ONLY))

// 사진 한 장 (Android Bitmap)
val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
val all = CodeReader.readAll(pixels, bitmap.width, bitmap.height, ReadOptions(tryHarder = true))
all.forEach { println("${it.format} ${it.text} ${it.payload} ${it.points}") }
```

- 코드가 없으면 `null`·빈 목록이다. 예외가 아니다
- `ScanResult.toString` 은 원문을 넣지 않는다 — 내용은 비밀을 가린 `payload` 로 보인다
- `ScanResult.payload` 는 읽은 형식까지 반영해 푼 내용이다(EAN 이면 `Product`)
- `points` 는 코드 위치를 알려 주는 점이다. QR 은 세 찾기 패턴의 중심이라 네 꼭짓점이 아니다
- 상태가 없어 여러 스레드에서 함께 불러도 된다

실시간 카메라 미리보기·분석 연결은 [`qr-android`](#qr-android) 가 한다. 카메라 권한 요청은 앱이 한다.

### 오류

인자가 틀리면 `IllegalArgumentException`, 인자는 맞는데 엔진이 끝내지 못하면 `QrCodeException`(원인은 `cause`)이다.
자세한 목록은 [ERRORS.md](ERRORS.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

## qr-android

CameraX 미리보기 위에 코드 박스와 칩(종류 + 한 줄 요약)을 그리는 Compose 스캐너다. 앱은 이 모듈 하나만 추가한다(`qr` 이 `api` 로 따라온다).

```kotlin
dependencies {
    implementation("net.plumt.qr:qr-android:0.0.1")
}
```

최소 요구: **minSdk 26**, compileSdk 36, Jetpack Compose(BOM 2025.02.00 기준), Java 11 바이트코드. DI 프레임워크에 의존하지 않는다.

minSdk 가 SDK 공통 하한(23)보다 높은 이유: 결과 타입(`qr` 의 `QrPayload`·`EventTime`)과 칩 요약·일정 실행이 `java.time` 을 쓰는데, `java.time` 은 API 26 부터
기기에 있다. 26 미만 기기를 받는 앱은 [core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring) 을 켜고
매니페스트에 `<uses-sdk tools:overrideLibrary="net.plumt.qr.scanner"/>` 를 적어야 한다(시험하지 않은 구성이다).

### 쓰기

```kotlin
// 카메라 권한은 앱이 선언·요청한다 (AndroidManifest: <uses-permission android:name="android.permission.CAMERA"/>)
CodeScanner(
    mode = ScanMode.Single(),                 // 가운데 코드에 고정, 칩을 탭하면 넘긴다
    onCodeTap = { result -> selected = result },
    modifier = Modifier.fillMaxSize(),
    permissionContent = { CameraPermissionRequest() },   // 권한이 없을 때 그릴 것(앱의 화면)
)
```

| 모드 | 동작 |
|---|---|
| `ScanMode.Single()` | 미리보기 가운데에 가장 가까운 코드에 고정한다. 더 가까운 코드가 생겨도 `LockOptions.hysteresis`(대각선 비율) 이상 가깝고 `switchFrames` 프레임 연속일 때만 옮긴다 — 코드 둘 사이에서 깜빡이지 않는다. 고정된 코드의 칩만 띄운다 |
| `ScanMode.Single(autoDeliver = true)` | 탭 없이 넘긴다. 고정이 `AutoDeliverOptions.stableMillis`(기본 300ms) 유지돼야 넘기고, 같은 추적 번호는 한 번, 같은 원문은 `repeatWindowMillis`(기본 2초) 안에 다시 넘기지 않는다 |
| `ScanMode.Multi` | 보이는 코드를 모두 추적한다(프레임 사이 같은 번호, 위치 평활, `maxMissedFrames` 넘게 놓치면 지움). 코드마다 칩, 탭한 것을 넘긴다 |

칩의 요약은 `QrPayload.summary` 다 — Wi-Fi 비밀번호·OTP 비밀은 화면에 나오지 않는다.

### 넘겨받은 결과 실행하기 — `QrActionExecutor`

스캐너는 결과로 아무것도 실행하지 않는다. 앱이 확인을 받은 뒤 부른다.

```kotlin
val executor = remember { QrActionExecutor.default() }
var pending by remember { mutableStateOf<QrAction?>(null) }

CodeScanner(ScanMode.Single(), onCodeTap = { pending = QrActions.primary(it.payload) })

pending?.let { action ->
    QrActionConfirmation(                         // 링크면 실제 접속 호스트(퓨니코드)와 경고를 보인다
        action = action,
        onConfirm = { executor.execute(context, action); pending = null },
        onDismiss = { pending = null },
    )
}
```

| 내용 | 대표 동작(`QrActions.primary`) | 기본 실행 |
|---|---|---|
| `Url` | `OpenUrl` — `host`(ASCII/퓨니코드)·`displayHost`·`warnings`(http·`사용자@`·국제화 도메인·IP·읽을 수 없는 호스트) | `ACTION_VIEW` + `CATEGORY_BROWSABLE` |
| `Wifi` | `ConnectWifi` | API 30+ 시스템 「네트워크 추가」 창(`ACTION_WIFI_ADD_NETWORKS`, 권한 불필요, 사용자가 승인). WEP·WPA2-EAP·규격 밖 비밀번호·API 29 이하는 Wi-Fi 설정 화면 + 비밀번호 클립보드(민감 표시, `copyWifiPasswordOnFallback` 로 끔) |
| `Contact` | `AddContact` | 연락처 삽입 화면(`ContactsContract.Intents.Insert`). 전화·이메일 넷째부터와 웹 주소는 `DATA` 행 |
| `Event` | `AddEvent` | 캘린더 일정 추가 화면. 종일은 UTC 자정 |
| `Phone`·`Sms`·`Email` | `Dial`·`SendSms`·`SendEmail` | 다이얼러(걸지 않음)·문자·메일 작성 화면(보내지 않음) |
| `Geo` | `ShowLocation` | `geo:` |
| `Otp` | `AddOtp`(+`CopyOtpSecret`) | `otpauth://` 를 처리하는 인증 앱으로 넘긴다. 없으면 `NoHandler` — 비밀 복사(민감 표시)를 제안한다 |
| `Product` | `SearchProduct` | 기기 웹 검색(`ACTION_WEB_SEARCH`) |
| `Text` | `CopyText` | 클립보드 |

`QrActions.all(result)` 는 대표 동작과 복사 동작을 함께 준다(Wi-Fi·OTP 는 원문 대신 비밀 복사). 결과는 `Started`·`Copied`·`WifiSettingsOpened`·
`NoHandler`(처리 앱 없음 — 실패 아님)·`Failed(ScannerException)` 이다. 동작을 바꾸려면 `QrActionExecutor` 를 구현하거나 `default()` 를 감싼다.

### 직접 그리기 — `ScannerState`

```kotlin
val state = rememberScannerState(ScanMode.Multi, ScannerOptions(formats = CodeFormat.ALL))
Box {
    CodeScannerPreview(state, Modifier.fillMaxSize())      // 미리보기와 카메라 수명만
    val codes by state.codes.collectAsState()            // 뷰 좌표의 박스, 추적 번호, 고정 여부
    codes.forEach { MyChip(it) }
}
state.setTorchEnabled(true); state.setZoomRatio(2f); state.focusAt(x, y); state.setPaused(true)
```

`CodeScanner` 에 같은 `state` 를 넘기면 기본 오버레이를 쓰면서 플래시·확대를 밖에서 조작할 수 있다. 칩 모양만 바꾸려면 `chip` 슬롯을 준다.

### 설정 — `ScannerOptions`

| 항목 | 기본 | 뜻 |
|---|---|---|
| `formats` | `CodeFormat.QR_ONLY` | 찾을 형식. 1차원 형식이 있으면 분석 해상도를 1280x960 으로 올린다(`analysisResolution`) |
| `engine` | `ScanEngines.mlKit()` | 인식 엔진(아래) |
| `lens` | `BACK` | `FRONT` 면 미리보기처럼 박스도 좌우 반전 |
| `scaleMode` | `FILL` | `FILL`(꽉 채움, 잘린 곳의 코드는 고르지 않음)·`FIT`(빈 띠) — 미리보기와 박스 변환에 함께 쓰인다 |
| `tracking` | 놓침 5프레임·평활 0.5·IoU 0.3 | 다중 추적 |
| `lock` | 0.1·3프레임 | 가운데 고정의 히스테리시스 |
| `pinchToZoom`·`tapToFocus` | 켬 | 두 손가락 확대, 코드가 아닌 곳 탭 → 초점 |

### 인식 엔진

| 엔진 | 장점 | 비용 |
|---|---|---|
| `ScanEngines.mlKit()` (기본) | 기울어짐·흐림·작은 코드에 강하다. 번들 모델이라 Google Play 서비스가 없는 기기에서도 돈다 | 모델이 APK 에 들어가 크기가 커진다(ML Kit 문서 기준 약 2.4MB). Google 라이브러리가 들어간다 |
| `ScanEngines.zxing()` | Google 라이브러리 없음(`qr` 의 ZXing). 작다 | 기울어짐·흐림에 약하다. 한 프레임에 같은 원문 코드가 여럿이면 하나만 잡는다. 박스는 위치 점에서 어림한다(아래) |

ZXing 박스는 근사다. ZXing 은 네 모서리를 주지 않고 QR 이면 세 찾기 패턴의 중심만 준다 — 네 번째 모서리를 평행사변형으로 채운 뒤 변 길이의 20% 를
사방으로 넓힌다. 찾기 패턴 중심이 모서리에서 3.5모듈 안쪽이라 작은 QR(버전 1~2)은 거의 맞고, 큰 QR 일수록 박스가 실제보다 조금 크다(버전 4 에서 변 기준 약 10%).
1차원 바코드는 읽은 줄의 양 끝 두 점이라 길이의 30% 를 높이로 잡는다. 가운데 고정·탭 판정에는 충분하지만 박스를 정확한 외곽선으로 쓰지 않는다.

ZXing 만 쓰는 앱은 ML Kit 을 빼도 된다 — `ScanEngines.mlKit()` 를 부르지 않는 한 ML Kit 클래스를 읽지 않는다.

```kotlin
implementation("net.plumt.qr:qr-android:0.0.1") { exclude(group = "com.google.mlkit") }
CodeScanner(..., options = ScannerOptions(engine = ScanEngines.zxing()))
```

ML Kit 을 뺀 채 `mlKit()` 를 쓰면 조용히 다른 엔진으로 바꾸지 않고 `CameraStatus.Error(ScannerException)` 로 알린다.
다른 인식기를 쓰려면 `ScanEngine`(프레임 → `Detection` 목록, 바로 세운 이미지 좌표)과 `ScanEngine.Factory` 를 구현해 `engine` 에 넣는다.

### 권한·매니페스트

- **`CAMERA` 권한을 선언하지 않는다.** 앱이 선언하고 요청한다 — 무엇이 설치 동의 목록에 뜨는지는 앱이 정한다. 권한이 없으면 `cameraStatus` 가
  `PermissionRequired` 이고 `CodeScanner` 는 `permissionContent` 를 그린다. 요청 결과를 받으면 `state.refreshPermission()` 을 부른다
  (화면이 다시 보일 때는 스스로 다시 읽는다)
- `<uses-feature android:name="android.hardware.camera" android:required="false"/>`(autofocus 도 같다)를 선언한다. 앱이 `CAMERA` 를 선언하면
  Play 가 카메라를 필수 하드웨어로 추정하는데, 이 스캐너는 카메라가 없는 기기를 `CameraStatus.NoCamera` 로 처리하므로 필수로 두지 않는다.
  카메라가 꼭 있어야 하는 앱은 자기 매니페스트에 `required="true"` 를 적으면 그쪽이 이긴다

### 카메라·스레드·수명

- 미리보기와 분석은 같은 4:3 비율로 잡는다(박스 좌표가 맞으려면 두 스트림의 비율이 같아야 한다). 분석은 `STRATEGY_KEEP_ONLY_LATEST` — 엔진이 느리면 프레임을 건너뛴다
- 엔진은 전용 단일 스레드에서 돈다. 프레임은 엔진이 끝나면 바로 반납한다
- 컴포저블이 사라지거나 수명주기 소유자가 바뀌면 이 미리보기의 use case 둘만 푼다(`unbindAll` 을 쓰지 않는다), 분석기를 떼고, 돌던 프레임이 끝난 뒤 엔진을 닫는다
- 좌표 변환: 버퍼 → 바로 세운 이미지(회전 0·90·180·270) → 뷰(`FILL`/`FIT` 배율, 가운데 정렬, 전면 반전). ML Kit 은 회전을 적용한 좌표를 주므로 그대로, ZXing 은 버퍼 좌표를 돌린다

### 위협 모델

| 위협 | 대응 |
|---|---|
| 모르는 QR 이 바로 실행됨(악성 링크·Wi-Fi·결제 앱 링크) | SDK 는 아무것도 자동 실행하지 않는다. `autoDeliver` 도 콜백만 부른다. 실행은 앱이 확인 뒤 `QrActionExecutor` 로 |
| 비슷한 글자로 만든 가짜 도메인, `https://bank.example.com@evil.com` | `OpenUrl.host` 는 IDN 을 퓨니코드로 바꾼 실제 호스트다. 사용자 정보·국제화 도메인·IP·http 를 `warnings` 로 알린다. 기본 확인 대화상자가 실제 호스트를 보인다. `\` 는 호스트의 끝으로 본다(브라우저가 `/` 로 다룬다) |
| 비밀 노출(화면·로그) | 칩 요약·`TrackedCode.toString`·`QrAction.toString` 에 Wi-Fi 비밀번호·OTP 비밀이 없다. 예외 메시지에 원문을 넣지 않는다. 복사하는 비밀은 클립보드 민감 표시(Android 13+ 가 미리보기를 가린다) |
| 처리 앱이 비밀을 받음 | `AddOtp` 는 `otpauth://` 를 사용자가 고른 인증 앱에 넘긴다 — 비밀이 그 앱으로 가는 것이 목적이다 |

`qr` 의 `ScanResult`·`QrPayload` 의 `toString` 도 원문을 담지 않는다(비밀이 들 수 있는 `Text`·주소의 사용자 정보는 가린다). 그래도 `text`·`password`·`secret` 속성을 직접 로그에 찍지 않는다.

### 오류

인자가 틀리면 `IllegalArgumentException`, 그 밖의 실패는 `ScannerException`(원인은 `cause`) 하나다. 카메라 실패는 `CameraStatus.Error`, 엔진 실패는
`state.error`·`onError`(세션마다 첫 한 건), 실행 실패는 `QrActionResult.Failed`. 자세한 목록은 [ERRORS.md](ERRORS.md#qr-android).

### ProGuard·R8

`consumer-rules.pro` 가 소비 앱에 자동 적용된다 — ML Kit 바코드 내부 클래스와 ZXing 을 보존한다. 앱이 따로 할 일은 없다.

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `qr-v0.0.1`, `qr-android-v0.0.1`)이다.
1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다. 공개 API 는 `<모듈>/api/*.api` 로 고정하고 CI 가 대조한다.
빌드는 모듈마다 라인 커버리지 90% 미만이면 실패한다(Kover). `qr-android` 는 유닛 테스트로 돌릴 수 없는 접착 코드(@Composable 함수,
CameraX 바인딩, ML Kit 네이티브 인식기 호출)만 측정에서 빼고, 이유를 `qr-android/build.gradle.kts` 에 적었다. 판단 로직(좌표 변환·추적·고정·자동 넘기기·
엔진 결과 변환·실행 Intent)은 모두 측정 대상이다.

## 라이선스

[Apache License 2.0](LICENSE)
