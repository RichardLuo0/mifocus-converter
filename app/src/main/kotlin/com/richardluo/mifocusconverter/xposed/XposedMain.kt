package com.richardluo.mifocusconverter.xposed

import com.richardluo.mifocusconverter.BuildConfig
import com.richardluo.mifocusconverter.utils.Logger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XposedMain : XposedModule() {
  init {
    Logger.init(this)
  }

  val hookList = listOfNotNull(LiveUpdateHook(), FakeFocusSupport(), SmsCodeCopyHook())

  override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
    hookList.forEach { it.onHookSystem(param) }
  }

  override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
    if (!param.isFirstPackage) return
    if (param.packageName == BuildConfig.APPLICATION_ID) return

    hookList.forEach { it.onHookApp(param) }
    when (param.packageName) {
      "com.android.systemui" -> hookList.forEach { it.onHookSystemUI(param) }
      "com.android.settings" -> hookList.forEach { it.onHookSettings(param) }
    }
  }
}
