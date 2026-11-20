package com.richardluo.mifocusconverter.notification

import android.os.Bundle
import androidx.core.graphics.toColorInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小米焦点通知 / 超级岛参数（通知 extras 里的 `miui.focus.param`）。
 *
 * 兼容两种结构：
 * - `{ "param_v2": { ... } }`（OS2 / OS3，文档推荐结构）
 * - `{ "baseInfo": { ... }, "ticker": ... }`（旧版焦点通知，字段直接在根节点）
 *
 * 展开态模板的数据分散在不同的组件对象里（baseInfo / chatInfo / highlightInfo / coverInfo / iconTextInfo /
 * competitionIconTextInfo / highlightInfoV3 / multiProgressInfo / hintInfo），
 * 这里按模板库的组件优先级逐个尝试，取第一个有内容的。
 */
data class FocusParam(
    val title: String? = null,
    val content: String? = null,
    /** 上岛 / 状态栏 chip 的极简文案，例如 "剩5分钟"、"2件包裹" */
    val chipText: String? = null,
    /** 岛里主要文字的颜色（`colorTitle` / `highlightColor`），不是通知主题色 */
    val titleColor: Int? = null,
    val business: String? = null,
    val updatable: Boolean = false,
    /** 0..100，仅在参数里显式带了进度时才有值 */
    val progress: Int? = null,
    /** 指向 `miui.focus.pics` Bundle 中 Icon 的 key，按模板优先级排列 */
    val imageKeys: List<String> = emptyList(),
    /** 按钮：方式一是 `miui.focus.actions` 里的 key，方式二是内联的 actionInfo */
    val actions: List<FocusAction> = emptyList(),
) {
  companion object {
    const val EXTRA_PARAM = "miui.focus.param"
    const val EXTRA_PICS = "miui.focus.pics"
    const val EXTRA_ACTIONS = "miui.focus.actions"
    const val EXTRA_PIC_PREFIX = "miui.focus.pic_"

    /** 该通知是否携带小米焦点通知数据 */
    fun isFocusNotification(extras: Bundle?): Boolean {
      if (extras == null) return false
      if (extras.containsKey(EXTRA_PARAM) || extras.containsKey(EXTRA_PICS)) return true
      return extras.keySet().any { it.startsWith(EXTRA_PIC_PREFIX) }
    }

    fun parse(raw: String?): FocusParam? {
      if (raw.isNullOrBlank()) return null
      return runCatching {
        val root = JSONObject(raw)
        val v2 = root.optJSONObject("param_v2") ?: root
        val island = v2.optJSONObject("param_island") ?: v2.optJSONObject("paramIsland")
        val bigArea = island?.optJSONObject("bigIslandArea")
        val leftTextInfo = bigArea?.optJSONObject("imageTextInfoLeft")?.optJSONObject("textInfo")

        val mainText = TEXT_COMPONENTS.firstNotNullOfOrNull { component ->
          val obj = v2.optJSONObject(component.key) ?: return@firstNotNullOfOrNull null
          val title = obj.str(component.title)
          val content = component.content?.let { obj.str(it) }
          if (title == null && content == null) null else title to content
        }

        FocusParam(
            title = firstNotBlank(mainText?.first, leftTextInfo?.str("title"), root.str("title")),
            content =
                firstNotBlank(
                    mainText?.second,
                    leftTextInfo?.str("content"),
                    root.str("content"),
                ),
            chipText =
                firstNotBlank(
                    leftTextInfo?.str("title"),
                    leftTextInfo?.str("content"),
                    bigArea?.rightText()?.first,
                    v2.optJSONObject("hintInfo")?.str("title"),
                    v2.str("ticker"),
                    island?.str("aodTitle"),
                ),
            titleColor =
                firstNotBlank(
                        v2.optJSONObject("baseInfo")?.str("colorTitle"),
                        v2.str("colorTitle"), // v1 焦点通知：颜色直接在根节点
                        v2.str("highlightColor"),
                    )
                    ?.toColor(),
            business = v2.str("business"),
            updatable = v2.optBoolean("updatable", false),
            progress = findProgress(v2, island),
            imageKeys = buildImageKeys(v2, island),
            actions = v2.optJSONArray("actions").actions(),
        )
      }
          .getOrNull()
    }

    /** 展开态模板里的主文本组件，按优先级排列 */
    private val TEXT_COMPONENTS =
        listOf(
            TextComponent("baseInfo", "title", "content"),
            TextComponent("chatInfo", "title", "content"),
            TextComponent("highlightInfo", "title", "content"),
            TextComponent("iconTextInfo", "title", "content"),
            TextComponent("coverInfo", "title", "content"),
            TextComponent("competitionIconTextInfo", "title", "content"),
            TextComponent("highlightInfoV3", "primaryText", "secondaryText"),
            TextComponent("multiProgressInfo", "title", null),
            TextComponent("hintInfo", "title", "content"),
        )

    private class TextComponent(val key: String, val title: String, val content: String?)

    /** 大岛 B 区：图文组件 / 定宽数字 / 等宽数字 / 进度文本 */
    private fun JSONObject.rightText(): Pair<String?, String?>? {
      val right = optJSONObject("imageTextInfoRight") ?: return null
      val textInfo = right.optJSONObject("textInfo")
      if (textInfo != null) return textInfo.str("title") to textInfo.str("content")
      val digit =
          right.optJSONObject("fixedWidthDigitInfo")
              ?: right.optJSONObject("sameWidthDigitInfo")
              ?: right.optJSONObject("progressTextInfo")?.optJSONObject("textInfo")
      return digit?.str("digit")?.let { it to digit.str("content") }
    }

    private fun findProgress(v2: JSONObject, island: JSONObject?): Int? {
      val bigArea = island?.optJSONObject("bigIslandArea")
      return v2.optJSONObject("progressInfo")?.progress()
          ?: v2.optJSONObject("multiProgressInfo")?.progress()
          ?: bigArea?.optJSONObject("progressTextInfo")?.optJSONObject("progressInfo")?.progress()
          ?: v2.optJSONObject("baseInfo")?.progress()
          ?: v2.optInt("progress", -1).takeIf { it in 0..100 }
    }

    /** 按模板优先级挑可用的图片：封面 > 头像 > 新图文 > 赛事 > 大岛 > 识别图形 > 功能图标 > 小岛 */
    private fun buildImageKeys(v2: JSONObject, island: JSONObject?): List<String> {
      val bigArea = island?.optJSONObject("bigIslandArea")
      val smallArea = island?.optJSONObject("smallIslandArea")
      return listOfNotNull(
          v2.optJSONObject("coverInfo")?.str("picCover"),
          v2.optJSONObject("chatInfo")?.str("picProfile"),
          v2.optJSONObject("iconTextInfo")?.optJSONObject("animIconInfo")?.str("src"),
          v2.optJSONObject("competitionIconTextInfo")?.str("compIcon"),
          bigArea?.optJSONObject("imageTextInfoLeft")?.optJSONObject("picInfo")?.str("pic"),
          bigArea?.optJSONObject("picInfo")?.str("pic"),
          bigArea?.optJSONObject("imageTextInfoRight")?.optJSONObject("picInfo")?.str("pic"),
          v2.optJSONObject("picInfo")?.str("pic"),
          v2.optJSONObject("baseInfo")?.str("picFunction"),
          v2.optJSONObject("highlightInfo")?.str("picFunction"),
          smallArea?.optJSONObject("picInfo")?.str("pic"),
          smallArea?.optJSONObject("combinePicInfo")?.optJSONObject("picInfo")?.str("pic"),
          // v1 焦点通知：图片 key 直接写在根节点（aodPic / tickerPic）
          v2.str("aodPic"),
          v2.str("tickerPic"),
      )
    }

    private fun JSONObject.progress(): Int? = optInt("progress", -1).takeIf { it in 0..100 }

    private fun JSONObject.str(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONArray?.objects(field: String): List<String> {
      if (this == null) return emptyList()
      return (0 until length()).mapNotNull { i -> (opt(i) as? JSONObject)?.str(field) }
    }

    /**
     * `actions` 有两种写法：
     * - `{ "action": "miui.focus.action_1_v2" }`：key 指向 `miui.focus.actions` 里的 Notification.Action
     * - `{ "actionTitle": ..., "actionIcon": ..., "actionIntent": ..., "actionIntentType": 1|2|3
     *   }`：内联 两者可同时存在（取方式一的 Icon/Title + 方式二的 PendingIntent）。
     */
    private fun JSONArray?.actions(): List<FocusAction> {
      if (this == null) return emptyList()
      return (0 until length()).mapNotNull { i ->
        val item =
            ((opt(i) as? JSONObject)?.let { it.optJSONObject("actionInfo") ?: it })
                ?: return@mapNotNull null
        FocusAction(
                key = item.str("action"),
                title = item.str("actionTitle"),
                iconKey = item.str("actionIcon"),
                intentUri = item.str("actionIntent"),
                intentType = item.optInt("actionIntentType", 1),
            )
            .takeIf { it.key != null || it.intentUri != null }
      }
    }

    private fun firstNotBlank(vararg values: String?): String? = values.firstOrNull {
      !it.isNullOrBlank()
    }

    private fun String.toColor(): Int? = runCatching { toColorInt() }.getOrNull()
  }
}

/** 一个按钮：方式一（Bundle key）或方式二（内联 actionInfo） */
data class FocusAction(
    val key: String? = null,
    val title: String? = null,
    val iconKey: String? = null,
    val intentUri: String? = null,
    /** 1: url to activity，2: action to broadcast，3: action to service */
    val intentType: Int = 1,
)
