package com.richardluo.mifocusconverter.reflect

import android.app.Application
import android.content.Context
import com.richardluo.mifocusconverter.utils.call
import com.richardluo.mifocusconverter.utils.classOf
import com.richardluo.mifocusconverter.utils.method

object ActivityThread {
  private val clazz: Class<*>? by lazy {
    classOf("android.app.ActivityThread")
  }

  private val currentApplicationM by lazy {
    clazz?.method("currentApplication")
  }

  fun currentApplication(): Application? = currentApplicationM?.call()

  private val currentActivityThreadM by lazy {
    clazz?.method("currentActivityThread")
  }

  private val getSystemContextM by lazy {
    clazz?.method("getSystemContext")
  }

  /** system_server 里没有 Application，只能拿 system context */
  fun systemContext(): Context? =
      currentActivityThreadM?.call<Any>()?.let { getSystemContextM?.call<Context>(it) }

  private val currentPackageNameM by lazy {
    clazz?.method("currentPackageName")
  }

  fun currentPackageName(): String? = currentPackageNameM?.call()
}
