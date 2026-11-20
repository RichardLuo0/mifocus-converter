package com.richardluo.mifocusconverter.reflect

import android.app.Notification
import com.richardluo.mifocusconverter.utils.allMethods
import com.richardluo.mifocusconverter.utils.classOf
import java.lang.reflect.Method

object NotificationManagerService {
  private const val CLASS_NAME = "com.android.server.notification.NotificationManagerService"

  private var cachedClazz: Class<*>? = null
  private var cachedMethod: Method? = null

  /**
   * `NotificationManagerService` 的 Class，找到一次就够（同一进程里 loader 是固定的）。
   * 不叫 `getClass`：那和 `Object.getClass()` 的 JVM 签名冲突。
   */
  fun getClazz(classLoader: ClassLoader?): Class<*>? =
      cachedClazz ?: classOf(CLASS_NAME, classLoader)?.also { cachedClazz = it }

  /**
   * 缓存 `enqueueNotificationInternal`：只取参数最多的那个重载。
   * 外层重载最终都会委托给它，它是真正干活的实现体；且在 `fixNotification()` 之前，
   * 正好是 NMS 计算 `FLAG_PROMOTED_ONGOING` 的入口。
   */
  fun getEnqueueNotificationInternalM(classLoader: ClassLoader?): Method? =
      cachedMethod
          ?: getClazz(classLoader)
              ?.allMethods("enqueueNotificationInternal")
              ?.filter { m ->
                m.parameterTypes.any { Notification::class.java.isAssignableFrom(it) }
              }
              ?.maxByOrNull { it.parameterTypes.size }
              ?.also { cachedMethod = it }
}
