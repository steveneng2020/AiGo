package com.nox.feature.device

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import com.nox.core.util.FileLogger
import com.nox.feature.agent.domain.root.SuRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无障碍服务保活（分级）。
 *
 * ## 为什么原来那版没用
 *
 * 旧实现只做两件事：记录被关次数、回到前台时提示用户去设置页重开。
 * 它解决的是「用户不知道坏了」，没解决「坏了要人去修」。
 *
 * 真正的掉线原因分四类，旧实现只覆盖了最后一类：
 *
 * | 原因 | 表现 | 谁能修 |
 * | --- | --- | --- |
 * | A 进程被杀（LMK/省电） | 设置列表里还在，但服务没连上 | 前台服务 + 重绑 |
 * | B 被移出启用列表（厂商/覆盖安装） | `ENABLED_ACCESSIBILITY_SERVICES` 里没了 | 需写 Settings.Secure |
 * | C 总开关被关 | `accessibility_enabled = 0` | 同上 |
 * | D 用户手动关闭 | 同上 | 不该绕过，只提示 |
 *
 * B/C 是常见情形，而写 `Settings.Secure` 需要系统级身份——普通应用拿不到。
 * 所以本模块分三级，按设备能力降级：
 *
 * - **L1 感知（所有设备）**：注册 ContentObserver 监听设置变化。
 *   不需要任何权限，把「靠用户发现」变成「掉线即知」。
 * - **L2 修复（有 Root）**：用 [SuRunner] 执行 `settings put` 写回自身条目。
 *   只补自己那一项，保留其他无障碍服务；带退避、次数上限与冷却。
 * - **L3 注入（LSPosed）**：由独立模块在 system_server 内纠正，见 hook 模块。
 *   本文件不参与 L3；L3 生效时 [isEnforcedByHook] 会返回 true，L2 自动让位。
 *
 * ## 学到的五条设计（来自对比同类实现）
 *
 * 1. **事件驱动，不轮询** —— ContentObserver 比定时查省电，且能秒级响应。
 * 2. **只维护自己的条目** —— 用户可能同时开着别的无障碍服务，覆盖会破坏它们。
 * 3. **区分「在列表里」与「真实连接」** —— 两种故障，修的路径不同。
 * 4. **退避 + 冷却 + 上限** —— 厂商反复删设置时不打消耗战。
 * 5. **失败要明说** —— 不静默、不假装成功；本模块只记日志并回报状态。
 *
 * ## 一个必须承认的边界
 *
 * 没有 Root、没有 LSPosed 的设备，**无法自动修复 B/C**。
 * 这是 Android 的设计（写 Secure Settings 需系统身份），不是实现取舍。
 * 此时本模块退回 L1：立即感知并提示，让用户少受「不知道坏了」的损失。
 */
@Singleton
class AccessibilityGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val suRunner: SuRunner,
) {

    companion object {
        private const val TAG = "AccessibilityGuard"

        /** 自身服务的完整组件名，写设置时要用它。 */
        const val SELF_COMPONENT: String =
            "com.aigo/com.nox.feature.device.PhoneControlService"

        /**
         * 服务是否真的连上。
         *
         * 静态版供权限页等无 Context 的场景使用；实例方法 [isServiceConnected]
         * 语义相同，保留是为了调用处读起来更自然。
         */
        fun isServiceConnectedStatic(): Boolean = PhoneControlService.current() != null

        /** 服务是否在系统启用列表里。 */
        fun isInEnabledListStatic(context: Context): Boolean {
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

        /** 总开关是否打开。 */
        fun isGlobalSwitchOnStatic(context: Context): Boolean = runCatching {
            Settings.Secure.getInt(
                context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0,
            ) == 1
        }.getOrDefault(false)

        /** 退避阶梯（毫秒）。厂商反删时逐步拉长，避免打消耗战。 */
        val BACKOFF_MS = longArrayOf(300, 1_000, 5_000, 15_000, 30_000)

        /** 连续失败达到该次数后进入冷却。 */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** 冷却时长。 */
        const val COOLDOWN_MS = 60_000L

        /** 声称自己修好了之后，多久内不再尝试，避免频繁写设置。 */
        const val MIN_REPAIR_INTERVAL_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var observer: ContentObserver? = null
    private var enabledObserver: ContentObserver? = null
    private var started = false

    /** 连续失败计数，成功一次即清零。 */
    private var consecutiveFailures = 0

    /** 冷却截止时刻（elapsedRealtime）。 */
    private var cooldownUntil = 0L

    /** 上次尝试修复的时刻，用于节流。 */
    private var lastRepairAt = 0L

    /** 修复过程串行化，避免 observer 连发时并发写设置。 */
    private var repairJob: Job? = null

    // ---------------- 状态查询 ----------------

    /** 服务是否真的连上了（不是「在设置列表里」）。 */
    fun isServiceConnected(): Boolean = PhoneControlService.current() != null

    /** 服务是否在系统的启用列表里。 */
    fun isInEnabledList(): Boolean {
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

    /** 总开关是否打开。 */
    fun isGlobalSwitchOn(): Boolean =
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1

    /**
     * 是否由 LSPosed 后端接管。
     *
     * 后端注入 system_server 后会写一个标记设置；存在即视为 L3 生效，
     * 此时 L2 不再插手，避免两个纠正者互相打架。
     */
    fun isEnforcedByHook(): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            "aigo_accessibility_enforcement",
            0,
        ) == 1
    }.getOrDefault(false)

    /** 当前可用的最强保护等级，用于界面展示。 */
    enum class Level {
        /** 只感知，不修复。 */
        OBSERVE_ONLY,

        /** 有 Root，可自动写回。 */
        ROOT_REPAIR,

        /** LSPosed 后端接管。 */
        HOOK_ENFORCED,
    }

    suspend fun currentLevel(): Level = when {
        isEnforcedByHook() -> Level.HOOK_ENFORCED
        suRunner.isAvailable() -> Level.ROOT_REPAIR
        else -> Level.OBSERVE_ONLY
    }

    // ---------------- L1：感知 ----------------

    /**
     * 开始监听设置变化。
     *
     * 幂等：重复调用不会重复注册。应用启动时调用一次即可，无需随页面进出启停。
     */
    fun start() {
        if (started) return
        started = true

        val handler = Handler(Looper.getMainLooper())

        // 监听「启用列表」变化：服务被移出列表时立刻知道。
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handleSettingsChanged("list")
            }
        }.also {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                it,
            )
        }

        // 监听「总开关」变化：被关掉时同样立刻知道。
        enabledObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handleSettingsChanged("switch")
            }
        }.also {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                it,
            )
        }

        FileLogger.i(TAG, "已开始监听无障碍设置变化（L1 感知已启用）")
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        enabledObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        enabledObserver = null
        started = false
        repairJob?.cancel()
        FileLogger.i(TAG, "已停止监听无障碍设置变化")
    }

    private fun handleSettingsChanged(reason: String) {
        val connected = isServiceConnected()
        val inList = isInEnabledList()
        val switchOn = isGlobalSwitchOn()

        // 先记一行再判断：否则「服务恰好还连着」时会完全静默，
        // 事后无法区分「observer 没触发」与「触发了但服务正常」。
        // 2026-11-27 实测就因此查了很久。
        FileLogger.i(
            TAG,
            "无障碍设置发生变化（触发=$reason, 连接=$connected, 在列表=$inList, 开关=$switchOn）",
        )

        if (connected) {
            // 服务仍连着，说明这次变化与我们无关（可能是别的无障碍服务被改）。
            // 不重置失败计数——只有「修复成功」才算赢。
            return
        }
        FileLogger.w(TAG, "判定为掉线，记录并尝试恢复（触发=$reason）")
        AccessibilityKeepAlive.recordDisabled(context)

        // A 类：在列表里但没连上——重绑即可，不需要改设置。
        if (inList && switchOn) {
            FileLogger.i(TAG, "服务在列表中但未连接，判定为进程被杀，交由系统重绑")
            return
        }

        // B/C 类：需要写设置。有 Root 才尝试。
        scheduleRepair(reason)
    }

    // ---------------- L2：Root 修复 ----------------

    private fun scheduleRepair(reason: String) {
        if (repairJob?.isActive == true) return

        // 用户没开自动保持就不动手。写系统设置有副作用（fork su、设置抖动），
        // 即使有 Root 也应尊重用户的显式选择——与同类实现一致，默认关。
        if (!isKeepEnabled()) {
            FileLogger.i(TAG, "自动保持未开启，仅记录并提示（触发=$reason）")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now < cooldownUntil) {
            FileLogger.i(TAG, "修复冷却中，剩余 ${(cooldownUntil - now) / 1000}s，跳过")
            return
        }
        if (now - lastRepairAt < MIN_REPAIR_INTERVAL_MS) {
            FileLogger.i(TAG, "距上次修复不足 ${MIN_REPAIR_INTERVAL_MS}ms，节流跳过")
            return
        }

        repairJob = scope.launch {
            if (isEnforcedByHook()) {
                FileLogger.i(TAG, "L3 后端已接管，L2 让位")
                return@launch
            }
            if (!suRunner.isAvailable()) {
                FileLogger.w(TAG, "无 Root，无法自动修复；已记录并会提示用户手动开启")
                return@launch
            }
            attemptRepair(reason)
        }
    }

    private suspend fun attemptRepair(reason: String) {
        lastRepairAt = SystemClock.elapsedRealtime()

        // 退避阶梯：第几次失败就用第几档间隔。
        val backoff = BACKOFF_MS[consecutiveFailures.coerceIn(0, BACKOFF_MS.lastIndex)]
        if (backoff > 0) delay(backoff)

        val target = buildTargetList()
        FileLogger.i(TAG, "尝试写回无障碍设置（触发=$reason，第 ${consecutiveFailures + 1} 次）")

        val listResult = suRunner.run(
            listOf(
                "settings", "put", "secure",
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, target,
            ),
            timeoutMs = 5_000,
        )
        val switchResult = suRunner.run(
            listOf(
                "settings", "put", "secure",
                Settings.Secure.ACCESSIBILITY_ENABLED, "1",
            ),
            timeoutMs = 5_000,
        )

        val ok = listResult.exitCode == 0 && switchResult.exitCode == 0
        // 写设置是异步生效的，等一小会儿再确认。
        delay(500)
        val repaired = ok && isInEnabledList() && isGlobalSwitchOn()

        if (repaired) {
            consecutiveFailures = 0
            FileLogger.i(TAG, "无障碍设置已修复（触发=$reason）")
        } else {
            consecutiveFailures++
            FileLogger.w(
                TAG,
                "修复未生效（连续第 $consecutiveFailures 次）：" +
                    "list=${listResult.exitCode} switch=${switchResult.exitCode} " +
                    "在列表=${isInEnabledList()} 开关=${isGlobalSwitchOn()}",
            )
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
                FileLogger.w(
                    TAG,
                    "连续 $consecutiveFailures 次失败，进入 ${COOLDOWN_MS / 1000}s 冷却。" +
                        "厂商可能在持续回删设置，不再硬碰。",
                )
            } else {
                // 未到上限，按下一档退避再试。
                scheduleRepair("retry")
            }
        }
    }

    /**
     * 构造写回的完整列表：**保留其他服务的条目，只确保自己在里面**。
     *
     * 直接覆盖成只有自己，会把用户开着的其他无障碍服务关掉——
     * 这是不可接受的副作用（同类实现里明确写了「只维护 owner 用户中的自身组件」）。
     */
    private fun buildTargetList(): String {
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

    // ---------------- 诊断信息（给界面用） ----------------

    data class Status(
        /** 服务是否真的连上。 */
        val connected: Boolean,
        /** 是否在系统启用列表里。 */
        val inEnabledList: Boolean,
        /** 总开关是否打开。 */
        val switchOn: Boolean,
        /** 累计被系统关闭次数。 */
        val disabledCount: Int,
        /** 当前保护等级。 */
        val level: Level,
    )

    suspend fun status(): Status = Status(
        connected = isServiceConnected(),
        inEnabledList = isInEnabledList(),
        switchOn = isGlobalSwitchOn(),
        disabledCount = AccessibilityKeepAlive.disabledCount(context),
        level = currentLevel(),
    )

    /** 用户是否开启了自动保持（设置页那个开关）。 */
    private fun isKeepEnabled(): Boolean = runCatching {
        context.getSharedPreferences("accessibility_guard", Context.MODE_PRIVATE)
            .getBoolean("keep_enabled", false)
    }.getOrDefault(false)

    /** 跳系统无障碍设置页，供无 Root 设备手动恢复。 */
    fun openSystemSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            FileLogger.w(TAG, "打开无障碍设置页失败：${it.message}")
        }
    }
}
