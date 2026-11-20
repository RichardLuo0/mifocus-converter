package com.richardluo.mifocusconverter.utils

import android.util.Log
import android.util.Log.ERROR
import android.util.Log.INFO
import com.richardluo.mifocusconverter.BuildConfig
import com.richardluo.mifocusconverter.reflect.ActivityThread
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference

object Logger {
  private var xposed: WeakReference<XposedInterface>? = null

  private const val TAG = "[MiFocus Converter]"

  private val currentPackageName: String by lazy { ActivityThread.currentPackageName() ?: "" }

  fun init(xposed: XposedInterface) {
    this.xposed = WeakReference(xposed)
  }

  fun log(text: String) {
    val xposed = xposed?.get()
    if (xposed != null) xposed.log(INFO, TAG, "$currentPackageName: $text") else Log.i(TAG, text)
  }

  fun logE(text: String) {
    val xposed = xposed?.get()
    if (xposed != null) xposed.log(ERROR, TAG, "$currentPackageName error: $text")
    else Log.e(TAG, text)
  }

  fun logE(t: Throwable) {
    val xposed = xposed?.get()
    if (xposed != null) xposed.log(ERROR, TAG, "$currentPackageName error", t)
    else Log.e(TAG, "", t)
  }

  fun logD(text: String) {
    if (BuildConfig.DEBUG) log(text)
  }
}
