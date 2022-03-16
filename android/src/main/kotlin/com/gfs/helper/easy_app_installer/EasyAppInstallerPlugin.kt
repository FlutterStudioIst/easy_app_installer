package com.gfs.helper.easy_app_installer

import android.app.Activity
import androidx.annotation.NonNull
import androidx.lifecycle.Lifecycle
import com.fs.freedom.basic.helper.AppHelper
import com.fs.freedom.basic.helper.DownloadHelper
import com.fs.freedom.basic.helper.SystemHelper
import com.fs.freedom.basic.listener.CommonResultListener
import com.gfs.helper.easy_app_installer.comments.CustomLifecycleObserver
import com.gfs.helper.easy_app_installer.comments.InstallApkState
import com.gfs.helper.easy_app_installer.model.InstallApkModel

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File

/** EasyAppInstallerPlugin */
class EasyAppInstallerPlugin: FlutterPlugin, MethodCallHandler, ActivityAware {

  private lateinit var mChannel : MethodChannel
  private var mInstallApkModel = InstallApkModel()
  private var mActivity: Activity? = null
  private var mLifecycle: Lifecycle? = null
  private val mLifecycleObserver = object : CustomLifecycleObserver {
    override fun onResume() {
      //校验是否获取到了权限
      if (mInstallApkModel.isIntoOpenPermissionPage) {
        when (mInstallApkModel.currentState) {
          InstallApkState.INSTALL -> {
            installApk(mInstallApkModel.arguments, mInstallApkModel.result)
          }
          InstallApkState.DOWNLOAD_AND_INSTALL -> {
            downloadAndInstallApk(mInstallApkModel.arguments, mInstallApkModel.result)
          }
          else -> {}
        }
      }
    }
  }

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    mChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "easy_app_installer")
    mChannel.setMethodCallHandler(this)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    val arguments = call.arguments as Map<*, *>?
    when (call.method) {
      "getPlatformVersion" -> {
        result.success("Android ${android.os.Build.VERSION.RELEASE}")
      }
      "installApk" -> {
        installApk(arguments, result)
      }
      "downloadAndInstallApk" -> {
        downloadAndInstallApk(arguments, result)
      }
      "cancelDownload" -> {
        cancelDownload(arguments, result)
      }
      "openAppMarket" -> {
        openAppMarket(arguments, result)
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  /**
   * 打开应用市场-当前应用详情页
   */
  private fun openAppMarket(arguments: Map<*, *>?, result: Result) {
    val targetMarketPackageName = arguments?.get("targetMarketPackageName") as String? ?: ""
    val isOpenSystemMarket = arguments?.get("isOpenSystemMarket") as Boolean? ?: true
    val openResult = AppHelper.openAppMarket(
      mActivity,
      targetMarketPackageName = targetMarketPackageName,
      isOpenSystemMarket = isOpenSystemMarket
    )

    if (openResult) {
      result.success(true)
    } else {
      result.error("openAppMarket", "open market failed!", "")
    }
  }

  /**
   * 下载apk并安装
   */
  private fun downloadAndInstallApk(arguments: Map<*, *>?, result: Result?) {
    val fileUrl = arguments?.get("fileUrl") as String? ?: ""
    val fileDirectory = arguments?.get("fileDirectory") as String? ?: ""
    val fileName = arguments?.get("fileName") as String? ?: ""
    val isDeleteOriginalFile = arguments?.get("isDeleteOriginalFile") as Boolean? ?: true

    mInstallApkModel = mInstallApkModel.copyWith(
      arguments = arguments,
      result = result,
      currentState = InstallApkState.DOWNLOAD_AND_INSTALL
    )

    SystemHelper.downloadAndInstallApk(
      activity = mActivity,
      fileUrl = fileUrl,
      filePath = "${mActivity?.filesDir}/$fileDirectory/",
      fileName = fileName,
      isDeleteOriginalFile = isDeleteOriginalFile,
      commonResultListener = object :CommonResultListener<File> {
        override fun onStart(attachParam: Any?) {
          resultCancelTag(attachParam)
          mInstallApkModel.isIntoOpenPermissionPage = false
        }

        override fun onSuccess(file: File) {
          val map = mapOf<String, Any>(
            "apkPath" to file.absolutePath
          )
          result?.success(map)
        }

        override fun onError(message: String) {
          if (message == SystemHelper.OPEN_INSTALL_PACKAGE_PERMISSION) {
            mInstallApkModel.isIntoOpenPermissionPage = true
          } else {
            result?.error("0", message, "")
          }
        }

        override fun onProgress(currentProgress: Float) {
          resultDownloadProgress(currentProgress)
        }
      }
    )
  }

  /**
   * 取消下载
   */
  private fun cancelDownload(arguments: Map<*, *>?, result: Result?) {
    val cancelTag = arguments?.get("cancelTag") as String? ?: ""
    if (cancelTag.isEmpty()) {
      result?.error("cancelDownload", "cancelTag is must not be null!", "")
      return
    }
    DownloadHelper.cancelDownload(cancelTag)
    result?.success(true)
  }

  /**
   * 安装apk
   */
  private fun installApk(arguments: Map<*, *>?, result: Result?) {
    val filePath = arguments?.get("filePath") as String? ?: ""
    if (filePath.isNotEmpty()) {
      mInstallApkModel = mInstallApkModel.copyWith(
        arguments = arguments,
        result = result,
        currentState = InstallApkState.INSTALL
      )
      SystemHelper.installApk(mActivity, apkFile = File(filePath), commonResultListener = object : CommonResultListener<File> {
        override fun onStart(attachParam: Any?) {
          mInstallApkModel.isIntoOpenPermissionPage = false
        }

        override fun onSuccess(file: File) {
          val map = mapOf<String, Any>(
            "apkPath" to file.absolutePath
          )
          result?.success(map)
        }

        override fun onError(message: String) {
          if (message == SystemHelper.OPEN_INSTALL_PACKAGE_PERMISSION) {
            mInstallApkModel.isIntoOpenPermissionPage = true
          } else {
            result?.error("0", message, "")
          }
        }

      })
    } else {
      result?.error("0", "installApk：file path can't be empty!", "")
    }
  }

  /**
   * 回调下载进度
   */
  private fun resultDownloadProgress(progress: Float) {
    mChannel.invokeMethod("resultDownloadProgress", progress)
  }

  /**
   * 回调用于取消下载的 cancelTag
   */
  private fun resultCancelTag(attachParam: Any?) {
    if (attachParam is String && attachParam.isNotEmpty()) {
      mChannel.invokeMethod("resultCancelTag", attachParam)
    }
  }


  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    mChannel.setMethodCallHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    mActivity = binding.activity
    mLifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding)
    mLifecycle?.addObserver(mLifecycleObserver)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    mActivity = null

  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    mActivity = binding.activity

  }

  override fun onDetachedFromActivity() {
    mActivity = null
    mLifecycle?.removeObserver(mLifecycleObserver)
    mLifecycle = null
  }

}
