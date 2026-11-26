package com.smartstockmobile

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager

class ScannerFeedbackModule(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  override fun getName() = "SmartStockScannerFeedback"
  @ReactMethod fun beep() {
    // Notification stream respects the user's volume. This tone signals a read,
    // never a successful stock movement. No microphone/audio recording needed.
    Handler(Looper.getMainLooper()).post {
      try {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 180)
      } catch (_: RuntimeException) { /* Audio must never block the scan. */ }
    }
  }
}
class ScannerFeedbackPackage : ReactPackage {
  override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(ScannerFeedbackModule(context))
  override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
