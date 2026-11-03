# qr-android — 이 모듈을 의존하면 소비 앱의 R8 에 자동 적용된다.
#
# 이 모듈 코드는 리플렉션·직렬화를 쓰지 않는다. CameraX·ML Kit·ZXing 은 각자의 consumer 규칙을 싣지만,
# ML Kit 은 모델 로더를 리플렉션으로 찾고 내부 클래스 축소로 초기화가 실패한 사례가 있어 명시적으로 보존한다.
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ZXing 은 리더를 리플렉션으로 만드는 경로가 있다(qr 모듈이 쓴다).
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
