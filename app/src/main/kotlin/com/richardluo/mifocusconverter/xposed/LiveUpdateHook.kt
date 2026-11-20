package com.richardluo.mifocusconverter.xposed

import android.app.Notification
import com.richardluo.mifocusconverter.notification.LiveUpdateConverter
import com.richardluo.mifocusconverter.reflect.NotificationManagerService
import com.richardluo.mifocusconverter.utils.allMethods
import com.richardluo.mifocusconverter.utils.asType
import com.richardluo.mifocusconverter.utils.field
import com.richardluo.mifocusconverter.utils.getAs
import com.richardluo.mifocusconverter.utils.hookCompat
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

/**
 * 在 system_server 里拦截通知入队，把 MiPush 焦点通知改写成 Live Update。
 *
 * 选 system_server 而不是 SystemUI：`FLAG_PROMOTED_ONGOING` 由 NMS 在入队时计算并写进 NotificationRecord，SystemUI
 * 只是渲染方；在 SystemUI 改只能影响它自己的展示， 锁屏 / AOD / 其他监听者仍然拿到未提升的通知，而且系统每次更新都会用 NMS 的副本覆盖。
 */
class LiveUpdateHook : Hook {
  private companion object {
    /** Notification.FLAG_PROMOTED_ONGOING */
    const val FLAG_PROMOTED_ONGOING = 0x00040000

    const val FIX_NOTIFICATION_WITH_CHANNEL = "fixNotificationWithChannel"
  }

  context(xposed: XposedInterface)
  override fun onHookSystem(param: XposedModuleInterface.SystemServerStartingParam) {
    val method =
        NotificationManagerService.getEnqueueNotificationInternalM(param.classLoader) ?: return
    val notificationIndex =
        method.parameterTypes.indexOfFirst { Notification::class.java.isAssignableFrom(it) }
    // enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification, ...)
    val pkgIndex = if (method.parameterTypes.firstOrNull() == String::class.java) 0 else -1
    val contextField = NotificationManagerService.getClazz(param.classLoader)?.field("mContext")

    method.hookCompat {
      before {
        val notification = args.getOrNull(notificationIndex).asType<Notification>() ?: return@before
        val pkg = args.getOrNull(pkgIndex).asType<String>() ?: return@before
        LiveUpdateConverter.convert(notification, pkg, contextField?.getAs(thisObject))
      }
    }

    // Android 17 起 NMS 还要求应用持有 POST_PROMOTED_NOTIFICATIONS（normal|appop），
    // 没声明的应用拿不到。fixNotificationWithChannel 是最后一步，这里直接补 flag。
    NotificationManagerService.getClazz(param.classLoader)
        ?.allMethods(FIX_NOTIFICATION_WITH_CHANNEL)
        ?.forEach { fixMethod ->
          val index =
              fixMethod.parameterTypes.indexOfFirst {
                Notification::class.java.isAssignableFrom(it)
              }
          if (index < 0) return@forEach

          fixMethod.hookCompat {
            after {
              val notification = args.getOrNull(index).asType<Notification>() ?: return@after
              if (
                  notification.extras?.getBoolean(LiveUpdateConverter.EXTRA_CONVERTED, false) ==
                      true
              ) {
                notification.flags = notification.flags or FLAG_PROMOTED_ONGOING
              }
            }
          }
        }
  }
}
