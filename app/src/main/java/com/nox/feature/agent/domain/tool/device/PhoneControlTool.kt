package com.nox.feature.agent.domain.tool.device

import com.nox.core.util.FileLogger
import com.nox.feature.agent.domain.model.AgentContext
import com.nox.feature.agent.domain.tool.AbstractContextualTool
import com.nox.feature.agent.domain.tool.ParameterType
import com.nox.feature.agent.domain.tool.ToolCapability
import com.nox.feature.agent.domain.tool.ToolParameter
import com.nox.feature.agent.domain.tool.ToolPermissionPolicy
import com.nox.feature.agent.domain.tool.ToolResult
import com.nox.feature.device.PhoneControlManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 手机控制：通过无障碍服务操作宿主手机。
 *
 * 与 `Shizuku`（shell 身份跑命令）和 `Root`（root）都不同，这条通道不依赖任何特权，
 * 只要用户开启了 Nox 的无障碍服务，就能读界面、点控件、输入文字、打开应用。
 */
class PhoneControlTool @Inject constructor(
    private val manager: PhoneControlManager,
    private val tracker: com.nox.feature.device.PhoneTaskTracker
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "PhoneControlTool"
    }

    override val name = "Phone"

    override val description =
        "操作手机。open_app 按应用名或包名打开应用；screen 读当前界面的文本与可点控件；" +
            "click / long_press 按文本或描述定位控件（也可给 x/y）；input 写文字；swipe 滑动；" +
            "back/home/recents/notifications 走系统导航；screenshot 截图；" +
            "volume 调音量；flashlight 开关手电筒；brightness 调屏幕亮度（需修改系统设置权限）；" +
            "key 发按键；current_app 看当前应用；wait 等界面加载。" +
            "需要用户先在「应用权限 → 手机控制」开启无障碍服务。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "动作：open_app / screen / click / long_press / input / swipe / screenshot / " +
                "volume / flashlight / brightness / key / current_app / wait / back / home / recents / notifications",
            required = true,
            enum = listOf(
                "open_app", "screen", "click", "long_press", "input", "swipe", "screenshot",
                "volume", "flashlight", "brightness", "key", "current_app", "wait",
                "back", "home", "recents", "notifications"
            )
        ),
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "open_app 用：应用名或包名，例如 微信 或 com.tencent.mm",
            required = false
        ),
        "text" to ToolParameter(
            name = "text",
            type = ParameterType.STRING,
            description = "click 用：要点击的控件文本（支持包含匹配）；input 用：要写入的文字",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "click 用：要点击控件的 contentDescription（图标按钮常只有描述没有文本）",
            required = false
        ),
        "x" to ToolParameter(
            name = "x",
            type = ParameterType.INTEGER,
            description = "click 用：屏幕横坐标（按控件点不到时才用）",
            required = false
        ),
        "y" to ToolParameter(
            name = "y",
            type = ParameterType.INTEGER,
            description = "click 用：屏幕纵坐标",
            required = false
        ),
        "direction" to ToolParameter(
            name = "direction",
            type = ParameterType.STRING,
            description = "swipe 用：up / down / left / right",
            required = false,
            enum = listOf("up", "down", "left", "right")
        ),
        "ratio" to ToolParameter(
            name = "ratio",
            type = ParameterType.STRING,
            description = "swipe 用：滑动距离占屏幕的比例，默认 0.6，范围 0.15–0.9",
            required = false
        ),
        "level" to ToolParameter(
            name = "level",
            type = ParameterType.INTEGER,
            description = "volume set 用：目标音量百分比 0–100；brightness 用：目标亮度百分比 1–100",
            required = false
        ),
        "enabled" to ToolParameter(
            name = "enabled",
            type = ParameterType.BOOLEAN,
            description = "flashlight 用：true 开、false 关",
            required = false
        ),
        "millis" to ToolParameter(
            name = "millis",
            type = ParameterType.INTEGER,
            description = "wait 用：等待毫秒数，100–10000",
            required = false
        ),
        "key" to ToolParameter(
            name = "key",
            type = ParameterType.STRING,
            description = "key 用：back / home / recents / volume_up / volume_down / mute",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少必需参数：action", "MISSING_ACTION")

        if (!manager.isEnabled()) {
            return ToolResult.Error(
                "手机控制未开启：请到「软件权限 → 手机控制」打开 Nox 的无障碍服务",
                code = PhoneControlManager.ERROR_NOT_ENABLED
            )
        }

        return try {
            val result = when (action) {
                "open_app" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.Error("open_app 需要 query", "MISSING_QUERY")
                    manager.openApp(query)
                }

                "screen" -> manager.describeScreen()

                "click" -> manager.click(
                    text = args["text"]?.jsonPrimitive?.contentOrNull,
                    contentDescription = args["description"]?.jsonPrimitive?.contentOrNull,
                    x = args["x"]?.jsonPrimitive?.intOrNull,
                    y = args["y"]?.jsonPrimitive?.intOrNull
                )

                "input" -> {
                    val text = args["text"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.Error("input 需要 text", "MISSING_TEXT")
                    manager.setText(text)
                }

                "swipe" -> {
                    val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "up"
                    val ratio = args["ratio"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.6f
                    manager.swipe(direction, ratio)
                }

                "back", "home", "recents", "notifications" -> manager.globalAction(action)

                "long_press" -> manager.longPress(
                    text = args["text"]?.jsonPrimitive?.contentOrNull,
                    x = args["x"]?.jsonPrimitive?.intOrNull,
                    y = args["y"]?.jsonPrimitive?.intOrNull
                )

                "screenshot" -> manager.screenshot()

                "volume" -> {
                    val mode = args["text"]?.jsonPrimitive?.contentOrNull
                        ?: args["query"]?.jsonPrimitive?.contentOrNull
                        ?: "up"
                    manager.volume(mode, args["level"]?.jsonPrimitive?.intOrNull)
                }

                "flashlight" -> manager.flashlight(
                    args["enabled"]?.jsonPrimitive?.booleanOrNull
                        ?: return ToolResult.Error("flashlight 需要 enabled", "MISSING_ENABLED")
                )

                "brightness" -> {
                    val level = args["level"]?.jsonPrimitive?.intOrNull
                        ?: return ToolResult.Error("brightness 需要 level（1-100）", "MISSING_LEVEL")
                    manager.setBrightness(level)
                }

                "key" -> {
                    val keyName = args["key"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.Error("key 需要 key 参数", "MISSING_KEY")
                    manager.key(keyName)
                }

                "current_app" -> manager.currentApp()

                "wait" -> manager.waitMs(args["millis"]?.jsonPrimitive?.longOrNull ?: 1_000L)

                else -> return ToolResult.Error("不支持的动作：$action", "INVALID_ACTION")
            }

            if (result.ok) {
                tracker.markUsed(action, result.detail)
                ToolResult.Success(JsonPrimitive(result.detail))
            } else {
                ToolResult.Error(result.detail, code = result.errorCode ?: "PHONE_ACTION_FAILED")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "手机控制异常: $action", e)
            ToolResult.Error("手机控制失败：${e.message}", code = "PHONE_CONTROL_FAILED")
        }
    }
}
