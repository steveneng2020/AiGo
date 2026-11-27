package com.nox.feature.device.a11y

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils

/**
 * 无障碍保活后端（运行在 system_server 进程内）。
 *
 * ## 与 App 内 L2 的分工
 *
 * | 层级 | 运行位置 | 权限来源 | 何时生效 |
 * | --- | --- | --- | --- |
 * | L1 | App 进程 | 无（ContentObserver 不需要权限） | 永远 |
 * | L2 | App 进程 | su（Root） | 有 Root 且用户开启 |
 * | **L3** | **system_server** | 进程本身就是系统 | LSPosed 模块已激活 |
 *
 * L3 的价值：不 fork `su`、不依赖 Root 授权弹窗、写设置不会被视为越权。
 * 代价是需要 LSPosed，且 hook 有让 system_server 崩溃的风险——所以本类
 * **所有对外入口都包在 runCatching 里**，宁可什么都不做，也不能把系统带崩。
 *
 * ## 学到的五条设计（对比同类实现后确认）
 *
 * 1. **事件驱动**：监听设置变化，不轮询。轮询在 system_server 里代价尤其高。
 * 2. **只维护自己的条目**：用户可能同时开着别的无障碍服务（实测本机有 3 个），
 *    覆盖成只有自己会直接破坏它们。
 * 3. **区分「在列表」与「真实连接」**：两种故障，修法不同。
 * 4. **退避 + 冷却 + 上限**：厂商反复回删时不打消耗战。
 * 5. **可一键关闭**：写入 global 设置，用 `settings put global` 即可停用，
 *    不需要卸载模块或进 LSPosed 管理器。
 */
internal object A11yEnforcer {

    private const val TAG = "AiGoA11yEnforcer"

    /** 自己的组件名。写死而非从包管理器查，避免在 system_server 里做重操作。 */
    private const val SELF_COMPONENT =
        "com.aigo/com.nox.feature.device.PhoneControlService"

    /**
     * 总开关：写在 global 命名空间。
     *
     * 关闭方式（无需重装模块）：
     * ```
     * adb shell settings put global aigo_a11y_enforcement 0
     * ```
     */
    private const val KEY_ENFORCEMENT = "aigo_a11y_enforcement"

    /**
     * 供 App 侧读取的标记：L3 是否在运行。
     *
     * App 侧 [com.nox.feature.device.AccessibilityGuard] 读它来决定是否让位，
     * 避免 L2 与 L3 同时纠正造成写设置打架。
     */
    private const val KEY_ACTIVE = "aigo_accessibility_enforcement"

    /** 退避阶梯（毫秒）。 */
    private val BACKOFF_MS = longArrayOf(300, 1_000, 5_000, 15_000, 30_000)

    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val COOLDOWN_MS = 60_000L

    @Volatile private var started = false

    private var observers = mutableListOf<ContentObserver>()
    private var consecutiveFailures = 0
    private var cooldownUntil = 0L
    private var pendingRepair: Runnable? = null

    /**
     * 由 hook 在 `SystemServer.startOtherServices` 返回后调用。
     *
     * @param context system_server 里的 Context（实测是 ActivityThread 的 system context）
     * @param log 日志出口，由 hook 侧提供（system_server 里没有 App 的 FileLogger）
     */
    fun start(context: Context, log: (String) -> Unit) {
        if (started) return
        started = true

        runCatching {
            val enabled = Settings.Global.getInt(
                context.contentResolver, KEY_ENFORCEMENT, 1,
            ) == 1
            if (!enabled) {
                log("无障碍保活后端已禁用（$KEY_ENFORCEMENT=0），不注册监听")
                publishActive(context, false)
                return
            }

            registerObservers(context, log)
            publishActive(context, true)
            log("无障碍保活后端已启动（事件驱动，监听 2 个设置项）")
        }.onFailure {
            // 关键：这里绝不能抛出去。system_server 里未捕获异常 = 系统崩溃重启。
            log("启动失败，已安全退出：${it::class.java.simpleName}: ${it.message}")
        }
    }

    private fun registerObservers(context: Context, log: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())

        fun watch(setting: String) {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    runCatching { onSettingChanged(context, setting, log) }
                        .onFailure { log("处理 $setting 变化失败：${it.message}") }
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(setting), false, observer,
            )
            observers += observer
        }

        watch(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        watch(Settings.Secure.ACCESSIBILITY_ENABLED)
    }

    private fun onSettingChanged(context: Context, setting: String, log: (String) -> Unit) {
        val inList = isInEnabledList(context)
        val switchOn = isGlobalSwitchOn(context)

        // 服务正常的唯一判据是「在列表里」+「总开关开着」。
        // 真实连接状态在 system_server 里拿不到轻量判据，交给 App 侧判断。
        if (inList && switchOn) return

        log("检测到无障碍被改动（$setting）：在列表=$inList 开关=$switchOn，准备修复")

        val now = android.os.SystemClock.elapsedRealtime()
        if (now < cooldownUntil) {
            log("冷却中，剩余 ${(cooldownUntil - now) / 1000}s，跳过")
            return
        }

        scheduleRepair(context, log, attempt = 0)
    }

    private fun scheduleRepair(
        context: Context,
        log: (String) -> Unit,
        attempt: Int,
    ) {
        if (attempt >= BACKOFF_MS.size) {
            consecutiveFailures++
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                cooldownUntil = android.os.SystemClock.elapsedRealtime() + COOLDOWN_MS
                log("连续 $consecutiveFailures 次失败，进入 ${COOLDOWN_MS / 1000}s 冷却。" +
                    "厂商可能在持续回删，不再硬碰。")
            } else {
                log("本次退避阶梯用尽，等待下次设置变化再试")
            }
            return
        }

        val delayMs = BACKOFF_MS[attempt]
        val action = Runnable {
            runCatching { repairNow(context, log, attempt) }
                .onFailure { log("修复动作异常：${it.message}") }
        }
        pendingRepair = action
        Handler(Looper.getMainLooper()).postDelayed(action, delayMs)
    }

    private fun repairNow(context: Context, log: (String) -> Unit, attempt: Int) {
        val target = buildTargetList(context)
        val okList = runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                target,
            )
        }.getOrDefault(false)

        val okSwitch = runCatching {
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            )
        }.getOrDefault(false)

        // 读回校验：写设置是异步生效，但同一进程内读回通常已是新值。
        val inList = isInEnabledList(context)
        val switchOn = isGlobalSwitchOn(context)

        if (okList && okSwitch && inList && switchOn) {
            consecutiveFailures = 0
            log("修复成功（第 ${attempt + 1} 档尝试）")
            return
        }

        log("修复未生效（第 ${attempt + 1} 档）：写列表=$okList 写开关=$okSwitch " +
            "读列表=$inList 读开关=$switchOn，按下一档退避重试")
        scheduleRepair(context, log, attempt + 1)
    }

    /**
     * 构造写回的完整列表：**保留其他服务，只确保自己在里面**。
     *
     * 实测本机同时开着 3 个无障碍服务（AiGo、场景模式、Eta）。
     * 直接写成只有自己会把另外两个关掉——这是不可接受的副作用。
     */
    private fun buildTargetList(context: Context): String {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        if (current.isBlank()) return SELF_COMPONENT

        val others = current.split(':').filter { entry ->
            entry.isNotBlank() && !entry.equals(SELF_COMPONENT, ignoreCase = true)
        }
        return (others + SELF_COMPONENT).joinToString(":")
    }

    private fun isInEnabledList(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (item in splitter) {
            if (item.equals(SELF_COMPONENT, ignoreCase = true)) return true
        }
        return false
    }

    private fun isGlobalSwitchOn(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0,
        ) == 1
    }.getOrDefault(false)

    /** 写标记，让 App 侧知道 L3 在运行、L2 该让位。 */
    private fun publishActive(context: Context, active: Boolean) {
        runCatching {
            Settings.Global.putInt(
                context.contentResolver, KEY_ACTIVE, if (active) 1 else 0,
            )
        }
    }

    /**
     * 读 [KEY_ENFORCEMENT] 的当前值，供诊断。
     *
     * 不在 stop 里注销 observer——system_server 的生命周期等于整机，
     * 模块重新加载时进程会重启，observer 随之消失，不需要手动反注册。
     */
    fun isEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, KEY_ENFORCEMENT, 1) == 1
    }.getOrDefault(false)
}
