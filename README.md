# sdk-kotlin-qr

QR·바코드를 만들고 읽고, 그 안의 내용(Wi-Fi·연락처·일정·OTP 등)을 해석하는 Kotlin 라이브러리다.
`qr` 은 순수 JVM(jar)이라 Android·서버·데스크톱에서 같이 쓴다. 플랫폼 그래픽 타입(`Bitmap`·`BufferedImage`)을 받거나 돌려주지 않고
픽셀 배열과 흑백 격자로 주고받는다. `qr-android` 는 그 위에 실시간 카메라 스캐너(Compose)를 얹는다.

| 모듈 | 좌표 | 상태 | 내용 |
|---|---|---|---|
| `qr` | `net.plumt.qr:qr` | 0.0.2 미발행 | 만들기·읽기·내용 해석(jar) |
| `qr-android` | `net.plumt.qr:qr-android` | 0.1.0 미발행 | 실시간 카메라 스캐너 Compose 컴포넌트·내용별 실행(aar). `qr` 을 함께 가져온다 |

## qr

```kotlin
dependencies {
    implementation("net.plumt.qr:qr:0.0.2")
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
- `parse` 는 입력 길이에 선형 시간이다. `QrPayload.MAX_PARSE_LENGTH`(8192자 — 가장 큰 QR 도 담는다)보다 긴 원문은 구조를 풀지 않고 `Text` 다.
  카메라가 프레임마다 부르므로 악의적인 내용(공백 수천 칸 등)의 코드 하나가 분석 스레드를 붙들지 않게 한다
- `encode` 는 쓸 수 없는 값(빈 SSID, 범위 밖 좌표, base32 가 아닌 OTP 비밀 등)이면 `IllegalArgumentException`
- 맨 이메일 주소·전화번호는 기본으로 추정하지 않고 `Text` 로 둔다. 사용자가 쓴 글이 다른 종류로 바뀌지 않게 하려는 것이다
- 추정이 필요하면 옵트인한다 — `QrPayload.parse(text, ParseOptions.INFER_ALL)`·`parse(text, format, options)`.
  글 전체가 이메일(ASCII, `로컬@도메인.최상위`)·전화번호(`+`·숫자·공백·`-`·괄호, 숫자 7..15자리, `2024-01-15` 같은 날짜 제외) 모양일 때만
  `Email`·`Phone` 이 되고, 1차원 바코드에서 읽은 숫자열은 전화로 보지 않는다(송장 번호를 누르면 전화 앱이 뜨지 않게). 규칙 표는 `ParseOptions` KDoc

```kotlin
QrPayload.parse("user@example.com")                          // Text
QrPayload.parse("user@example.com", ParseOptions.INFER_ALL)  // Email("user@example.com")
QrPayload.parse("010-1234-5678", CodeFormat.CODE_128, ParseOptions.INFER_ALL)   // Text — 1차원 바코드는 전화로 보지 않는다
CodeFormat.fromName("PDF417")                                // PDF_417 — 저장된 이름을 되읽는다(대소문자·_·- 무시, 모르면 null)
```

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

정해진 픽셀 크기·색으로 그리려면 `CodeImages.render` 다 — 칸을 정수 배로 키우고 남는 픽셀은 가운데 정렬의 배경으로 채워 요청 크기를
정확히 낸다. Android 비트맵은 `qr-android` 의 `QrBitmaps.render` 가 같은 규칙으로 만든다.

```kotlin
val image: ArgbImage = CodeImages.render(QrCode.encode(text), 768, colors = CodeColors(foreground = 0xFF0D47A1.toInt(), background = 0xFFFFF9C4.toInt()))
```

### 오류 정정 단계 고르기·용량 초과

```kotlin
val level = QrCode.pickErrorCorrection(text)        // H → Q → M → L 중 들어가는 가장 높은 단계
try {
    QrCode.encode(text, QrCodeOptions(errorCorrection = level))
} catch (e: QrContentTooLongException) {             // IllegalArgumentException 의 하위 타입 — 사용자가 고칠 입력
    showTooLong(withLogo = e.withLogo)
}
```

짧은 내용은 가장 튼튼한 H 로, 긴 내용만 낮은 단계로 내려가 더 많이 담는다(한글은 H 에 약 420자, L 에 약 980자). 어느 단계에도 안 들어가면
`QrContentTooLongException` 이다. `QrCode.encode` 도 용량을 넘으면 같은 예외를 던진다.

### 로고 넣기 — `QrCode.encodeWithLogo`

```kotlin
val logo = QrLogoOptions(sizeRatio = 0.2f, paddingModules = 1)       // 기본값
val level = QrCode.pickErrorCorrection(text, logo)                   // 로고 자리를 허용하는 단계만 본다
val code: LogoQrCode = QrCode.encodeWithLogo(text, logo, QrCodeOptions(errorCorrection = level))
val image = CodeImages.render(code, sizePx = 768, logo = logoArgb)   // 로고를 로고 자리 가운데에 비율을 지켜 얹는다
```

로고 자리(`logoArea`)와 그 둘레 패딩(`clearedArea`)을 밝은 칸으로 비운다 — 로고가 모듈과 섞이지 않고, 비운 칸은 오류 정정이 복원한다.
비우는 자리의 한 변은 단계마다 상한이 있다(`ErrorCorrection.maxLogoRatio` — L 은 로고 불가, M 15%, Q 20%, H 30%). 복원력의 일부만 로고에 쓰고
나머지는 얼룩·흐림 몫으로 남기는 보수적인 값이다. 상한을 넘거나, 작은 버전에서 로고 자리가 찾기 패턴·형식 정보에 닿으면 `IllegalArgumentException`
이다(`sizeRatio` 를 줄이거나 `QrCodeOptions.minVersion` 을 올린다). 로고를 직접 그리려면 `code.matrix` 를 그리고 `logoArea × 배율` 자리에 얹는다.

### 색 대비 — `QrColors`

```kotlin
QrColors.contrastRatio(fg, bg)     // WCAG 대비 1.0..21.0
QrColors.readability(fg, bg)       // READABLE(4 이상)·MARGINAL(3..4)·LOW_CONTRAST(3 미만)·INVERTED(전경이 더 밝음)
CodeColors(fg, bg)                 // LOW_CONTRAST·INVERTED 면 IllegalArgumentException — 읽히지 않는 QR 을 만들지 않게
```

색은 ARGB 정수다. 알파가 있는 색은 흰 바탕에 얹은 색으로 본다(투명 배경 QR 은 대개 흰 화면·종이 위에 놓인다). 경계(`MARGINAL`)는 막지 않고
앱이 경고를 띄우게 둔다. 어두운 바탕의 밝은 코드를 일부러 그리면 `CodeColors(..., checkContrast = false)`.

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
- `tryHarder` 는 90도 돌아간 1차원 바코드도 읽는다(돌려 읽은 점도 받은 이미지 좌표다). 끄면 행만 훑어 세로로 선 바코드는 못 읽는다
- 만드는 격자는 `CodeMatrix.MAX_CELLS`(4096x4096) 칸까지다. 더 크게 보여야 하면 격자를 그리는 쪽(Canvas 등)에서 키운다

실시간 카메라 미리보기·분석 연결은 [`qr-android`](#qr-android) 가 한다. 카메라 권한 요청은 앱이 한다.

### 오류

인자가 틀리면 `IllegalArgumentException`, 인자는 맞는데 엔진이 끝내지 못하면 `QrCodeException`(원인은 `cause`)이다.
자세한 목록은 [ERRORS.md](ERRORS.md), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

## qr-android

CameraX 미리보기 위에 코드 박스와 칩(종류 + 한 줄 요약)을 그리는 Compose 스캐너다. 앱은 이 모듈 하나만 추가한다(`qr` 이 `api` 로 따라온다).

```kotlin
dependencies {
    implementation("net.plumt.qr:qr-android:0.1.0")
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
| `ScanMode.Multi()` | 보이는 코드를 모두 추적한다(프레임 사이 같은 번호, 위치 평활, `maxMissedFrames` 넘게 놓치면 지움). 코드마다 칩, 탭한 것을 넘긴다. 고정하지 않는다 |
| `ScanMode.Multi(lock, autoDeliver)` | 모두 추적하면서 하나에 고정한다(아래 「멀티 고정 정책」). `autoDeliver = true` 면 고정된 코드를 `Single` 과 같은 규칙으로 탭 없이 넘긴다 |

칩의 요약은 `QrPayload.summary` 다 — Wi-Fi 비밀번호·OTP 비밀은 화면에 나오지 않는다.

### 오버레이 바꾸기·숨기기

박스(`box`)와 칩(`chip`)은 따로 고른다 — 기본·숨김(`null`)·직접 그리기. 박스를 숨겨도 코드 자리 탭은 그대로 판정한다.

```kotlin
// 카메라만 — 오버레이 없이 가운데 코드를 탭 없이 넘긴다
CodeScanner(
    mode = ScanMode.Single(autoDeliver = true),
    onCodeTap = { result -> onScanned(result) },
    box = null,
    chip = null,
)

// 기본 박스의 색·두께·모서리만 바꾼다
CodeScanner(
    mode = ScanMode.Multi(),
    onCodeTap = { … },
    box = { code, dimmed ->
        QrCodeBox(code, dimmed, style = ScannerDefaults.boxStyle(lockedColor = Color.Green, lockedStrokeWidth = 4.dp, cornerRadius = 12.dp))
    },
)

// 직접 그리기 — 박스 슬롯은 코드 자리에 그 크기로 놓인다. 칩 슬롯은 박스 아래(모자라면 위)에 놓인다
CodeScanner(
    mode = ScanMode.Multi(MultiLock.Tap),
    onCodeTap = { … },
    box = { code, _ -> Box(Modifier.fillMaxSize().border(2.dp, if (code.isLocked) Color.Yellow else Color.White, CircleShape)) },
    chip = { code, onClick -> if (code.isLocked) AssistChip(onClick = onClick, label = { Text(ScannerDefaults.summary(code.result)) }) },
)
```

| 슬롯·인자 | 기본 | 뜻 |
|---|---|---|
| `box: (@Composable (code, dimmed) -> Unit)?` | `QrCodeBox(code, dimmed)` | 보이는 코드마다. `dimmed` 는 기본 박스가 흐리게 그릴 코드인가(`Single` 의 고정 안 된 코드, `Multi` 에서 다른 코드가 고정됐을 때). `null` 이면 그리지 않는다. 탭을 받는 컴포넌트를 그리면 그 자리 탭은 스캐너가 받지 않는다 |
| `chip: (@Composable (code, onClick) -> Unit)?` | `QrCodeChip` | `Single` 은 고정된 코드에만, `Multi` 는 보이는 코드마다. `onClick` 이 그 코드를 넘긴다. `null` 이면 그리지 않는다 |
| `tapToDeliver` | `true` | `false` 면 박스 자리·칩 탭이 넘기지 않는다(자동 넘기기만). `MultiLock.Tap` 의 탭 고정은 그대로다 |
| `ScannerDefaults.boxStyle(...)` | 보통·고정 `primary` 3dp, 흐림 반투명 흰색 2dp, 모서리 8dp | `CodeBoxStyle` — `color`·`lockedColor`·`dimmedColor`·`strokeWidth`·`lockedStrokeWidth`·`dimmedStrokeWidth`·`cornerRadius` |

### 멀티 고정 정책 — `MultiLock`

```kotlin
CodeScanner(mode = ScanMode.Multi(MultiLock.Tap, autoDeliver = true), onCodeTap = { … })   // 탭한 코드에 고정, 잠시 머물면 넘긴다
CodeScanner(mode = ScanMode.Multi(MultiLock.Nearest), onCodeTap = { … })                   // 가운데 코드에 고정, 칩은 모두
CodeScanner(
    mode = ScanMode.Multi(MultiLock.Custom { codes -> codes.firstOrNull { it.result.payload is QrPayload.Url } }),
    onCodeTap = { … },
)

// 앱이 직접 — 어느 모드·정책이든 lockOn 이 우선한다
val state = rememberScannerState(ScanMode.Multi())
state.lockOn(code.id)   // 그 코드가 사라지면 풀리고 정책대로 다시 고른다
state.unlock()
```

| 정책 | 고정 |
|---|---|
| `MultiLock.None` (기본) | 하지 않는다. `lockOn` 으로만 |
| `MultiLock.Tap` | 코드 박스 자리를 탭하면 그 코드로 옮기고, 고정된 코드를 다시 탭하면 푼다. 박스 자리 탭은 넘기지 않는다 — 칩 탭이나 `autoDeliver` 로 넘긴다 |
| `MultiLock.Nearest` | 가운데에 가장 가까운 코드. `Single` 과 같은 규칙(`LockOptions` 의 히스테리시스·`switchFrames`) |
| `MultiLock.Custom(select)` | 앱 함수가 프레임마다(분석 스레드) 보이는 코드 중 하나를 고른다. `isLocked` 는 직전 고정, null 이면 고정 없음. 던지면 그 프레임을 버리고 `onError` 로 한 번 알린다. 인라인 람다여도 상태를 새로 만들지 않는다 |

고정된 코드는 `TrackedCode.isLocked`·`ScannerState.lockedCode` 로 보인다(`Multi` 에서도). 고정된 코드가 화면에서 사라지면(추적에서 지워지거나 화면 밖)
고정이 풀린다. `unlock()` 뒤 스스로 고르는 정책(`Single`·`Nearest`·`Custom`)은 다음 프레임에 다시 고른다.

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
| `Url` | `OpenUrl` — `host`(ASCII/퓨니코드, 숫자 IPv4 는 `a.b.c.d`)·`displayHost`·`warnings`(http·`사용자@`·국제화 도메인·IP·읽을 수 없는 호스트·웹이 아닌 스킴) | `ACTION_VIEW` + `CATEGORY_BROWSABLE`. http·https 만 연다 |
| `Wifi` | `ConnectWifi` | API 30+ 시스템 「네트워크 추가」 창(`ACTION_WIFI_ADD_NETWORKS`, 권한 불필요, 사용자가 승인). WEP·WPA2-EAP·규격 밖 비밀번호·API 29 이하는 Wi-Fi 설정 화면 + 비밀번호 클립보드(민감 표시, `copyWifiPasswordOnFallback` 로 끔) |
| `Contact` | `AddContact` | 연락처 삽입 화면(`ContactsContract.Intents.Insert`). 전화·이메일 넷째부터와 웹 주소는 `DATA` 행 |
| `Event` | `AddEvent` | 캘린더 일정 추가 화면. 종일은 UTC 자정 |
| `Phone`·`Sms`·`Email` | `Dial`·`SendSms`·`SendEmail` | 다이얼러(걸지 않음)·문자·메일 작성 화면(보내지 않음) |
| `Geo` | `ShowLocation` | `geo:` |
| `Otp` | `AddOtp`(+`CopyOtpSecret`) | `otpauth://` 를 처리하는 인증 앱으로 넘긴다. 없으면 `NoHandler` — 비밀 복사(민감 표시)를 제안한다 |
| `Product` | `SearchProduct` | 기기 웹 검색(`ACTION_WEB_SEARCH`) |
| `Text` | `CopyText` | 클립보드 |

`QrAction.OpenUrl` 은 `QrActions.openUrl(url)` 로만 만든다 — 호스트·경고는 주소에서 계산되고 밖에서 넣을 수 없다(기록에서 복원할 때도 주소만 저장한다).
스킴이 없으면 `https://` 를 붙인다. 기본 확인창은 링크면 실제 호스트와 경고를, 그 밖의 동작은 다룰 값(번호·받는 사람·네트워크 이름 —
`ScannerDefaults.actionDetail`, 비밀 제외)을 보인다.

**앱 링크·결제 앱 스킴(`intent://`·`market://`·`<앱>://`)은 기본으로 열지 않는다.** `QrPayload.parse` 는 http·https 가 아닌 주소를 `Text`(복사)로 두고,
`QrActions.openUrl` 에 넣어도 `UrlWarning.NON_WEB_SCHEME` 이 붙어 기본 실행기는 `Failed` 로 거절한다 — 앱 링크는 누르는 즉시 다른 앱의 화면(결제·송금·로그인)을
연다. 그 스킴이 필요한 앱만 옵트인한다.

```kotlin
val appLinks = AppLinkPolicy.allow(setOf("examplepay", "market"), allowIntentUri = false)
val executor = remember { QrActionExecutor.default(QrActionOptions(), appLinks) }

val action = QrActions.primary(result.payload, appLinks)    // 허용한 스킴의 글이면 OpenUrl, 아니면 primary 와 같다
if (QrActions.requiresConfirmation(action)) {
    pending = action                                         // 앱 링크·경고 있는 링크는 언제나 확인을 받는다
} else {
    executor.execute(context, action)
}
pending?.let { QrActionConfirmation(it, appLinks, onConfirm = { executor.execute(context, it); pending = null }, onDismiss = { pending = null }) }
```

- 허용한 스킴은 `ACTION_VIEW` + `CATEGORY_BROWSABLE` 로 연다 — 웹 링크로 열 수 있다고 선언한 화면만 받는다
- 확인창은 「다른 앱을 열까요?」 제목, 열릴 대상(`intent:` 는 대상 앱 패키지, 그 밖은 스킴), 주소, 경고를 보인다. 실행기는 확인을 거쳤는지 알 수 없으므로
  `requiresConfirmation` 이 참이면 앱이 확인창을 띄운다
- `http`·`https`·`intent`·`javascript`·`vbscript`·`file`·`content`·`data`·`about`·`blob`·`jar` 는 스킴 목록에 넣을 수 없다(`AppLinkPolicy.FORBIDDEN_SCHEMES`)

**`intent://` 의 위험과 다듬기.** `intent:` 주소는 다른 앱의 임의 화면(컴포넌트)·셀렉터·실행 플래그·추가 값을 지정할 수 있다. 내보내지 않은 화면을 직접 겨누거나,
URI 권한 부여 플래그로 앱의 파일 접근을 넘기거나, 결제·송금 화면을 값을 채운 채 여는 데 쓰일 수 있다. 그래서 `allowIntentUri = true` 로 따로 켜야 하고,
켜도 기본 실행기는 이렇게 다듬어 연다.

| 다듬기 | 이유 |
|---|---|
| `component`·`selector` 를 지운다 | 내보내지 않은 화면·다른 앱 화면을 직접 겨누지 못하게 |
| `CATEGORY_BROWSABLE` 을 붙인다 | 웹 링크로 열 수 있다고 선언한 화면만 받는다(브라우저와 같은 조건) |
| URI 의 `launchFlags` 를 지운다 | `FLAG_GRANT_*_URI_PERMISSION` 으로 권한을 넘기지 못하게 |
| action 은 VIEW 만 받는다 | `CALL`·`SEND` 같은 동작을 링크로 시작하지 못하게 |
| 안쪽 `scheme=` 은 http·https 또는 허용한 스킴만 | `intent:` 를 우회로로 막아 둔 스킴(`file` 등)을 열지 못하게 |

남는 위험: `package=` 가 가리키는 앱과 `S.키=값` 추가 값은 그대로 그 앱에 간다 — 그 앱의 화면이 값을 믿고 동작하면(결제 금액 등) 사용자 확인 말고는
막을 길이 없다. 그 앱이 없으면 `NoHandler` 다(스토어로 보내지 않는다). 필요한 결제·앱 스킴이 정해져 있으면 `intent:` 를 켜지 말고 그 스킴만 허용한다.

`QrActions.all(result)` 는 대표 동작과 복사 동작을 함께 준다(Wi-Fi·OTP 는 원문 대신 비밀 복사). 결과는 `Started`·`Copied`·`WifiSettingsOpened`·
`NoHandler`(처리 앱 없음 — 실패 아님)·`Failed(ScannerException)` 이다. 동작을 바꾸려면 `QrActionExecutor` 를 구현하거나 `default()` 를 감싼다.

### 사진에서 읽기 — `BitmapCodeReader`

```kotlin
val bitmap = decodeSampled(uri, maxDimension = BitmapCodeReader.RECOMMENDED_MAX_DIMENSION)   // 앱이 줄여 읽는다(2048 권장)
val results: List<ScanResult> = BitmapCodeReader.read(context, bitmap, formats = CodeFormat.QR_ONLY)   // suspend
```

카메라 스캐너와 같은 엔진(기본 ML Kit, `engine = ScanEngines.zxing(tryHarder = true)` 로 바꾼다)으로 읽어 같은 코드는 두 경로에서 같은 원문이다.
기본 디스패처에서 돌고 취소하면 엔진 대기를 끊는다. 코드가 없으면 빈 목록, 엔진 실패는 `ScannerException` 이다. 하드웨어 비트맵은 복사해 읽는다.
너무 줄이면(1024 등) 사진 속 촘촘한 QR 의 모듈이 뭉개져 못 읽는다.

### 비트맵으로 그리기 — `QrBitmaps`

```kotlin
val bitmap = QrBitmaps.render(QrCode.encode(text), 768, colors = CodeColors(fg, bg))
val withLogo = QrBitmaps.render(QrCode.encodeWithLogo(text), 768, logo = logoBitmap, colors = CodeColors(fg, bg))
```

`qr` 의 `CodeImages` 와 같은 규칙(정확한 크기·정수 배·가운데 정렬·대비 검사·로고 합성)으로 `ARGB_8888` 비트맵을 만든다. 백그라운드에서 부른다.

### 직접 그리기 — `ScannerState`

```kotlin
val state = rememberScannerState(ScanMode.Multi(), ScannerOptions(formats = CodeFormat.ALL))
Box {
    CodeScannerPreview(state, Modifier.fillMaxSize())      // 미리보기와 카메라 수명만
    val codes by state.codes.collectAsState()            // 뷰 좌표의 박스, 추적 번호, 고정 여부
    codes.forEach { MyChip(it) }
}
state.setTorchEnabled(true); state.setZoomRatio(2f); state.focusAt(x, y); state.setPaused(true)
if (state.cameraStatus.value is CameraStatus.Error) state.retry()   // 일시적 실패(다른 앱이 카메라 점유) 뒤 다시 연다
```

`rememberScannerState` 는 `mode`·`options` 가 바뀌면 새 상태(새 카메라 세션)를 만든다. 단 엔진 팩토리(`engine`)만 다르면 같은 상태다 —
`ScannerOptions(engine = { ctx, f -> MyEngine(ctx, threshold) })` 처럼 람다를 인라인으로 넘겨도 재구성마다 카메라가 다시 열리지 않고,
새 팩토리는 다음 카메라 세션부터 쓴다. `MultiLock.Custom` 의 함수만 다른 모드도 같은 상태다(새 함수는 다음 프레임부터). 그 밖의 값은 재구성마다 같은 값이면 된다(`data class` 라 값으로 비교한다).
`setPaused(false)` 로 재개하면 추적·고정은 새로 쌓고, 자동 넘기기의 같은 원문 반복 창은 이어진다 — 넘기기 → 멈춤 → 결과 창 닫기 → 재개에서
화면에 남은 같은 코드를 곧바로 다시 넘기지 않는다.

`CodeScanner` 에 같은 `state` 를 넘기면 기본 오버레이를 쓰면서 플래시·확대·고정을 밖에서 조작할 수 있다. 박스·칩만 바꾸려면 슬롯을 준다(위 「오버레이 바꾸기·숨기기」).

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
implementation("net.plumt.qr:qr-android:0.1.0") { exclude(group = "com.google.mlkit") }
CodeScanner(..., options = ScannerOptions(engine = ScanEngines.zxing()))
```

ML Kit 을 뺀 채 `mlKit()` 를 쓰면 조용히 다른 엔진으로 바꾸지 않고 `CameraStatus.Error(ScannerException)` 로 알린다.
다른 인식기를 쓰려면 `ScanEngine`(프레임 → `Detection` 목록, 바로 세운 이미지 좌표)과 `ScanEngine.Factory` 를 구현해 `engine` 에 넣는다.

### 같은 카메라로 다른 분석 함께 돌리기 — `FrameListener`

스캐너가 본 프레임을 앱도 받는다. 코드를 찾는 동안 같은 화면에서 글자 인식 같은 다른 분석을 돌릴 때 쓴다. 카메라를 하나 더 열지 않는다.

```kotlin
CodeScanner(
    mode = ScanMode.Single(autoDeliver = true),
    onCodeTap = { result -> /* 코드 */ },
    onFrame = { frame, detections ->
        if (detections.isEmpty() && textGate.tryEnter()) {
            val luma = frame.luminance           // 이 안에서만 읽는다. 받은 배열은 계속 써도 된다
            textExecutor.execute { recognizeText(luma, frame.rowStride, frame.width, frame.height, frame.rotationDegrees) }
        }
    },
)
```

- 분석 스레드에서, 엔진이 그 프레임을 본 직후 불린다. 돌아올 때까지 다음 프레임이 기다리므로 무거운 일은 다른 스레드로 넘긴다
- `luminance` 는 그 호출 안에서만 읽을 수 있다. 받은 배열은 프레임마다 새로 만든 복사본이라 넘겨서 써도 된다
- 일시정지 중이거나 엔진이 실패한 프레임은 오지 않는다. 수신자가 던진 예외는 분석을 멈추지 않고 `onError` 로 한 번 온다
- `CodeScanner` 없이 상태만 쓸 때는 `ScannerState.setFrameListener(listener)` 로 걸고 `null` 로 뗀다

### 권한·매니페스트

- **`CAMERA` 권한을 선언하지 않는다.** 앱이 선언하고 요청한다 — 무엇이 설치 동의 목록에 뜨는지는 앱이 정한다. 권한이 없으면 `cameraStatus` 가
  `PermissionRequired` 이고 `CodeScanner` 는 `permissionContent` 를 그린다. `rememberScannerState` 가 화면이 다시 보일 때(ON_RESUME — 시스템 권한
  창이 닫힐 때 포함) 권한을 다시 읽으므로, 기본 `CodeScanner` 는 허용하고 돌아오면 앱이 아무것도 부르지 않아도 미리보기로 바뀐다.
  요청 결과 콜백에서 바로 반영하고 싶으면 `state.refreshPermission()` 을 부른다
- `<uses-feature android:name="android.hardware.camera" android:required="false"/>`(autofocus 도 같다)를 선언한다. 앱이 `CAMERA` 를 선언하면
  Play 가 카메라를 필수 하드웨어로 추정하는데, 이 스캐너는 카메라가 없는 기기를 `CameraStatus.NoCamera` 로 처리하므로 필수로 두지 않는다.
  카메라가 꼭 있어야 하는 앱은 자기 매니페스트에 `required="true"` 를 적으면 그쪽이 이긴다

### 카메라·스레드·수명

- 미리보기와 분석은 같은 4:3 비율로 잡는다(박스 좌표가 맞으려면 두 스트림의 비율이 같아야 한다). 분석은 `STRATEGY_KEEP_ONLY_LATEST` — 엔진이 느리면 프레임을 건너뛴다
- 엔진은 전용 단일 스레드(`plumt-qr-analysis`)에서 돈다 — 메인 스레드에서 인식하지 않는다. 프레임은 엔진이 끝나면 바로 반납한다. ML Kit 은 프레임당 2초를 넘기면 `ScannerException`
- ZXing 엔진은 1차원 형식을 찾을 때 버퍼를 90도 돌린 사본을 1차원 형식만으로 한 번 더 읽는다 — 센서 버퍼는 세로 화면과 90도 돌아 있어, 화면 가로로 든 바코드가
  버퍼 안에서는 행과 나란히 눕기 때문이다(그만큼 1차원 형식의 프레임당 비용이 는다)
- 세션이 끝나면(분석기 닫힘) 엔진 안에 있던 프레임의 결과도 버린다
- 컴포저블이 사라지거나 수명주기 소유자가 바뀌면 이 미리보기의 use case 둘만 푼다(`unbindAll` 을 쓰지 않는다), 분석기를 떼고, 돌던 프레임이 끝난 뒤 엔진을 닫는다
- 좌표 변환: 버퍼 → 바로 세운 이미지(회전 0·90·180·270) → 뷰(`FILL`/`FIT` 배율, 가운데 정렬, 전면 반전). ML Kit 은 회전을 적용한 좌표를 주므로 그대로, ZXing 은 버퍼 좌표를 돌린다.
  계산식은 유닛 테스트가 독립 모형과 대조한다. 전제는 미리보기·분석 스트림의 비율·화각이 같다는 것이다 — 기기가 한쪽만 다른 비율로 잡거나, 화면 회전을 스스로
  처리하는 Activity(`configChanges=orientation`)에서 회전한 뒤에는 박스가 어긋날 수 있다(실기기 확인 항목)

### 위협 모델

| 위협 | 대응 |
|---|---|
| 모르는 QR 이 바로 실행됨(악성 링크·Wi-Fi·결제 앱 링크) | SDK 는 아무것도 자동 실행하지 않는다. `autoDeliver` 도 콜백만 부른다. 실행은 앱이 확인 뒤 `QrActionExecutor` 로. 기본 실행기는 http·https 만 연다(`intent:`·`javascript:`·앱 스킴 거절) |
| 앱 링크로 다른 앱 화면을 값을 채워 엶 | 기본은 열지 않는다. 앱이 `AppLinkPolicy` 로 고른 스킴만, `requiresConfirmation` 확인 뒤 연다. `intent:` 는 따로 켜야 하고 컴포넌트·셀렉터·권한 플래그를 지우고 `BROWSABLE` 화면만 받는다(위 표). 추가 값·대상 패키지는 남는다 |
| 읽히지 않는 색으로 만든 QR | `CodeColors` 가 대비 3 미만·밝기 반전을 만들 때 막는다 |
| 내용으로 인식을 멈추게 함(공백 수천 칸 vCard 등) | `parse` 는 선형 시간이고 8192자 넘는 원문은 풀지 않는다. ML Kit 대기는 프레임당 2초 상한 |
| 숫자 IP 로 사설망 주소 숨기기(`https://2130706433/`) | 브라우저와 같은 규칙으로 IPv4 로 읽어 `127.0.0.1` 과 `IP_ADDRESS` 경고를 보인다 |
| 비슷한 글자로 만든 가짜 도메인, `https://bank.example.com@evil.com` | `OpenUrl.host` 는 IDN 을 퓨니코드로 바꾼 실제 호스트다. 사용자 정보·국제화 도메인·IP·http 를 `warnings` 로 알린다. 기본 확인 대화상자가 실제 호스트를 보인다. `\` 는 호스트의 끝으로 본다(브라우저가 `/` 로 다룬다) |
| 비밀 노출(화면·로그) | 칩 요약·확인창 본문·`TrackedCode.toString`·`QrAction.toString` 에 Wi-Fi 비밀번호·OTP 비밀·주소의 `사용자:비밀번호@` 가 없다. 예외 메시지에 원문을 넣지 않는다. 복사하는 비밀은 클립보드 민감 표시(Android 13+ 가 미리보기를 가린다) |
| 처리 앱이 비밀을 받음 | `AddOtp` 는 `otpauth://` 를 사용자가 고른 인증 앱에 넘긴다 — 비밀이 그 앱으로 가는 것이 목적이다 |

`qr` 의 `ScanResult`·`QrPayload` 의 `toString` 도 원문을 담지 않는다(비밀이 들 수 있는 `Text`·주소의 사용자 정보는 가린다). 그래도 `text`·`password`·`secret` 속성을 직접 로그에 찍지 않는다.

### 오류

인자가 틀리면 `IllegalArgumentException`, 그 밖의 실패는 `ScannerException`(원인은 `cause`) 하나다. 카메라 실패는 `CameraStatus.Error`, 엔진 실패는
`state.error`·`onError`(세션마다 첫 한 건), 실행 실패는 `QrActionResult.Failed`. 자세한 목록은 [ERRORS.md](ERRORS.md#qr-android).

### ProGuard·R8

이 SDK 코드는 리플렉션·직렬화를 쓰지 않는다. ML Kit·CameraX 는 각자의 consumer 규칙을 AAR 에 싣고, ZXing core 는 규칙이 필요 없다.
예외 하나로, ML Kit 이 매니페스트에 적힌 등록자(`*Registrar`)를 리플렉션으로 만드는데 ML Kit 이 끌어오는 firebase-components 16.1.0 의
규칙은 R8 full mode(AGP 8+ 기본)에서 그 기본 생성자를 지킨다는 보장이 없다. 그래서 등록자 생성자만 보존하는 규칙 한 줄을 이 SDK 의
consumer 규칙에 싣는다(firebase-components 19.0.0 이 싣는 규칙과 같다).
라이브러리 전체 보존(`-keep`)이나 `-dontwarn com.google.android.gms.**` 를 두지 않는다 — 소비 앱의 축소를 막고 앱 자신의 경고까지 숨기기 때문이다. 앱이 따로 할 일은 없다.

## 버전

모듈마다 [SemVer](https://semver.org/lang/ko/) 를 따로 쓰고 태그는 `<모듈>-v<버전>`(예: `qr-v0.0.1`, `qr-android-v0.0.1`)이다.
1.0.0 전까지는 마이너 버전에서도 공개 API 가 바뀔 수 있다. 공개 API 는 `<모듈>/api/*.api` 로 고정하고 CI 가 대조한다.
빌드는 모듈마다 라인 커버리지 90% 미만이면 실패한다(Kover). `qr-android` 는 유닛 테스트로 돌릴 수 없는 접착 코드(@Composable 함수,
CameraX 바인딩, ML Kit 네이티브 인식기 호출)만 측정에서 빼고, 이유를 `qr-android/build.gradle.kts` 에 적었다. 판단 로직(좌표 변환·추적·고정·자동 넘기기·
엔진 결과 변환·실행 Intent)은 모두 측정 대상이다.

## 라이선스

[Apache License 2.0](LICENSE)
