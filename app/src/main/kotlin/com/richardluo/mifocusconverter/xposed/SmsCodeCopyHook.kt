package com.richardluo.mifocusconverter.xposed

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import android.provider.Telephony
import com.richardluo.mifocusconverter.BuildConfig
import com.richardluo.mifocusconverter.R
import com.richardluo.mifocusconverter.notification.SmsCode
import com.richardluo.mifocusconverter.reflect.ActivityThread
import com.richardluo.mifocusconverter.reflect.NotificationManagerService
import com.richardluo.mifocusconverter.utils.Logger.log
import com.richardluo.mifocusconverter.utils.Logger.logE
import com.richardluo.mifocusconverter.utils.asType
import com.richardluo.mifocusconverter.utils.field
import com.richardluo.mifocusconverter.utils.getAs
import com.richardluo.mifocusconverter.utils.hookCompat
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

/**
 * 短信通知里出现「验证码」时，提取验证码并给通知加一个按钮（按钮文案就是验证码本身）。
 *
 * 点击后不拉起任何应用进程：PendingIntent 是一个指向 system 包自身的广播， 由 system_server 里动态注册的 receiver 直接把验证码写进剪贴板。
 */
class SmsCodeCopyHook : Hook {
  private companion object {
    const val ACTION_COPY_CODE = "com.richardluo.mifocusconverter.COPY_CODE"
    const val EXTRA_CODE = "code"

    const val EXTRA_TITLE = "android.title"
    const val EXTRA_TEXT = "android.text"
    const val EXTRA_BIG_TEXT = "android.bigText"
    const val EXTRA_SUB_TEXT = "android.subText"

    /** 拿不到默认短信应用时默认 Google 信息 */
    const val DEFAULT_SMS_PACKAGE = "com.google.android.apps.messaging"
  }

  @Volatile private var receiverRegistered = false

  private val copyReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          val code = intent.getStringExtra(EXTRA_CODE) ?: return
          context
              .getSystemService(ClipboardManager::class.java)
              ?.setPrimaryClip(ClipData.newPlainText("验证码", code))
        }
      }

  context(xposed: XposedInterface)
  override fun onHookSystem(param: XposedModuleInterface.SystemServerStartingParam) {
    val method =
        NotificationManagerService.getEnqueueNotificationInternalM(param.classLoader) ?: return
    val notificationIndex =
        method.parameterTypes.indexOfFirst { Notification::class.java.isAssignableFrom(it) }
    val pkgIndex = if (method.parameterTypes.firstOrNull() == String::class.java) 0 else -1
    val contextField = NotificationManagerService.getClazz(param.classLoader)?.field("mContext")

    method.hookCompat {
      before {
        val notification = args.getOrNull(notificationIndex).asType<Notification>() ?: return@before
        val pkg = args.getOrNull(pkgIndex).asType<String>() ?: return@before
        val context = contextField?.getAs<Context>(thisObject) ?: return@before
        runCatching { addCopyAction(notification, pkg, context) }
            .onFailure { logE("add copy action failed: $it") }
      }
    }
  }

  private val smsPackage: String by lazy {
    val pkg =
        ActivityThread.systemContext()?.let {
          runCatching { Telephony.Sms.getDefaultSmsPackage(it) }.getOrNull()
        } ?: DEFAULT_SMS_PACKAGE
    log("sms app: $pkg")
    pkg
  }

  private fun addCopyAction(notification: Notification, pkg: String, context: Context) {
    // 严格只处理默认短信应用；拿不到默认短信应用时才默认 Google 信息
    if (pkg != smsPackage) return

    val texts = textsOf(notification.extras)
    log("$pkg: sms texts=${texts.joinToString(" | ")}")

    val actions = notification.actions
    val code = SmsCode.extract(texts)
    if (code == null) {
      log("$pkg: no sms code, extras=${notification.extras?.keySet()}")
      return
    }

    ensureReceiver(context)
    notification.actions = arrayOf(buildCopyAction(code, context)) + (actions ?: emptyArray())
    log("$pkg: add copy action for sms code $code")
  }

  private fun textsOf(extras: Bundle?): List<CharSequence> {
    if (extras == null) return emptyList()
    return buildList {
      extras.getCharSequence(EXTRA_TITLE)?.let(::add)
      extras.getCharSequence(EXTRA_TEXT)?.let(::add)
      extras.getCharSequence(EXTRA_BIG_TEXT)?.let(::add)
      extras.getCharSequence(EXTRA_SUB_TEXT)?.let(::add)
    }
  }

  private fun ensureReceiver(context: Context) {
    if (receiverRegistered) return
    receiverRegistered = true
    // hook 跑在应用发通知的 binder 调用里，这里是 system context（包名 android），
    // 必须以 system_server 身份注册，否则会 "not allowed to send as package android"
    val token = Binder.clearCallingIdentity()
    try {
      context.registerReceiver(
          copyReceiver,
          IntentFilter(ACTION_COPY_CODE),
          Context.RECEIVER_NOT_EXPORTED,
      )
    } finally {
      Binder.restoreCallingIdentity(token)
    }
  }

  private fun buildCopyAction(code: String, context: Context): Notification.Action {
    val intent = Intent(ACTION_COPY_CODE).setPackage(context.packageName).putExtra(EXTRA_CODE, code)
    val pendingIntent = withSystemIdentity {
      PendingIntent.getBroadcast(
          context,
          code.hashCode(),
          intent,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    }
    val icon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_content_copy)
    return Notification.Action.Builder(icon, copyTitle(code), pendingIntent).build()
  }

  private fun copyTitle(code: String) = "复制 $code"

  private inline fun <T> withSystemIdentity(block: () -> T): T {
    val token = Binder.clearCallingIdentity()
    return try {
      block()
    } finally {
      Binder.restoreCallingIdentity(token)
    }
  }
}
