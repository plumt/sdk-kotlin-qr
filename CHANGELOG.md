# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## qr

### [Unreleased]

### [0.0.2] - 미발행

#### 추가
- `QrCode.pickErrorCorrection(text, logo?, options)` — H → Q → M → L 중 내용이 들어가는 가장 높은 오류 정정 단계. 로고를 주면 로고 자리를 허용하는 단계만 본다
- `QrContentTooLongException`(`IllegalArgumentException` 하위) — QR 용량 초과. `errorCorrection`·`withLogo` 와 엔진 원인(`cause`)을 담는다.
  `QrCode.encode`·`encodeWithLogo`·`pickErrorCorrection`·`Barcode.encode(.., QR_CODE)` 가 던진다
- `QrCode.encodeWithLogo` · `QrLogoOptions`(`sizeRatio` 0 초과 0.3 이하, `paddingModules` 0..4) · `LogoQrCode`(`matrix`·`logoArea`·`clearedArea`·`errorCorrection`) · `CellArea` —
  가운데 로고 자리와 패딩을 밝은 칸으로 비운 QR. 비우는 자리는 단계별 상한 `ErrorCorrection.maxLogoRatio`(L 0·M 0.15·Q 0.2·H 0.3) 이하이고 찾기 패턴·형식 정보에 닿지 않아야 한다
- `CodeImages.render` · `ArgbImage` · `CodeColors` — 격자를 정확한 픽셀 크기·색의 ARGB 이미지로(정수 배, 가운데 정렬), 로고 합성(비율 유지, 줄일 때 픽셀 평균, source-over)
- `QrColors`(`relativeLuminance`·`contrastRatio`·`isInverted`·`readability`, `MIN_CONTRAST` 3·`RECOMMENDED_CONTRAST` 4) · `ColorReadability` — WCAG 대비, 알파는 흰 바탕 합성.
  `CodeColors` 는 기본으로 대비 3 미만·밝기 반전을 막는다(`checkContrast = false` 로 끔)
- `ParseOptions`(`inferEmail`·`inferPhone`, `DEFAULT`·`INFER_ALL`) · `QrPayload.parse(text, options)`·`parse(text, format, options)` — 맨 이메일 주소·전화번호 추정(옵트인).
  1차원 바코드는 전화를 추정하지 않는다. 정규식 없이 한 번 훑는다(ASCII 만)
- `CodeFormat.fromName` — 저장된 형식 이름 되읽기(대소문자·`_`·`-`·공백 무시, `PDF417` → `PDF_417`). 모르면 null

#### 변경
- QR 용량 초과 예외가 `IllegalArgumentException` 에서 그 하위 타입 `QrContentTooLongException` 으로 바뀌었다(기존 catch 는 그대로 받는다)
- `ErrorCorrection` 에 `maxLogoRatio` 속성이 생겼다
- 대비·로고 비율·색 값이 들어가는 `IllegalArgumentException` 메시지가 `String.format(Locale.ROOT, …)` 를 쓰지 않는다 —
  플랫폼 ICU 의 루트 로케일 규칙에 따라 Android 버전마다 표기가 달라질 수 있어서다. 표기(`2.995`·`#FF959595`)는 그대로다

#### 그대로 둔 것
- `QrPayload.parse(text)` 의 기본은 맨 이메일·전화번호를 `Text` 로 둔다 — 추정은 사용자가 쓴 글을 다른 종류로 바꾸고(날짜·주문 번호가 전화가 된다)
  누르면 전화·메일 앱이 뜨므로 기본으로 켜지 않는다. 필요한 앱은 `ParseOptions` 로 켠다

### [0.0.1] - 미발행

#### 추가
- `QrPayload` 내용 형식: `Url`·`Text`·`Wifi`(WPA3·WPA2-EAP 포함)·`Contact`(vCard 3.0 쓰기, vCard 2.1/3.0/4.0·MECARD 읽기, `encodeMeCard`)·
  `Email`(`mailto:`·`MATMSG:`·`SMTP:`)·`Phone`·`Sms`·`Event`(`EventTime` 종일·현지·TZID·UTC)·`Geo`(고도·검색어)·`Otp`(TOTP·HOTP)·`Product`
- `QrPayload.parse(text)`·`parse(text, format)` — 던지지 않고, 모르는 형식·깨진 입력은 `Text`
- `summary()`·`QrPayload.summarize()` — Wi-Fi 비밀번호·OTP 비밀을 넣지 않는 한 줄 요약
- `BarcodeNumber` — EAN-13·EAN-8·UPC-A·UPC-E·ITF-14·ISBN-10·ISBN-13 판별·검증·체크 숫자·변환
- `QrCode.encode`(`QrCodeOptions`: 오류 정정·여백·문자셋·최소 버전), `Barcode.encode`(13개 형식, `BarcodeOptions`)
- `CodeMatrix` 불변 흑백 격자 — `scaled`·`toArgb`·`toBooleanArray`·`row`·`toPng`
- `CodeReader.read`·`readAll` — 밝기(`ByteArray`, 행 간격 지정)·ARGB(`IntArray`) 배열, `ReadOptions`(형식·tryHarder·명암 반전·문자셋)
- 오류 정책: 인자 오류는 `IllegalArgumentException`, 엔진 실패는 원인을 담은 `QrCodeException`
- 로그 안전 `toString` — `ScanResult` 는 원문 없이 형식·내용·길이·점만, `Text` 는 `WIFI:`·`otpauth` 로 시작하는 원문을 `****` 로,
  `Url` 은 사용자 정보(`사용자:비밀번호@`)를 `****@` 로 가린다(`Wifi`·`Otp` 는 원래 가린다). 비교·해시는 원문 그대로다
- 해석 시간 상한 — `parse` 는 입력 길이에 선형이고, `QrPayload.MAX_PARSE_LENGTH`(8192자)보다 긴 원문은 `Text` 다
  (줄바꿈 없이 뭉갠 vCard·iCalendar 의 경계를 전방탐색 정규식 대신 한 번 훑기로 찾는다 — 공백 수천 칸짜리 코드 하나로 수 초 걸리던 문제)
- `Url.summary()` 도 사용자 정보를 `****@` 로 가린다(칩·확인창에 비밀번호가 보이지 않게)
- 생성 크기 상한 `CodeMatrix.MAX_CELLS`(4096x4096) — `BarcodeOptions`·여백·`scaled` 결과가 넘으면 `IllegalArgumentException`(Android 힙 OOM 방지)
- `ReadOptions.tryHarder` 가 90도 돌아간 1차원 바코드도 읽는다(밝기 원본이 회전을 지원한다)

#### Android 정규식 차이 반영
- iCalendar 날짜·시각을 ASCII 숫자만 받는다(`\d` → `[0-9]`). Android 정규식은 `\d` 가 유니코드 숫자('٢'·'２')까지 맞춰
  기기에서만 그런 시각을 읽었다. 이제 어디서나 그 시각은 없는 것(`null`)이다
- 사용자 정보 가리기가 앞 공백의 종류(전각·고정 공백 등)와 무관하다 — 정규식 `\s` 의 엔진별 차이로 JVM 에서는 가리지 않던 경우가 있었다

## qr-android

### [Unreleased]

### [0.0.2] - 미발행

#### 추가
- `FrameListener`(`onFrame(frame, detections)`) · `CodeScanner(onFrame = …)` · `ScannerState.setFrameListener` — 스캐너가 분석한 프레임을 앱도 받는다.
  엔진 직후·반납 전에 분석 스레드에서 한 번 불리고, 일시정지·엔진 실패 프레임은 오지 않는다. 수신자 예외는 `ScannerException` 으로 감싸 한 번 알린다
- `AppLinkPolicy`(`NONE` 기본, `allow(schemes, allowIntentUri)`, `allows`, `FORBIDDEN_SCHEMES`) — 앱 링크 옵트인. 고른 스킴만 `ACTION_VIEW` + `CATEGORY_BROWSABLE` 로 연다.
  `intent:` 는 따로 켜야 하고, 켜도 컴포넌트·셀렉터·URI 실행 플래그를 지우고 action VIEW·안쪽 스킴 허용 목록을 검사한다
- `QrActionExecutor.default(options, appLinks)` · `QrActions.primary(payload, appLinks)`·`all(result, appLinks)` · `QrActions.requiresConfirmation(action)`
  (앱 링크와 `NOT_HTTPS` 외 경고가 있는 링크는 참)
- `QrActionConfirmation(action, appLinks, ...)` 오버로드 — 허용한 앱 링크에 「다른 앱을 열까요?」 제목·대상 앱(패키지 또는 스킴)·경고를 보이고 확인 단추를 둔다. 문자열 2개(영어·한국어)
- `BitmapCodeReader.read(context, bitmap, formats, engine)`(suspend) · `RECOMMENDED_MAX_DIMENSION`(2048) — 사진 한 장에서 카메라와 같은 엔진으로 읽는다.
  한 줄씩 밝기로 바꿔(투명은 흰 바탕) 큰 `IntArray` 를 만들지 않고, 하드웨어 비트맵은 복사해 읽는다. 취소하면 엔진 대기를 끊고 엔진을 닫는다
- `QrBitmaps.render`(격자·로고 QR) · `toBitmap` · `toArgbImage` — `qr` 의 `CodeImages` 를 `ARGB_8888` 비트맵으로

#### 그대로 둔 것
- 기본 실행기·기본 확인창은 앱 링크를 열지 않는다(`AppLinkPolicy` 를 받는 오버로드만 연다)

### [0.0.1] - 미발행

#### 추가
- `CodeScanner` Compose 컴포넌트 — CameraX 미리보기 위에 코드 박스와 칩(종류 + 비밀 없는 요약), 탭 → `onCodeTap`, 두 손가락 확대·탭 초점
- `ScanMode.Single` — 가운데에 가장 가까운 코드에 고정(히스테리시스·연속 프레임 조건), `autoDeliver`(안정 시간·같은 번호 1회·같은 원문 반복 창)
- `ScanMode.Multi` — 다중 추적(원문 + IoU·거리로 프레임 사이 같은 번호, 지수 평활, 놓친 프레임 수로 제거)
- `ScannerState`·`rememberScannerState`·`CodeScannerPreview` — 직접 그리는 UI 용 상태(코드·고정·카메라 상태·플래시·확대·멈춤·오류·자동 넘김)
- `ScanEngine`·`ScanEngine.Factory` 확장 지점, `ScanEngines.mlKit()`(기본, 번들 모델)·`ScanEngines.zxing()`(`qr` 의 `CodeReader`)
- 좌표 변환 — 회전 0·90·180·270, `PreviewScaleMode.FILL`·`FIT`, 전면 카메라 반전
- `QrActions`·`QrAction`·`QrActionExecutor.default()` — 내용별 실행(링크는 실제 호스트·경고, Wi-Fi 추가 창/설정, 연락처·일정 삽입, 전화·문자·메일·지도, OTP 인증 앱·비밀 복사, 상품 검색, 복사). 자동 실행 없음
- `QrActionConfirmation` — 실행 전 확인 대화상자(링크의 퓨니코드 호스트·경고)
- 영어·한국어 문자열
- 오류 정책: 인자 오류는 `IllegalArgumentException`, 카메라·엔진·실행 실패는 원인을 담은 `ScannerException`
- 권한 허용 뒤 전환 — `rememberScannerState` 가 ON_RESUME 마다 권한을 다시 읽어, 기본 `CodeScanner` 가 시스템 권한 창에서 허용하고 돌아오면 바로 미리보기로 바뀐다
- `rememberScannerState` 는 엔진 팩토리만 다른 설정을 같은 상태로 본다 — 값을 캡처한 람다를 인라인으로 넘겨도 재구성마다 카메라를 다시 열지 않는다(새 팩토리는 다음 세션부터)
- `ScannerState.retry()` — `CameraStatus.Error`·`NoCamera` 에서 카메라를 다시 묶는다
- ZXing 엔진이 1차원 형식을 찾을 때 버퍼를 90도 돌린 사본을 한 번 더 읽는다 — 세로 화면에서 화면 가로로 든 바코드를 읽는다
- 분석기가 닫힌 뒤에는 엔진 안에 있던 프레임의 결과도 버린다(세션 종료 뒤 옛 박스가 다시 실리지 않게, 파이프라인 동시 접근 방지)
- 멈춤 뒤 재개해도 자동 넘기기의 같은 원문 반복 창을 잇는다(재개 직후 같은 코드를 다시 넘기지 않게)
- ML Kit 처리 대기 상한 2초 — 넘으면 `ScannerException`
- `QrAction.OpenUrl` 은 `QrActions.openUrl` 로만 만든다(호스트·경고를 밖에서 넣을 수 없다). 스킴이 없으면 `https://` 를 붙이고,
  http·https 가 아니면 `UrlWarning.NON_WEB_SCHEME` — 기본 실행기는 열지 않고(`Failed`), 기본 확인창은 확인 단추를 숨긴다
- `UrlWarning.IP_ADDRESS` 를 WHATWG URL 규칙으로 판정 — `2130706433`·`0x7f000001`·`127.1`·`0177.0.0.1` 을 `127.0.0.1` 로 보인다
- 지도 Intent 좌표를 지수 표기 없이(`0.00001`), 라벨의 괄호는 인코딩해 보낸다
- `ScannerDefaults.actionDetail`·기본 확인창 본문 — 번호·받는 사람·네트워크 이름 등 실제로 다룰 값을 보인다(비밀 제외)
- `consumer-rules.pro` 최소화 — ML Kit·ZXing 전체 보존과 `-dontwarn com.google.android.gms.**` 를 뺐다(각 라이브러리가 자체 규칙을 싣는다)

#### 실기기 검증 반영
- 수정: R8 release 에서 ML Kit 인식기를 만들지 못하던 문제(`CameraStatus.Error`, logcat `ComponentDiscovery … NoSuchMethodException <init> []`).
  ML Kit 이 끌어오는 firebase-components 16.1.0 의 규칙이 등록자(`*Registrar`) 클래스만 보존하고 기본 생성자는 보존하지 않아
  R8 full mode 가 생성자를 지웠다. `consumer-rules.pro` 에 `-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }`
  (firebase-components 19.0.0 의 공식 규칙과 같음)를 넣었다. 넓은 `-keep` 은 되살리지 않는다

#### Android 정규식 차이 반영
- URL 의 콜론 뒤 포트를 ASCII 숫자로만 본다(`\d` → `[0-9]`). `example.com:８０８０/x` 는 어디서나 스킴 `example.com` → `NON_WEB_SCHEME` 이다
- `ScannerDefaults.summary` 의 공백 접기를 명시 집합으로 바꿨다 — ASCII 공백·제어 공백과 줄 구분자(U+0085·U+2028·U+2029)만 접고,
  전각 공백(U+3000)·고정 공백(U+00A0)은 내용으로 둔다(이전에는 Android 에서만 접혔다)
