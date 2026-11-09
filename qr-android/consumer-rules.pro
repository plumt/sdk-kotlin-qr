# qr-android — 이 모듈을 의존하면 소비 앱의 R8 에 자동 적용된다.
#
# 이 모듈과 qr 모듈의 코드는 리플렉션·직렬화를 쓰지 않는다. ZXing core 는 리더를 직접 생성하고(리플렉션 없음) 규칙이 필요 없다.
# CameraX·ML Kit barcode-scanning 은 proto 필드·native 메서드 보존 규칙을 AAR 에 싣는다.
#
# 예외 하나: ML Kit 은 매니페스트 meta-data 에 적힌 등록자(CommonComponentRegistrar·VisionCommonRegistrar·
# BarcodeRegistrar)를 리플렉션으로 기본 생성자 호출해 만든다. ML Kit 이 끌어오는 firebase-components 16.1.0 의 규칙은
# `-keep class * implements ComponentRegistrar` 뿐이라(생성자 미보존) R8 full mode(AGP 8+ 기본)에서 생성자가 지워지고
# release 에서 "NoSuchMethodException <init> []" → 인식기 생성 실패가 난다. 실기기 release 로 확인했다.
# 그래서 firebase-components 19.0.0 이 고쳐 실은 규칙과 같은 것을 여기 둔다 — 생성자만 보존하므로 축소 대가가 거의 없다.
# 예전의 넓은 규칙(barcode 패키지 전체 보존)은 BarcodeRegistrar 만 우연히 덮었고 mlkit.common·vision.common 등록자는
# 처음부터 덮지 못했다 — 그래서 넓은 규칙을 되살리지 않고 등록자 생성자만 정확히 보존한다.
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar
#
# 넓은 -keep(라이브러리 전체 보존)이나 -dontwarn com.google.android.gms.** 는 두지 않는다 — 소비 앱의 축소를 막아 APK 가 커지고,
# 소비 앱 자신의 누락 경고까지 숨긴다.
