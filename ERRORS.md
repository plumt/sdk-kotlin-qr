# 오류와 실패 신호

라이브러리가 던지는 예외와, 예외 대신 값으로 알리는 실패 신호를 모듈별로 적는다.

## qr

예외는 두 종류뿐이다. 인자가 틀리면 `IllegalArgumentException`, 인자는 맞는데 엔진이 일을 끝내지 못하면
`QrCodeException`(원인은 `cause`)이다. QR 용량 초과만 `IllegalArgumentException` 의 하위 타입 `QrContentTooLongException` 으로 따로 던진다 —
사용자가 내용을 줄여 고칠 입력이라 화면에 알리고 크래시 보고에서 빼기 쉽게 하려는 것이다. "없음"·"모름" 은 예외가 아니라 값(null·빈 목록·false·`QrPayload.Text`)으로 알린다.

예외 메시지에는 필드 이름과 받은 값, 허용 범위가 들어간다. 비밀번호·OTP 비밀은 넣지 않는다.
메시지 문구는 바뀔 수 있으니 분기는 예외 타입으로 한다.

### 예외 — 내용 쓰기(`QrPayload.encode`·`Contact.encodeMeCard`)

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `Url` 이 비었거나 공백이 들었을 때 | 주소를 바로잡는다 |
| `IllegalArgumentException` | `Wifi.ssid` 가 빈 문자열일 때 | SSID 를 받는다 |
| `IllegalArgumentException` | `Contact` 에 이름·소속·전화·이메일이 모두 없을 때, 빈 전화번호·이메일이 들었을 때, `url` 에 줄바꿈이 있을 때 | 입력을 바로잡는다 |
| `IllegalArgumentException` | `Email.address` 가 비었거나 공백·`?`·`&` 가 들었을 때 | 주소를 바로잡는다 |
| `IllegalArgumentException` | `Phone`·`Sms` 번호가 비었을 때, `Sms` 번호에 `:`·`?` 가 들었을 때 | 번호를 바로잡는다 |
| `IllegalArgumentException` | `Event.title` 이 비었을 때, 시각의 연도가 1..9999 밖일 때, 같은 종류의 두 시각에서 끝이 시작보다 앞설 때 | 제목·시각을 바로잡는다 |
| `IllegalArgumentException` | `Geo` 위도가 -90..90, 경도가 -180..180 밖이거나 NaN·무한대일 때, 고도가 유한하지 않을 때 | 좌표를 바로잡는다 |
| `IllegalArgumentException` | `Otp` 계정이 비었을 때, 계정·발급자에 `:` 가 있을 때, 비밀이 base32 가 아닐 때, `digits` 가 6..8 밖, `period` < 1, `counter` < 0 일 때 | 값을 바로잡는다 |
| `IllegalArgumentException` | 퍼센트 인코딩하는 값(이메일 제목·본문·주소, 위치 검색어, OTP 계정·발급자)에 짝 없는 서로게이트가 있을 때 | 문자열을 바로잡는다 |
| `IllegalArgumentException` | `Product.code` 가 비었을 때 | 번호를 받는다 |

`QrPayload.parse` 가 돌려준 구조화된 값에서는 이 예외가 나지 않는다 — 그런 입력은 `Text` 로 돌아온다.

### 예외 — 번호(`BarcodeNumber`)

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `checkDigit`·`withCheckDigit` 의 자릿수가 종류의 자릿수 - 1 이 아니거나 숫자가 아닐 때, UPC-E 번호 체계가 0·1 이 아닐 때, ISBN-13 이 978·979 로 시작하지 않을 때 | 입력을 바로잡는다 |
| `IllegalArgumentException` | 변환 함수의 입력이 그 종류로 올바르지 않을 때(자릿수·체크 숫자) | 먼저 `isValid` 로 거른다 |
| `IllegalArgumentException` | `ean13ToUpcA` 에 0 으로 시작하지 않는 번호, `isbn13ToIsbn10` 에 979 로 시작하는 번호 | 바꿀 수 없는 번호다. 원래 번호를 쓴다 |

### 예외 — 만들기(`QrCode.encode`·`encodeWithLogo`·`pickErrorCorrection`·`Barcode.encode`)

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | 내용이 비었을 때 | 내용을 받는다 |
| `IllegalArgumentException` | `QrCodeOptions.charset` 으로 내용을 나타낼 수 없을 때 | UTF-8 을 쓴다 |
| `QrContentTooLongException` | QR(`Barcode.encode(.., QR_CODE)` 포함) 내용이 오류 정정 단계의 버전 40 용량을 넘을 때. `errorCorrection` 은 들어가지 않은 단계(`pickErrorCorrection` 이면 그중 가장 낮은 단계), `withLogo` 는 로고 자리를 비우는 생성이었는지, `cause` 는 엔진 예외 | 내용을 줄이거나 단계를 낮춘다(`pickErrorCorrection`). `withLogo` 면 로고를 빼거나 줄인다 |
| `IllegalArgumentException` | QR 이 아닌 형식의 내용이 최대 용량을 넘을 때 | 내용을 줄인다 |
| `IllegalArgumentException` | `encodeWithLogo`·`pickErrorCorrection(.., logo)` — 비울 자리(로고 + 패딩)가 단계의 `maxLogoRatio`(L 0·M 15%·Q 20%·H 30%, 한 변 기준)를 넘을 때, 찾기 패턴·형식 정보에 닿을 때(작은 버전), 단계가 L 일 때 | `sizeRatio`·`paddingModules` 를 줄이거나, 단계를 올리거나, `minVersion` 을 올린다 |
| `IllegalArgumentException` | `QrLogoOptions` 의 `sizeRatio` 가 0 초과 0.3 이하가 아니거나(NaN 포함) `paddingModules` 가 0..4 밖, `CellArea` 좌표가 음수·크기 1 미만 (생성 시) | 값을 바로잡는다 |
| `IllegalArgumentException` | 1차원 형식이 받지 않는 내용 — `Barcode` 문서의 표(자릿수·문자 집합·체크 숫자·80자 상한) | 표에 맞춘다. 상품 번호는 체크 숫자 없이 줘도 된다 |
| `IllegalArgumentException` | `QrCodeOptions.margin` 이 음수, `minVersion` 이 1..40 밖일 때 (생성 시) | 값을 바로잡는다 |
| `IllegalArgumentException` | `BarcodeOptions` 의 값이 음수이거나 `width*height` 가 `CodeMatrix.MAX_CELLS`(4096x4096) 를 넘을 때 (생성 시), 여백 때문에 결과가 그 상한을 넘을 때 | 크기·여백을 줄인다 |
| `IllegalArgumentException` | `CodeMatrix` 생성 인자가 틀렸을 때, `get`·`row` 좌표가 범위 밖일 때, `scaled` 배율이 1 미만이거나 결과가 `MAX_CELLS` 를 넘을 때 | 인자를 바로잡는다. 큰 그림은 격자를 그리는 쪽(Canvas 등)에서 키운다 |
| `QrCodeException` | 엔진이 그 밖의 이유로 만들지 못했을 때 — 원래 예외가 `cause` 다 | 입력값과 함께 이슈로 알린다 |

### 예외 — 그리기·색(`CodeImages`·`CodeColors`·`ArgbImage`)

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `CodeColors(checkContrast = true)` — 전경이 배경보다 밝을 때(`ColorReadability.INVERTED`), 대비가 3 미만일 때(`LOW_CONTRAST`). 알파가 있는 색은 흰 바탕에 얹은 색으로 판정한다 (생성 시) | 색을 바꾼다. 어두운 바탕의 밝은 코드를 일부러 그리면 `checkContrast = false` |
| `IllegalArgumentException` | `render` 의 크기가 1 미만이거나 격자보다 작을 때(한 칸 1픽셀 미만), `CodeMatrix.MAX_CELLS` 를 넘을 때 | 격자 너비 이상, 상한 이하로 준다 |
| `IllegalArgumentException` | `ArgbImage` 의 크기가 1 미만, 픽셀 수가 `width*height` 와 다르거나 `MAX_CELLS` 를 넘을 때 (생성 시), `get` 좌표가 범위 밖일 때 | 인자를 바로잡는다. 큰 로고는 줄여서 넘긴다 |

`QrColors`(`contrastRatio`·`relativeLuminance`·`isInverted`·`readability`)는 던지지 않는다.

### 예외 — 읽기(`CodeReader`)

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | 너비·높이가 1 미만, `rowStride < width`, 배열 크기가 `rowStride * (height - 1) + width` 보다 작을 때 | 프레임 정보를 그대로 넘긴다 |
| `IllegalArgumentException` | `ReadOptions.formats` 가 비었을 때 (생성 시) | 찾을 형식을 하나 이상 준다 |
| `QrCodeException` | 엔진이 읽는 도중 예기치 못하게 실패했을 때 — 원래 예외가 `cause` 다 | 그 프레임은 건너뛰고, 이미지와 함께 이슈로 알린다 |

### 값으로 알리는 신호

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `QrPayload.Text` | `parse` 가 형식을 모르거나 입력이 깨졌을 때, 맨 이메일 주소·전화번호(기본은 추정하지 않는다 — `ParseOptions`), 구조로 읽었지만 다시 쓸 수 없는 값일 때(빈 SSID·범위 밖 좌표·틀린 OTP 비밀 등), 원문이 `QrPayload.MAX_PARSE_LENGTH`(8192자) 보다 길 때 | 원문을 글로 보인다 |
| `Event.start`·`end` 가 null | 시각이 없거나 해석할 수 없을 때 | 시각 없는 일정으로 다룬다 |
| `EventTime.Local.zone` 이 null | 시간대가 없거나 모르는 `TZID`(Windows 이름 등)일 때 | 보는 사람의 시간대로 해석한다 |
| 요약이 `Wi-Fi`·`OTP` | 비밀이 든 원문을 구조로 읽지 못했거나 이름이 비었을 때 | 그대로 표지로 보인다 |
| `CodeFormat.fromName` 이 null | 모르는 형식 이름(손상된 기록 등) — `valueOf` 와 달리 던지지 않는다 | 형식 없는 기록으로 다룬다 |
| `detect` 가 null, `isValid` 가 false | 자릿수·문자·체크 숫자가 맞지 않을 때 | 일반 번호로 다룬다 |
| `read` 가 null, `readAll` 이 빈 목록 | 이미지에 찾는 형식의 코드가 없거나 읽을 수 없을 때. 90도 돌아간 1차원 바코드는 `tryHarder` 일 때만 읽는다 | 다음 프레임을 본다. 사진이면 `tryHarder = true` |
| `readAll` 결과가 기대보다 적음 | 원문이 같은 코드가 여러 개일 때(하나만 남긴다) | 위치가 필요하면 영역을 나눠 `read` 한다 |

## qr-android

예외는 두 종류뿐이다. 인자가 틀리면 `IllegalArgumentException`, 그 밖의 실패(카메라·엔진·실행)는 `ScannerException`(원인은 `cause`)이다.
카메라·엔진 실패는 던지지 않고 상태(`CameraStatus.Error`·`ScannerState.error`)로, 실행 실패는 `QrActionResult.Failed` 로 알린다.
메시지에 코드 원문·비밀번호·OTP 비밀을 넣지 않는다. 분기는 타입으로 한다.

### 예외 — 인자

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `ScannerOptions.formats` 가 비었을 때 (생성 시) | 형식을 하나 이상 준다 |
| `IllegalArgumentException` | `TrackingOptions` — `maxMissedFrames` 가 0..1000 밖, `smoothing` 이 0 이상 1 미만이 아님, `iouThreshold` 가 0..1 밖 (생성 시, NaN 포함) | 값을 바로잡는다 |
| `IllegalArgumentException` | `LockOptions` — `hysteresis` 가 0..1 밖, `switchFrames` 가 1..1000 밖 (생성 시) | 값을 바로잡는다 |
| `IllegalArgumentException` | `AutoDeliverOptions` 의 시간이 음수 (생성 시) | 0 이상을 준다 |
| `IllegalArgumentException` | `ScannerState.setZoomRatio`·`zoomBy` 에 유한한 양수가 아닌 값, `focusAt` 에 유한하지 않은 좌표 | 값을 거른다 |
| `IllegalArgumentException` | `ScanFrame(...)` — 크기 1 미만, `rowStride < width`, 배열이 `rowStride*(height-1)+width` 보다 작음, 회전이 0·90·180·270 이 아님 | 프레임 정보를 그대로 넘긴다 |
| `IllegalArgumentException` | `ScanEngine.Factory.create` 에 빈 형식 집합 | 형식을 준다 |
| `IllegalArgumentException` | `ScannerDefaults.summary` 의 `maxLength` 가 1 미만 | 1 이상을 준다 |
| `IllegalArgumentException` | `AppLinkPolicy.allow` 의 스킴이 문법(영문 시작, 영문·숫자·`+`·`.`·`-`)에 맞지 않거나 `FORBIDDEN_SCHEMES`(http·https·intent·javascript·file·content·data 등)일 때. `intent` 는 `allowIntentUri = true` 로 켠다 | 스킴 이름만(`://` 없이) 준다 |
| `IllegalArgumentException` | `QrBitmaps.render` 의 크기·색이 `CodeImages`·`CodeColors` 의 조건을 어길 때, 로고·입력 비트맵이 재활용됐거나 `MAX_CELLS` 픽셀을 넘을 때 | 인자를 바로잡는다 |
| `IllegalArgumentException` | `BitmapCodeReader.read` 에 빈 `formats`·재활용된 비트맵 | 형식을 주고, 읽는 동안 비트맵을 재활용하지 않는다 |

### 예외 — 사진 읽기(`BitmapCodeReader.read`)

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `ScannerException` | 엔진을 만들지 못했을 때(ML Kit 의존을 뺐는데 `mlKit()`, 초기화 실패 — 엔진이 던진 `ScannerException` 은 그대로, 그 밖의 런타임 예외는 감싸 `cause` 에) | 원인을 보고한다. 엔진을 바꾼다(`ScanEngines.zxing(tryHarder = true)`) |
| `ScannerException` | 엔진이 읽다 실패했을 때(ML Kit 2초 상한 포함) — 원인은 `cause` | 원인을 보고한다 |
| `ScannerException` | `FrameListener.onFrame` 이 런타임 예외를 던졌을 때 — 원인은 `cause`. 분석은 계속된다 | 수신자 코드를 고친다. 엔진 오류와 합쳐 카메라 세션마다 처음 한 건만 온다 |
| `CancellationException` | 부른 코루틴이 취소됐을 때 — 엔진 대기를 끊고 엔진을 닫는다 | 처리하지 않는다 |

코드가 없으면 예외가 아니라 빈 목록이다.

### 상태로 알리는 실패

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `CameraStatus.PermissionRequired` | 앱에 `CAMERA` 권한이 없다(실패가 아니다) | 앱이 권한을 요청한다. 허용하고 화면이 다시 보이면(ON_RESUME) 상태가 스스로 바뀐다 — 바로 반영하려면 `refreshPermission()` |
| `CameraStatus.NoCamera` | 요청한 방향(`lens`)의 카메라가 기기에 없다 | 다른 렌즈를 고르거나 사진으로 읽기(`CodeReader`)를 제안한다. 외장 카메라를 붙였다면 `retry()` |
| `CameraStatus.Error(ScannerException)` | 카메라 제공자를 얻지 못함·바인딩 실패(다른 앱이 카메라 점유 등)·엔진 생성 실패(ML Kit 의존이 빠졌는데 `mlKit()` 를 고름, 초기화 실패. release 에서만 나고 logcat 에 `ComponentDiscovery … Registrar … NoSuchMethodException` 이 있으면 앱의 R8 설정이 이 SDK 의 consumer 규칙을 빼고 있는지 본다) | 원인을 보고한다. 일시적 실패(점유)면 `ScannerState.retry()` 로 다시 연다. 엔진 문제면 `ScanEngines.zxing()` 으로 바꾼다. SDK 는 조용히 다른 엔진으로 바꾸지 않는다 |
| `ScannerState.error`·`onError` | 엔진이 프레임을 처리하다 실패(ML Kit 이 2초 안에 끝내지 못한 것 포함) — 카메라 세션마다 처음 한 건만 온다(같은 원인이 프레임마다 반복되므로). 그 프레임은 건너뛰고 다음 프레임을 본다 | 보고한다. 계속 실패하면 엔진을 바꾼다 |

### 실행 결과 — `QrActionExecutor.execute`

`execute` 는 던지지 않는다.

| 결과 | 언제 | 호출부가 할 일 |
|---|---|---|
| `NoHandler` | 처리할 앱이 기기에 없다(`ActivityNotFoundException`) — 실패가 아니다 | 복사 같은 다른 동작을 제안한다(`QrActions.all`) |
| `WifiSettingsOpened(passwordCopied)` | 시스템 「네트워크 추가」 창을 쓸 수 없다 — API 29 이하, WEP, WPA2-EAP, 빈·32바이트 넘는 SSID, WPA 규격 밖 비밀번호(8..63자 ASCII·64자리 16진수 아님), 제안 API 가 거절 | 사용자에게 설정 화면에서 붙여 넣으라고 안내한다 |
| `Failed(ScannerException)` | `OpenUrl` 의 주소가 http·https 가 아니고 실행기의 `AppLinkPolicy` 가 그 스킴을 허용하지 않을 때(기본 실행기는 언제나 — `intent:`·`javascript:`·앱 스킴. `cause` 는 `IllegalArgumentException`, 아무것도 띄우지 않는다) | 열지 않는다. 필요하면 복사(`CopyText`)를 제안한다 |
| `Failed(ScannerException)` | 허용한 `intent:` 를 다듬을 수 없을 때 — 문법 오류(`cause` 의 `cause` 가 `URISyntaxException`), action 이 VIEW 가 아님, 안쪽 `scheme=` 이 http·https·허용 목록 밖 | 열지 않는다 |
| `Failed(ScannerException)` | 그 밖의 실행 실패 — 보안 예외, `AddOtp` 의 OTP 값이 쓸 수 없는 값(`encode` 실패, `cause` 는 `IllegalArgumentException`) 등 | 원인을 보고한다 |

### 값으로 알리는 신호

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| 코드가 칩에 안 뜸 | 바이너리 QR(UTF-8 이 아님 — ML Kit 이 원문을 주지 않는다), 박스·위치 점이 없는 결과, `FILL` 에서 잘려 보이지 않는 곳의 코드, 뷰 크기를 아직 모름 | 정상이다 |
| `TrackedCode.isStale` | 이번 프레임에서 놓쳤지만 `maxMissedFrames` 안이다 | 흐리게 그린다 |
| `OpenUrl.warnings` 에 `UNREADABLE_HOST` | 호스트가 없거나 도메인 규칙(라벨 63자 등)에 맞지 않거나, 숫자로 끝나는데 IPv4 로 읽히지 않는다(`256.1.1.1`·`example.123`) — `host` 는 원문 호스트 그대로 | 열기 전에 경고한다 |
| `OpenUrl.warnings` 에 `IP_ADDRESS` | 호스트가 IP 다. 브라우저처럼 `2130706433`·`0x7f000001`·`127.1` 같은 숫자 표기도 IPv4 로 읽어 `host` 를 `127.0.0.1` 로 보인다 | 열기 전에 경고한다 |
| `OpenUrl.warnings` 에 `NON_WEB_SCHEME` | http·https 가 아닌 주소다 — 기본 실행기는 `Failed` 로 거절하고, 기본 확인창은 확인 단추를 보이지 않는다. `AppLinkPolicy` 로 허용한 스킴만 그 정책을 받은 실행기·확인창이 연다 | 열지 않거나, 허용한 앱 링크면 확인 뒤 연다(`QrActions.requiresConfirmation` 이 참) |
| `deliveries` 에서 결과가 사라짐 | 구독자가 없을 때 넘긴 결과는 버린다. 느린 구독자에게는 최근 8개만 남긴다 | `CodeScanner` 를 쓰거나 상태를 만든 즉시 구독한다 |
