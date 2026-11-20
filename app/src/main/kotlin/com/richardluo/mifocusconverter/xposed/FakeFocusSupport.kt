package com.richardluo.mifocusconverter.xposed

import android.os.Bundle
import com.richardluo.mifocusconverter.utils.Logger.log
import com.richardluo.mifocusconverter.utils.allMethods
import com.richardluo.mifocusconverter.utils.classOf
import com.richardluo.mifocusconverter.utils.hookCompat
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

/**
 * 让应用以为系统支持焦点通知。全部在 system_server 里改，不 hook 任何应用，也不启动额外进程。
 *
 * 文档里应用判断“支不支持”的两个接口：
 * 1. `Settings.System.getInt(cr, "notification_focus_protocol", 0)` —— 所有进程最终都会 binder 到 system
 *    进程里的 SettingsProvider。
 * 2. `hasFocusPermission()` 里的
 *    `ContentResolver.call("content://miui.statusbar.notification.public", "canShowFocus", ...)` ——
 *    AOSP 上没有这个 authority，直接 `call` 会在解析 provider 时失败。
 *
 * 两件事都在 `ContentProvider$Transport.call`（boot class，所有 provider 的 call 入口）里拦：
 * - 改 SettingsProvider 的那个 call 返回值即可，不用去加载 SettingsProvider 自己的类 （它被 system 进程用单独的 classloader
 *   加载，`Class.forName` 未必找得到）；
 * - 我们的 authority 先在 AMS 解析 provider 时重定向到 `settings`（system 进程常驻，不会拉新进程）， 拿到 settings 的 binder
 *   后，客户端传的 authority 仍是 `miui.statusbar.notification.public`，按它精确匹配。
 */
class FakeFocusSupport : Hook {
  private companion object {
    const val KEY_PROTOCOL = "notification_focus_protocol"
    const val PROTOCOL_OS3 = 3

    // Settings.NameValueTable.VALUE
    const val SETTINGS_VALUE = "value"
    // Settings.CALL_METHOD_GET_SYSTEM
    const val CALL_METHOD_GET_SYSTEM = "GET_system"

    const val FOCUS_PROVIDER_AUTHORITY = "miui.statusbar.notification.public"
    const val METHOD_CAN_SHOW_FOCUS = "canShowFocus"
    const val KEY_CAN_SHOW_FOCUS = "canShowFocus"

    /** 借用的真实 provider：system 进程里常驻，拿到它的 binder 不会启动任何新进程 */
    const val PROXY_PROVIDER_AUTHORITY = "settings"
    const val SETTINGS_AUTHORITY = "settings"
  }

  context(xposed: XposedInterface)
  override fun onHookSystem(param: XposedModuleInterface.SystemServerStartingParam) {
    hookProviderAlias(param.classLoader)
    hookProviderCalls(param.classLoader)
  }

  /**
   * `ActivityManagerService.getContentProvider(IApplicationThread, String callingPackage, String
   * name, int userId, boolean stable)`：把我们的 authority 换成 `settings`。 返回的 holder / connection /
   * 引用计数都是 settings 的真实数据，客户端只会拿它的 binder 调 call。
   */
  context(xposed: XposedInterface)
  private fun hookProviderAlias(classLoader: ClassLoader?) {
    val ams = classOf("com.android.server.am.ActivityManagerService", classLoader) ?: return

    ams.allMethods("getContentProvider").forEach { method ->
      // 第二个 String 参数是 authority（第一个是 callingPackage）
      val nameIndex =
          method.parameterTypes
              .withIndex()
              .filter { it.value == String::class.java }
              .getOrNull(1)
              ?.index ?: return@forEach

      method.hookCompat {
        before {
          if (args.getOrNull(nameIndex) == FOCUS_PROVIDER_AUTHORITY) {
            args[nameIndex] = PROXY_PROVIDER_AUTHORITY
          }
        }
      }
    }
  }

  /** 所有 provider 的 call 都会经过这里（settings provider 就跑在 system 进程） */
  context(xposed: XposedInterface)
  private fun hookProviderCalls(classLoader: ClassLoader?) {
    val transport = classOf($$"android.content.ContentProvider$Transport", classLoader) ?: return

    // call(AttributionSource, String authority, String method, String arg, Bundle extras)
    transport.allMethods("call").hookCompat {
      before {
        val authority = args.getOrNull(1) as? String ?: return@before
        val method = args.getOrNull(2) as? String ?: return@before

        when (authority) {
          FOCUS_PROVIDER_AUTHORITY if method == METHOD_CAN_SHOW_FOCUS -> {
            result = Bundle().apply { putBoolean(KEY_CAN_SHOW_FOCUS, true) }
          }

          SETTINGS_AUTHORITY if
              method == CALL_METHOD_GET_SYSTEM && args.getOrNull(3) == KEY_PROTOCOL
           -> {
            result = Bundle().apply { putString(SETTINGS_VALUE, PROTOCOL_OS3.toString()) }
            log("$KEY_PROTOCOL -> $PROTOCOL_OS3")
          }
        }
      }
    }
  }
}
