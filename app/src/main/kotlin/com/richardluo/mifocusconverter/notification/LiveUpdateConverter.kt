package com.richardluo.mifocusconverter.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import com.richardluo.mifocusconverter.utils.Logger.log
import com.richardluo.mifocusconverter.utils.Logger.logD

/**
 * 把 MiPush 下发的「小米焦点通知」改写成 Android 16 的 Live Update（promoted ongoing notification）。
 *
 * 关键点：`FLAG_PROMOTED_ONGOING` 由 system_server 的 NotificationManagerService 授予， 应用（以及
 * SystemUI）自己设置会被 `fixNotification()` 清掉。NMS 的判断条件是 `Notification#hasPromotableCharacteristics()`：
 * - 请求标记：`android.requestPromotedOngoing`（16 QPR1 / 17）
 * - 必须是 ongoing、有标题、样式可提升（无样式 / BigText / Call / Metric / Progress）
 * - 不是 group summary、没有自定义 RemoteViews
 * - 16.0：需要 colorized；16 QPR1+：需要**非** colorized
 */
object LiveUpdateConverter {
  const val EXTRA_CONVERTED = "com.richardluo.mifocusconverter.converted"

  private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
  private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
  private const val EXTRA_COLORIZED = "android.colorized"
  private const val EXTRA_TEMPLATE = "android.template"
  private const val EXTRA_TITLE = "android.title"
  private const val EXTRA_TEXT = "android.text"
  private const val EXTRA_BIG_TEXT = "android.bigText"
  private const val EXTRA_PROGRESS = "android.progress"
  private const val EXTRA_PROGRESS_MAX = "android.progressMax"
  private const val EXTRA_PROGRESS_INDETERMINATE = "android.progressIndeterminate"
  private const val EXTRA_STYLED_BY_PROGRESS = "android.styledByProgress"
  private const val EXTRA_LARGE_ICON = "android.largeIcon"

  private const val TEMPLATE_BIG_TEXT = $$"android.app.Notification$BigTextStyle"
  private const val TEMPLATE_CALL = $$"android.app.Notification$CallStyle"
  private const val TEMPLATE_METRIC = $$"android.app.Notification$MetricStyle"
  private const val TEMPLATE_PROGRESS = $$"android.app.Notification$ProgressStyle"

  private const val FLAG_ONGOING_EVENT = 0x2
  private const val FLAG_AUTO_CANCEL = 0x10
  private const val FLAG_GROUP_SUMMARY = 0x200

  /** 岛最多 3 个圆形/进度按钮，AOSP 也只用前几个 */
  private const val MAX_ACTIONS = 3

  /** `hasPromotableStyle()` 允许的模板，null 表示标准模板 */
  private val PROMOTABLE_TEMPLATES = setOf(TEMPLATE_BIG_TEXT, TEMPLATE_CALL, TEMPLATE_METRIC)

  /** chip 文案建议不超过 7 个字符 */
  private const val CHIP_MAX_LENGTH = 7

  /** v1 焦点通知（无 param_v2）里图片的固定 key */
  private const val FALLBACK_IMAGE_KEY = "miui.focus.pic_large"

  /** Android 16 QPR1 起才有「主动请求上 Live Update」的 API；16.0 只能靠 colorized + ongoing 触发。 */
  private val supportsPromotionRequest: Boolean by lazy {
    runCatching { Notification::class.java.getDeclaredMethod("isRequestPromotedOngoing") }.isSuccess
  }

  fun convert(notification: Notification, pkg: String, context: Context? = null): Boolean =
      runCatching {
        doConvert(notification, pkg, context)
      }
      .onFailure { log("$pkg: convert failed: $it") }
      .getOrDefault(false)

  private fun doConvert(notification: Notification, pkg: String, context: Context?): Boolean {
    val extras = notification.extras ?: return false
    if (!FocusParam.isFocusNotification(extras)) return false

    // 保险：同一个通知理论上只会经过一次被 hook 的重载
    if (extras.getBoolean(EXTRA_CONVERTED, false)) return true

    // 值不一定是 String，getString() 会抛 ClassCastException，由外层 runCatching 兜住
    val param = FocusParam.parse(extras.getString(FocusParam.EXTRA_PARAM))
    if (param == null) {
      log("$pkg: focus param exists but cannot be parsed")
      return false
    }

    if (notification.flags and FLAG_GROUP_SUMMARY != 0) {
      logD("$pkg: skip group summary")
      return false
    }

    return convertInternal(notification, extras, param, pkg, context)
  }

  @Suppress("DEPRECATION")
  private fun convertInternal(
      notification: Notification,
      extras: Bundle,
      param: FocusParam,
      pkg: String,
      context: Context?,
  ): Boolean {
    // 先确认能拿到标题，避免改了一半再放弃
    val dataTitle = param.title ?: param.content
    if (extras.getCharSequence(EXTRA_TITLE).isNullOrBlank() && dataTitle.isNullOrBlank()) {
      // hasTitle() 不通过就不会被提升
      log("$pkg: no title, skip")
      return false
    }

    // Live Update 必须是 ongoing，且不能自动消失
    notification.flags = (notification.flags or FLAG_ONGOING_EVENT) and FLAG_AUTO_CANCEL.inv()

    // 自定义 RemoteViews 会让通知失去可提升性
    notification.contentView = null
    notification.bigContentView = null
    notification.headsUpContentView = null

    // 版本间语义相反：16.0 要求 colorized，16 QPR1+ 要求非 colorized
    extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
    if (supportsPromotionRequest) {
      extras.remove(EXTRA_COLORIZED)
    } else {
      extras.putBoolean(EXTRA_COLORIZED, true)
    }

    if (extras.getCharSequence(EXTRA_TITLE).isNullOrBlank() && !dataTitle.isNullOrBlank()) {
      extras.putCharSequence(EXTRA_TITLE, dataTitle)
    }

    val template = extras.getString(EXTRA_TEMPLATE)
    if (template != null && template !in PROMOTABLE_TEMPLATES && template != TEMPLATE_PROGRESS) {
      // BigPicture / Messaging / Inbox / Media / 自定义样式都不允许上岛，降级成 BigText
      extras.putString(EXTRA_TEMPLATE, TEMPLATE_BIG_TEXT)
      val text = param.content ?: extras.getCharSequence(EXTRA_TEXT)?.toString()
      if (!text.isNullOrBlank()) extras.putCharSequence(EXTRA_BIG_TEXT, text)
    }

    // 状态栏 chip 文案：优先 hintInfo.title / ticker
    val chipText = param.chipText ?: param.title
    if (!chipText.isNullOrBlank()) {
      extras.putString(EXTRA_SHORT_CRITICAL_TEXT, chipText.trim().take(CHIP_MAX_LENGTH))
    }

    applyProgress(extras, param)
    applyImage(extras, param)
    applyActions(notification, extras, param, pkg, context)
    extras.putBoolean(EXTRA_CONVERTED, true)

    log(
        "$pkg: converted focus notification to live update" +
            (param.business?.let { " (business=$it)" } ?: ""),
    )
    return true
  }

  private fun applyProgress(extras: Bundle, param: FocusParam) {
    val progress =
        param.progress
            ?: run {
              val max = extras.getInt(EXTRA_PROGRESS_MAX, 0)
              val current = extras.getInt(EXTRA_PROGRESS, 0)
              if (max <= 0 || extras.getBoolean(EXTRA_PROGRESS_INDETERMINATE, false)) null
              else (current * 100f / max).toInt()
            }
            ?: return

    val template = extras.getString(EXTRA_TEMPLATE)
    if (template == TEMPLATE_CALL || template == TEMPLATE_METRIC) return

    extras.putString(EXTRA_TEMPLATE, TEMPLATE_PROGRESS)
    extras.putInt(EXTRA_PROGRESS, progress.coerceIn(0, 100))
    extras.putInt(EXTRA_PROGRESS_MAX, 100)
    extras.putBoolean(EXTRA_PROGRESS_INDETERMINATE, false)
    extras.putBoolean(EXTRA_STYLED_BY_PROGRESS, true)
  }

  /** 小米把图片放在 `miui.focus.pics` Bundle 里，JSON 中的值就是这个 Bundle 的 key */
  private fun applyImage(extras: Bundle, param: FocusParam) {
    if (extras.containsKey(EXTRA_LARGE_ICON)) return
    val pics = extras.getBundle(FocusParam.EXTRA_PICS)
    // 兜底：v1 焦点通知的图片 key 不一定写进 JSON，固定叫 pic_large
    val keys = param.imageKeys.ifEmpty { listOf(FALLBACK_IMAGE_KEY) }
    val icon = keys.firstNotNullOfOrNull { key -> pics?.icon(key) ?: extras.icon(key) } ?: return
    extras.putParcelable(EXTRA_LARGE_ICON, icon)
  }

  private fun Bundle.icon(key: String): Icon? = runCatching {
    getParcelable(key, Icon::class.java)
  }
      .getOrNull()

  private fun applyActions(
      notification: Notification,
      extras: Bundle,
      param: FocusParam,
      pkg: String,
      context: Context?,
  ) {
    if (!notification.actions.isNullOrEmpty() || param.actions.isEmpty()) return
    val actionBundle = extras.getBundle(FocusParam.EXTRA_ACTIONS)
    val pics = extras.getBundle(FocusParam.EXTRA_PICS)

    val actions =
        param.actions.take(MAX_ACTIONS).mapIndexedNotNull { index, spec ->
          val fromBundle =
              spec.key?.let { actionBundle?.getParcelable(it, Notification.Action::class.java) }
          val pendingIntent = buildPendingIntent(spec, index, pkg, context)
          val title: CharSequence =
              (fromBundle?.title ?: spec.title) ?: return@mapIndexedNotNull fromBundle
          if (pendingIntent == null) return@mapIndexedNotNull fromBundle

          // 两者同时存在时：Icon/Title 用方式一，PendingIntent 用方式二
          val icon: Icon =
              fromBundle?.getIcon()
                  ?: spec.iconKey?.let { pics?.icon(it) ?: extras.icon(it) }
                  ?: notification.smallIcon
                  ?: return@mapIndexedNotNull fromBundle
          Notification.Action.Builder(icon, title, pendingIntent).build()
        }

    if (actions.isNotEmpty()) notification.actions = actions.toTypedArray()
  }

  private fun buildPendingIntent(
      spec: FocusAction,
      requestCode: Int,
      pkg: String,
      context: Context?,
  ): PendingIntent? {
    val uri = spec.intentUri ?: return null
    if (context == null) {
      log("$pkg: no context, skip action $uri")
      return null
    }
    val intent =
        runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
    if (!intent.constrainTo(pkg)) {
      log("$pkg: action intent targets another package, ignored: $uri")
      return null
    }
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    // hook 跑在应用发通知的 binder 调用里，创建 PendingIntent 必须用 system_server 身份，
    // 否则 getIntentSender 会报 "not allowed to send as package android"
    val token = Binder.clearCallingIdentity()
    return try {
      runCatching {
        when (spec.intentType) {
          2 ->
              PendingIntent.getBroadcast(
                  context,
                  requestCode,
                  intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                  flags,
              )
          3 -> PendingIntent.getService(context, requestCode, intent, flags)
          else -> PendingIntent.getActivity(context, requestCode, intent, flags)
        }
      }
          .getOrNull()
    } finally {
      Binder.restoreCallingIdentity(token)
    }
  }

  /**
   * `actionIntent` 是应用可控的 Intent URI，而 PendingIntent 会以 system 身份创建。 不做限制就等价于把「以 system
   * 启动任意组件」的能力交给了任意应用，所以强制只能落在 发通知的应用自己身上，并去掉 URI 授权相关的 flag。
   */
  private fun Intent.constrainTo(pkg: String): Boolean {
    component?.let { if (it.packageName != pkg) return false }
    selector?.let { sel ->
      if (sel.component?.packageName?.let { it != pkg } == true) return false
      sel.setPackage(pkg)
    }
    setPackage(pkg)
    clipData = null
    flags =
        flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                .inv()
    return true
  }
}
