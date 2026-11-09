package net.plumt.qr.scanner

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * R8 release 에서 ML Kit 이 죽지 않도록 consumer 규칙을 고정한다.
 *
 * ML Kit 은 매니페스트 meta-data(`com.google.firebase.components:<클래스>`)에 적힌 등록자(*Registrar)를
 * 리플렉션으로 `newInstance()` 한다. 의존으로 따라오는 firebase-components 16.1.0 의 규칙은
 * `-keep class * implements ComponentRegistrar` 뿐이라 R8 full mode(AGP 8+ 기본)에서 기본 생성자가 지워졌고,
 * 실기기 release 에서 `NoSuchMethodException <init> []` → 인식기 생성 실패가 났다.
 */
@RunWith(RobolectricTestRunner::class)
class ConsumerRulesTest {

    private val rules = File("consumer-rules.pro").readText()

    @Test
    fun `consumer 규칙이 등록자의 기본 생성자를 보존한다`() {
        val active = rules.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val rule = Regex("-keep class \\* implements com\\.google\\.firebase\\.components\\.ComponentRegistrar \\{ ?(void )?<init>\\(\\); ?\\}")
        assertTrue("등록자 생성자 keep 규칙이 없다:\n$rules", rule.containsMatchIn(active))
    }

    @Test
    fun `매니페스트의 모든 ML Kit 등록자가 규칙이 덮는 형태다`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = ComponentName(context, "com.google.mlkit.common.internal.MlKitComponentDiscoveryService")
        val meta = context.packageManager.getServiceInfo(service, PackageManager.GET_META_DATA).metaData
        val prefix = "com.google.firebase.components:"
        val registrars = meta.keySet().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        // 실기기에서 실패했던 세 등록자가 모두 있어야 한다(이 목록이 바뀌면 규칙을 다시 확인한다)
        listOf(
            "com.google.mlkit.common.internal.CommonComponentRegistrar",
            "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
            "com.google.mlkit.vision.barcode.internal.BarcodeRegistrar",
        ).forEach { assertTrue("$it 가 매니페스트에 없다: $registrars", it in registrars) }

        val registrarInterface = Class.forName("com.google.firebase.components.ComponentRegistrar")
        registrars.forEach { name ->
            val type = Class.forName(name)
            assertTrue("$name 는 ComponentRegistrar 가 아니다 — 규칙이 덮지 못한다", registrarInterface.isAssignableFrom(type))
            assertTrue("$name 에 기본 생성자가 없다", type.constructors.any { it.parameterCount == 0 })
        }
    }
}
