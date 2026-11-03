# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## qr

### [Unreleased]

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

## qr-android

### [Unreleased]

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
