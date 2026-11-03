# 오류와 실패 신호

라이브러리가 던지는 예외와, 예외 대신 값으로 알리는 실패 신호를 모듈별로 적는다.

## qr

예외는 두 종류뿐이다. 인자가 틀리면 `IllegalArgumentException`, 인자는 맞는데 엔진이 일을 끝내지 못하면
`QrCodeException`(원인은 `cause`)이다. "없음"·"모름" 은 예외가 아니라 값(null·빈 목록·false·`QrPayload.Text`)으로 알린다.

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

### 예외 — 만들기(`QrCode.encode`·`Barcode.encode`)

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | 내용이 비었을 때 | 내용을 받는다 |
| `IllegalArgumentException` | `QrCodeOptions.charset` 으로 내용을 나타낼 수 없을 때 | UTF-8 을 쓴다 |
| `IllegalArgumentException` | 내용이 형식의 최대 용량을 넘을 때(QR 은 오류 정정 단계별 버전 40 용량) | 내용을 줄이거나 오류 정정 단계를 낮춘다 |
| `IllegalArgumentException` | 1차원 형식이 받지 않는 내용 — `Barcode` 문서의 표(자릿수·문자 집합·체크 숫자·80자 상한) | 표에 맞춘다. 상품 번호는 체크 숫자 없이 줘도 된다 |
| `IllegalArgumentException` | `QrCodeOptions.margin` 이 음수, `minVersion` 이 1..40 밖일 때 (생성 시) | 값을 바로잡는다 |
| `IllegalArgumentException` | `BarcodeOptions` 의 값이 음수이거나 `width*height` 가 1억을 넘을 때 (생성 시), 여백 때문에 결과가 1억 칸을 넘을 때 | 크기·여백을 줄인다 |
| `IllegalArgumentException` | `CodeMatrix` 생성 인자가 틀렸을 때, `get`·`row` 좌표가 범위 밖일 때, `scaled` 배율이 1 미만이거나 결과가 너무 클 때 | 인자를 바로잡는다 |
| `QrCodeException` | 엔진이 그 밖의 이유로 만들지 못했을 때 — 원래 예외가 `cause` 다 | 입력값과 함께 이슈로 알린다 |

### 예외 — 읽기(`CodeReader`)

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | 너비·높이가 1 미만, `rowStride < width`, 배열 크기가 `rowStride * (height - 1) + width` 보다 작을 때 | 프레임 정보를 그대로 넘긴다 |
| `IllegalArgumentException` | `ReadOptions.formats` 가 비었을 때 (생성 시) | 찾을 형식을 하나 이상 준다 |
| `QrCodeException` | 엔진이 읽는 도중 예기치 못하게 실패했을 때 — 원래 예외가 `cause` 다 | 그 프레임은 건너뛰고, 이미지와 함께 이슈로 알린다 |

### 값으로 알리는 신호

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `QrPayload.Text` | `parse` 가 형식을 모르거나 입력이 깨졌을 때, 구조로 읽었지만 다시 쓸 수 없는 값일 때(빈 SSID·범위 밖 좌표·틀린 OTP 비밀 등) | 원문을 글로 보인다 |
| `Event.start`·`end` 가 null | 시각이 없거나 해석할 수 없을 때 | 시각 없는 일정으로 다룬다 |
| `EventTime.Local.zone` 이 null | 시간대가 없거나 모르는 `TZID`(Windows 이름 등)일 때 | 보는 사람의 시간대로 해석한다 |
| 요약이 `Wi-Fi`·`OTP` | 비밀이 든 원문을 구조로 읽지 못했거나 이름이 비었을 때 | 그대로 표지로 보인다 |
| `detect` 가 null, `isValid` 가 false | 자릿수·문자·체크 숫자가 맞지 않을 때 | 일반 번호로 다룬다 |
| `read` 가 null, `readAll` 이 빈 목록 | 이미지에 찾는 형식의 코드가 없거나 읽을 수 없을 때 | 다음 프레임을 본다 |
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

### 상태로 알리는 실패

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `CameraStatus.PermissionRequired` | 앱에 `CAMERA` 권한이 없다(실패가 아니다) | 앱이 권한을 요청하고 `refreshPermission()` |
| `CameraStatus.NoCamera` | 요청한 방향(`lens`)의 카메라가 기기에 없다 | 다른 렌즈를 고르거나 사진으로 읽기(`CodeReader`)를 제안한다 |
| `CameraStatus.Error(ScannerException)` | 카메라 제공자를 얻지 못함·바인딩 실패(다른 앱이 카메라 점유 등)·엔진 생성 실패(ML Kit 의존이 빠졌는데 `mlKit()` 를 고름, 초기화 실패) | 원인을 보고하고, 엔진 문제면 `ScanEngines.zxing()` 으로 바꾼다. SDK 는 조용히 다른 엔진으로 바꾸지 않는다 |
| `ScannerState.error`·`onError` | 엔진이 프레임을 처리하다 실패 — 카메라 세션마다 처음 한 건만 온다(같은 원인이 프레임마다 반복되므로). 그 프레임은 건너뛰고 다음 프레임을 본다 | 보고한다. 계속 실패하면 엔진을 바꾼다 |

### 실행 결과 — `QrActionExecutor.execute`

`execute` 는 던지지 않는다.

| 결과 | 언제 | 호출부가 할 일 |
|---|---|---|
| `NoHandler` | 처리할 앱이 기기에 없다(`ActivityNotFoundException`) — 실패가 아니다 | 복사 같은 다른 동작을 제안한다(`QrActions.all`) |
| `WifiSettingsOpened(passwordCopied)` | 시스템 「네트워크 추가」 창을 쓸 수 없다 — API 29 이하, WEP, WPA2-EAP, 빈·32바이트 넘는 SSID, WPA 규격 밖 비밀번호(8..63자 ASCII·64자리 16진수 아님), 제안 API 가 거절 | 사용자에게 설정 화면에서 붙여 넣으라고 안내한다 |
| `Failed(ScannerException)` | 그 밖의 실행 실패 — 보안 예외, `AddOtp` 의 OTP 값이 쓸 수 없는 값(`encode` 실패, `cause` 는 `IllegalArgumentException`) 등 | 원인을 보고한다 |

### 값으로 알리는 신호

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| 코드가 칩에 안 뜸 | 바이너리 QR(UTF-8 이 아님 — ML Kit 이 원문을 주지 않는다), 박스·위치 점이 없는 결과, `FILL` 에서 잘려 보이지 않는 곳의 코드, 뷰 크기를 아직 모름 | 정상이다 |
| `TrackedCode.isStale` | 이번 프레임에서 놓쳤지만 `maxMissedFrames` 안이다 | 흐리게 그린다 |
| `OpenUrl.warnings` 에 `UNREADABLE_HOST` | 호스트가 없거나 도메인 규칙(라벨 63자 등)에 맞지 않는다 — `host` 는 원문 호스트 그대로 | 열기 전에 경고한다 |
| `deliveries` 에서 결과가 사라짐 | 구독자가 없을 때 넘긴 결과는 버린다. 느린 구독자에게는 최근 8개만 남긴다 | `CodeScanner` 를 쓰거나 상태를 만든 즉시 구독한다 |
