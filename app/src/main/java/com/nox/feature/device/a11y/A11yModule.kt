package com.nox.feature.device.a11y

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed 模块入口。
 *
 * 实现依据 `io.github.libxposed:api:102.0.0` 的真实接口（已用 javap 核对，不是照抄别人的代码）：
 *
 * ```
 * XposedModuleInterface.onSystemServerStarting(SystemServerStartingParam) -> ClassLoader
 * XposedInterface.hook(Executable) -> HookBuilder
 * HookBuilder.intercept(Hooker) -> HookHandle
 * Chain.getThisObject() / proceed()
 * XposedInterfaceWrapper.log(int, String, String)
 * ```
 *
 * ## 挂载点为什么选 `SystemServer.startOtherServices`
 *
 * 这个时间点在「系统服务基本启动完成、用户还看不到桌面」之间：
 * - 太早：`Settings.Secure` 还读不到（ContentResolver 未就绪）
 * - 太晚：错过开机后第一次纠正窗口
 *
 * ## 安全底线（三条，缺一不可）
 *
 * 1. **异常绝不外传**：所有 hook 逻辑包在 runCatching 里。system_server 的
 *    未捕获异常 = 整机崩溃重启循环，比「保活失效」严重得多。
 * 2. **原方法异常照常抛**：`chain.proceed()` 抛出的异常不能被我吞掉，
 *    否则会掩盖系统原有的启动错误。
 * 3. **可一键停用**，不需要卸载模块或进 LSPosed 管理器：
 *    ```
 *    adb shell settings put global aigo_a11y_enforcement 0
 *    ```
 *
 * ## 为什么不做 APK 签名钉扎
 *
 * 同类实现（Eta）做钉扎，是因为它的控制通道走**跨进程广播**，任何应用都能发请求。
 * 本模块没有控制通道：开关是 global 设置，写它需要 `WRITE_SECURE_SETTINGS`，
 * 普通应用拿不到；能改的只有 adb 或 root，那已经是完全信任的环境。
 * 多一层钉扎只会让换签名时把自己锁在外面。
 */
class A11yModule : XposedModule() {

    private companion object {
        const val TAG = "AiGo-A11y"
        const val LOG_INFO = 4      // android.util.Log.INFO，避免在 system 进程引入 android.util.Log
        const val LOG_WARN = 5
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // staticScope=true 保证只在 system 进程加载；这里再兜一层。
        if (!param.isSystemServer) return
        log(LOG_INFO, TAG, "模块已加载到 ${param.processName}（API ${apiVersion}）")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        runCatching {
            installHook(param.classLoader)
        }.onFailure {
            log(LOG_WARN, TAG, "hook 安装失败（不影响系统启动）：${it.simpleName()}: ${it.message}")
        }
    }

    private fun installHook(classLoader: ClassLoader) {
        val systemServer = runCatching {
            classLoader.loadClass("com.android.server.SystemServer")
        }.getOrNull()
        val timings = runCatching {
            classLoader.loadClass("android.util.TimingsTraceAndSlog")
        }.getOrNull()

        if (systemServer == null || timings == null) {
            log(LOG_WARN, TAG, "找不到 SystemServer 或 TimingsTraceAndSlog，本机不兼容，跳过")
            return
        }

        val target = systemServer.declaredMethods.firstOrNull { m ->
            m.name == "startOtherServices" &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == timings
        }
        if (target == null) {
            log(LOG_WARN, TAG, "SystemServer.startOtherServices(TimingsTraceAndSlog) 签名不匹配，跳过")
            return
        }

        hook(target).intercept { chain: XposedInterface.Chain ->
            // 先让原方法完整执行；异常原样抛出，不吞。
            val result = chain.proceed()

            // 系统服务就绪后再接入保活。这一段失败绝不能影响启动结果。
            runCatching {
                val context = resolveContext(chain.thisObject)
                if (context == null) {
                    log(LOG_WARN, TAG, "拿不到 SystemServer 的 Context，跳过无障碍保活")
                } else {
                    A11yEnforcer.start(context) { msg -> log(LOG_INFO, TAG, msg) }
                }
            }.onFailure {
                log(LOG_WARN, TAG, "接入保活失败（已忽略）：${it.simpleName()}: ${it.message}")
            }

            result
        }
        log(LOG_INFO, TAG, "已挂上 SystemServer.startOtherServices")
    }

    /**
     * 从 SystemServer 实例上反射取 `mSystemContext`。
     *
     * 不同 Android 版本字段可能在父类，所以逐级向上找。
     * 取不到就返回 null —— **宁可放弃保活，也不要靠猜字段名乱试**，
     * 反射失败抛到 system_server 里就是重启循环。
     */
    private fun resolveContext(systemServer: Any): Context? {
        var cls: Class<*>? = systemServer.javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField("mSystemContext") }.getOrNull()
            if (field != null) {
                runCatching { field.isAccessible = true }
                val value = runCatching { field.get(systemServer) }.getOrNull()
                if (value is Context) return value
            }
            cls = cls.superclass
        }
        return null
    }

    private fun Throwable.simpleName(): String = this::class.java.simpleName
}
