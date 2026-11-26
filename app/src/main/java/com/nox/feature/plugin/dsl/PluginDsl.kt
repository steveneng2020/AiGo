package com.nox.feature.plugin.dsl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 插件界面 DSL 的文档版本。
 *
 * 版本不匹配时整份文档都不渲染，而不是尽力而为地画一半——插件与宿主版本错配时
 * 画出错乱的界面，比明确报错更难让作者定位问题。
 */
const val PLUGIN_DSL_VERSION = 1

/** 渲染上限。插件给的树再大也不会拖垮宿主主线程。 */
object PluginDslLimits {
    const val MAX_DEPTH = 24
    const val MAX_NODES = 600
    const val MAX_TEXT = 4000
    const val MAX_DOC_BYTES = 512 * 1024
    const val MAX_IMAGE_URL = 2048
}

/** 节点类型白名单。出现表外类型整份文档拒绝渲染。 */
object PluginDslTypes {
    const val COLUMN = "column"
    const val ROW = "row"
    const val BOX = "box"
    const val CARD = "card"
    const val SCROLL = "scroll"
    const val SPACER = "spacer"
    const val DIVIDER = "divider"
    const val TEXT = "text"
    const val ICON = "icon"
    const val IMAGE = "image"
    const val BADGE = "badge"
    const val PROGRESS = "progress"
    const val BUTTON = "button"
    const val ICON_BUTTON = "iconButton"
    const val SWITCH = "switch"
    const val SLIDER = "slider"
    const val TEXT_FIELD = "textField"

    val ALL = setOf(
        COLUMN, ROW, BOX, CARD, SCROLL, SPACER, DIVIDER, TEXT, ICON, IMAGE, BADGE,
        PROGRESS, BUTTON, ICON_BUTTON, SWITCH, SLIDER, TEXT_FIELD
    )
}

/** 颜色：语义令牌跟随宿主主题，或插件写死的 `#RRGGBB` / `#AARRGGBB`。 */
sealed interface DslColor {
    data class Token(val name: String) : DslColor
    data class Literal(val argb: Long) : DslColor
}

/** 内边距，单位 dp。 */
data class DslInsets(val start: Float, val top: Float, val end: Float, val bottom: Float)

/**
 * 交互动作。
 *
 * `event` 把交互回传给插件 runtime，由插件返回新的 DSL 文档实现刷新；
 * `close` 让宿主关掉当前接管页；`openUrl` 交给系统浏览器。
 * 除此之外的动作一律不认，插件无法通过 DSL 触发任意宿主能力。
 */
data class DslAction(
    val type: String,
    val name: String? = null,
    val url: String? = null
) {
    companion object {
        const val EVENT = "event"
        const val CLOSE = "close"
        const val OPEN_URL = "openUrl"
        val ALL = setOf(EVENT, CLOSE, OPEN_URL)
    }
}

/** 节点样式。字段都可空，缺省时按节点类型取宿主默认值。 */
data class DslStyle(
    val background: DslColor? = null,
    val foreground: DslColor? = null,
    val border: DslColor? = null,
    val radius: Float? = null,
    val shadow: Float? = null,
    val padding: DslInsets? = null,
    val gap: Float? = null,
    val fontSize: Float? = null,
    val weight: Int? = null,
    val align: String? = null,
    val fillWidth: Boolean = false,
    val fillHeight: Boolean = false
)

/**
 * 一个界面节点。
 *
 * 用单一类型带可空字段承载全部节点，而不是 sealed class 继承体系：节点集会持续扩充，
 * 集中在一个类型里加字段，比每加一个节点就新增子类更容易让解析与渲染保持同步。
 */
data class DslNode(
    val type: String,
    val id: String? = null,
    val text: String? = null,
    val icon: String? = null,
    val src: String? = null,
    val placeholder: String? = null,
    val value: String? = null,
    val checked: Boolean = false,
    val progress: Float? = null,
    val min: Float = 0f,
    val max: Float = 1f,
    val children: List<DslNode> = emptyList(),
    val action: DslAction? = null,
    val style: DslStyle = DslStyle(),
    val size: Float? = null,
    val height: Float? = null,
    val width: Float? = null
)

/** 一份可渲染的界面文档。 */
data class DslDocument(
    val root: DslNode,
    /** 文档自带的状态，随交互事件一并发回插件，省去插件自己维护会话状态。 */
    val state: JsonObject = JsonObject(emptyMap())
)

/** 解析失败原因，直接展示给插件作者，`path` 指向出错节点。 */
data class DslParseError(val message: String, val path: String = "")

sealed interface DslParseResult {
    data class Ok(val document: DslDocument) : DslParseResult
    data class Failed(val error: DslParseError) : DslParseResult
}

/**
 * DSL 解析器：把插件给的 JSON 转成可渲染模型，同时完成全部安全约束校验。
 *
 * 先校验完整文档再渲染，避免半份文档画出残缺界面后用户以为插件坏了。
 */
object PluginDslParser {

    private val COLOR_HEX = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val LOCAL_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val ALIGN = setOf("start", "center", "end")

    fun parse(raw: String): DslParseResult {
        if (raw.length > PluginDslLimits.MAX_DOC_BYTES) {
            return DslParseResult.Failed(DslParseError("文档过大（上限 ${PluginDslLimits.MAX_DOC_BYTES} 字节）"))
        }
        val element = runCatching { Json.parseToJsonElement(raw) }
            .getOrElse { return DslParseResult.Failed(DslParseError("不是合法 JSON：${it.message}")) }
        val obj = element as? JsonObject
            ?: return DslParseResult.Failed(DslParseError("文档根必须是对象"))

        val version = obj.text("v")?.toIntOrNull() ?: PLUGIN_DSL_VERSION
        if (version != PLUGIN_DSL_VERSION) {
            return DslParseResult.Failed(
                DslParseError("DSL 版本 $version 不受支持，宿主只认 $PLUGIN_DSL_VERSION")
            )
        }

        val rootObject = obj["root"] as? JsonObject
            ?: return DslParseResult.Failed(DslParseError("缺少 root 节点"))
        val state = (obj["state"] as? JsonObject) ?: JsonObject(emptyMap())

        val counter = Counter()
        val root = readNode(rootObject, "root", 1, counter)
            ?: return DslParseResult.Failed(DslParseError("root 节点结构不合法", "root"))
        return DslParseResult.Ok(DslDocument(root, state))
    }

    private class Counter {
        var nodes = 0
    }

    /** 解析单个界面对象；供整屏接管与插件推送的局部更新共用。 */
    fun parseNode(obj: JsonObject): DslNode? = readNode(obj, "root", 1, Counter())

    private fun readNode(obj: JsonObject, path: String, depth: Int, counter: Counter): DslNode? {
        if (depth > PluginDslLimits.MAX_DEPTH) return null
        if (++counter.nodes > PluginDslLimits.MAX_NODES) return null

        val type = obj.text("type") ?: return null
        if (type !in PluginDslTypes.ALL) return null

        val id = obj.text("id")
        if (id != null && !LOCAL_ID.matches(id)) return null

        val src = obj.text("src")
        if (src != null) {
            if (src.length > PluginDslLimits.MAX_IMAGE_URL) return null
            // 只放行 https：http 会被系统明文策略拦掉，data:/file: 则是绕过审计读本地文件的口子
            if (!src.startsWith("https://")) return null
        }

        val action = obj["action"]?.let {
            val actionObject = it as? JsonObject ?: return null
            readAction(actionObject) ?: return null
        }

        val style = obj["style"]?.let {
            val styleObject = it as? JsonObject ?: return null
            readStyle(styleObject) ?: return null
        }

        val childrenRaw = obj["children"] as? JsonArray
        val children = childrenRaw?.mapIndexedNotNull { index, element ->
            (element as? JsonObject)?.let { readNode(it, "$path.children[$index]", depth + 1, counter) }
        }.orEmpty()
        // 子节点里有解析失败时整棵子树作废，避免静默丢内容
        if (childrenRaw != null && children.size != childrenRaw.size) return null

        return DslNode(
            type = type,
            id = id,
            text = obj.text("text"),
            icon = obj.text("icon"),
            src = src,
            placeholder = obj.text("placeholder"),
            value = obj.text("value"),
            checked = obj.text("checked") == "true",
            progress = obj.text("progress")?.toFloatOrNull().usable(),
            min = obj.text("min")?.toFloatOrNull().usable() ?: 0f,
            max = obj.text("max")?.toFloatOrNull().usable() ?: 1f,
            children = children,
            action = action,
            style = style ?: DslStyle(),
            size = obj.text("size")?.toFloatOrNull().usable(),
            height = obj.text("height")?.toFloatOrNull().usable(),
            width = obj.text("width")?.toFloatOrNull().usable()
        )
    }

    private fun readAction(obj: JsonObject): DslAction? {
        val type = obj.text("type") ?: return null
        if (type !in DslAction.ALL) return null
        return when (type) {
            DslAction.EVENT -> {
                val name = obj.text("name") ?: return null
                if (!LOCAL_ID.matches(name)) return null
                DslAction(type, name = name)
            }
            DslAction.OPEN_URL -> {
                val url = obj.text("url") ?: return null
                if (!url.startsWith("https://")) return null
                DslAction(type, url = url)
            }
            else -> DslAction(type)
        }
    }

    private fun readStyle(obj: JsonObject): DslStyle? {
        fun color(key: String): DslColor? {
            val raw = obj.text(key) ?: return null
            if (obj[key] !is JsonPrimitive) return null
            return parseColor(raw)
        }
        return DslStyle(
            background = color("background"),
            foreground = color("foreground"),
            border = color("border"),
            radius = obj.text("radius")?.toFloatOrNull().usable(),
            shadow = obj.text("shadow")?.toFloatOrNull().usable(),
            padding = obj["padding"]?.let { readInsets(it) ?: return null },
            gap = obj.text("gap")?.toFloatOrNull().usable(),
            fontSize = obj.text("fontSize")?.toFloatOrNull().usable(),
            weight = obj.text("weight")?.toIntOrNull(),
            align = obj.text("align")?.takeIf { it in ALIGN },
            fillWidth = obj.text("fillWidth") == "true",
            fillHeight = obj.text("fillHeight") == "true"
        )
    }

    /** 颜色文本转模型；供样式令牌复用同一套令牌名与十六进制规则。 */
    fun parseColorText(text: String): DslColor? = parseColor(text)

    private fun parseColor(text: String): DslColor? {
        if (text.startsWith("#")) {
            if (!COLOR_HEX.matches(text)) return null
            val hex = text.removePrefix("#")
            val argb = if (hex.length == 6) 0xFF000000L or hex.toLong(16) else hex.toLong(16)
            return DslColor.Literal(argb)
        }
        // 语义令牌交给渲染器按宿主主题解析，不认识的令牌渲染时回退
        return if (LOCAL_ID.matches(text)) DslColor.Token(text) else null
    }

    private fun readInsets(element: JsonElement): DslInsets? {
        (element as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()?.usable()?.let {
            return DslInsets(it, it, it, it)
        }
        val array = element as? JsonArray ?: return null
        // 负内边距在 Compose 里会让子元素叠在一起，统一夹到 0
        val values = array.map {
            ((it as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()?.usable() ?: return null).coerceAtLeast(0f)
        }
        return when (values.size) {
            2 -> DslInsets(values[1], values[0], values[1], values[0])
            4 -> DslInsets(values[0], values[1], values[2], values[3])
            else -> null
        }
    }

    /** 只取原始文本，数字与布尔也能取到；对象与数组返回 null。 */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * NaN / Infinity 不是可用尺寸。写 `"height": "NaN"` 时当未声明处理，
 * 而不是把非有限值交给 Compose 布局——那会在测量阶段抛异常。
 */
private fun Float?.usable(): Float? = this?.takeIf { it.isFinite() }
