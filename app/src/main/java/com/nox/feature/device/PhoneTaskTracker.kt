package com.nox.feature.device

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记录一轮对话里手机控制的执行结果，供轮次结束时弹出「已完成」面板。
 *
 * 只保留最后一次动作的文字，不累积整轮流水——面板是给用户一眼确认结果用的，
 * 堆一长串操作日志反而看不清。
 */
@Singleton
class PhoneTaskTracker @Inject constructor() {

    private var used = false
    private val actions = mutableListOf<String>()

    @Synchronized
    fun markUsed(action: String, detail: String) {
        used = true
        // 只留最近若干条：面板是摘要，不是完整日志
        actions += detail.ifBlank { action }
        while (actions.size > MAX_ACTIONS) actions.removeAt(0)
    }

    /** 取出并清空本轮记录。没有用过手机控制时返回 null。 */
    @Synchronized
    fun consume(): Summary? {
        if (!used) return null
        val summary = Summary(actions.toList())
        used = false
        actions.clear()
        return summary
    }

    @Synchronized
    fun clear() {
        used = false
        actions.clear()
    }

    data class Summary(val actions: List<String>)
}

/** 面板里最多列出的步骤数。 */
private const val MAX_ACTIONS = 8
