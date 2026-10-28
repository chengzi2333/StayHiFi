package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.ICodecService
import com.topjohnwu.superuser.ipc.RootService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task D1：libsu RootService 工程边界与 IPC 契约。
 *  - RootCodecService 必须是 RootService 的（直接）子类——Root 身份只能来自 libsu 的
 *    ContextWrapper 体系，不靠 manifest `<service>` 声明或 android:process（设计 §11.5）；
 *  - ICodecService 五方法集合保持不变：Root 只替换特权通道，不改蓝牙逻辑与 IPC 面（§11.1）。
 */
class RootServiceContractTest {

    @Test
    fun rootCodecServiceExtendsRootService() {
        assertEquals(
            "RootCodecService 必须直接继承 libsu RootService",
            RootService::class.java,
            RootCodecService::class.java.superclass,
        )
    }

    @Test
    fun codecServiceInterfaceMethodsUnchanged() {
        val names = ICodecService::class.java.methods
            .filter { it.declaringClass == ICodecService::class.java }
            .map { it.name }
            .toSet()
        assertEquals(
            "ICodecService 方法集合必须严格等于五方法",
            setOf("dumpDevices", "readConfig", "writeConfig", "dumpBtState", "destroy"),
            names,
        )
    }

    @Test
    fun rootCodecServiceIsPublicWithPublicOnBind() {
        val onBind = RootCodecService::class.java.getDeclaredMethod(
            "onBind", android.content.Intent::class.java)
        assertEquals(
            "onBind 必须声明在 RootCodecService 且为 public（libsu 跨进程反射调用）",
            RootCodecService::class.java,
            onBind.declaringClass,
        )
        assertTrue(
            "onBind 必须 public",
            java.lang.reflect.Modifier.isPublic(onBind.modifiers),
        )
    }
}
