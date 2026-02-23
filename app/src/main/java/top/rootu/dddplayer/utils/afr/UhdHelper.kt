package top.rootu.dddplayer.utils.afr

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Display
import android.view.Window
import android.view.WindowManager
import top.rootu.dddplayer.utils.afr.DisplayHolder.Mode
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

interface UhdHelperListener {
    fun onModeChanged(mode: Mode?)
}

class UhdHelper(private val mContext: Context) {
    private var mListener: UhdHelperListener? = null
    private val mIsSetModeInProgress = AtomicBoolean(false)
    private val mWorkHandler = WorkHandler(Looper.getMainLooper())
    private val overlayStateChangeReceiver = OverlayStateChangeReceiver()
    private var isReceiversRegistered = false
    private val mInternalDisplay = DisplayHolder()
    private var showInterstitial = false
    private var isInterstitialFadeReceived = false
    private var mTargetWindow: Window? = null
    private var currentOverlayStatus = 0
    private val mDisplayManager = mContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var mDisplayListener: DisplayManager.DisplayListener? = null

    companion object {
        const val HEIGHT_UHD = 2160
        const val SET_MODE_TIMEOUT_DELAY_MS = 15000L
        const val SHOW_INTERSTITIAL_TIMEOUT_DELAY_MS = 2000L
        const val MODESWITCH_OVERLAY_ENABLE = "com.amazon.tv.notification.modeswitch_overlay.action.ENABLE"
        const val MODESWITCH_OVERLAY_DISABLE = "com.amazon.tv.notification.modeswitch_overlay.action.DISABLE"
        const val MODESWITCH_OVERLAY_STATE_CHANGED = "com.amazon.tv.notification.modeswitch_overlay.action.STATE_CHANGED"
        const val MODESWITCH_OVERLAY_EXTRA_STATE = "com.amazon.tv.notification.modeswitch_overlay.extra.STATE"
        const val OVERLAY_STATE_DISMISSED = 0
        private val TAG = UhdHelper::class.java.simpleName

        private const val MODE_CHANGED_MSG = 1
        private const val MODE_CHANGE_TIMEOUT_MSG = 2
        private const val SEND_CALLBACK_WITH_SUPPLIED_RESULT = 3
        private const val INTERSTITIAL_FADED_BROADCAST_MSG = 4
        private const val INTERSTITIAL_TIMEOUT_MSG = 5

        fun toResolution(mode: Mode?): String? {
            return mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }
        }

        fun getCurrentMode(context: Context): Mode? {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val mode = display.mode
            return Mode(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
        }
    }

    private inner class WorkHandler(looper: Looper) : Handler(looper) {
        private var mRequestedModeId = 0
        private var mCallbackListener: UhdHelperListener? = null

        fun setExpectedMode(modeId: Int) { mRequestedModeId = modeId }
        fun setCallbackListener(listener: UhdHelperListener?) { mCallbackListener = listener }

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MODE_CHANGED_MSG -> {
                    val mode = getCurrentMode()
                    if (mode == null) {
                        Log.w(TAG, "Mode query returned null after onDisplayChanged")
                        return
                    }
                    if (mode.modeId == mRequestedModeId) {
                        maybeDoACallback(mode)
                        doPostModeSetCleanup()
                    }
                }
                MODE_CHANGE_TIMEOUT_MSG -> {
                    maybeDoACallback(null)
                    doPostModeSetCleanup()
                }
                SEND_CALLBACK_WITH_SUPPLIED_RESULT -> {
                    maybeDoACallback(msg.obj as? Mode)
                    if (msg.arg1 == 1) doPostModeSetCleanup()
                }
                INTERSTITIAL_FADED_BROADCAST_MSG -> {
                    if (!isInterstitialFadeReceived) {
                        isInterstitialFadeReceived = true
                        initModeChange(mRequestedModeId, null)
                    }
                }
                INTERSTITIAL_TIMEOUT_MSG -> {
                    if (!isInterstitialFadeReceived) {
                        isInterstitialFadeReceived = true
                        initModeChange(mRequestedModeId, null)
                    }
                }
            }
        }

        private fun maybeDoACallback(mode: Mode?) {
            mCallbackListener?.onModeChanged(mode)
        }

        private fun doPostModeSetCleanup() {
            if (currentOverlayStatus != OVERLAY_STATE_DISMISSED) {
                currentOverlayStatus = OVERLAY_STATE_DISMISSED
                hideOptimizingOverlay()
            }
            synchronized(mIsSetModeInProgress) {
                removeMessages(MODE_CHANGE_TIMEOUT_MSG)
                if (isReceiversRegistered) {
                    mDisplayListener?.let { mDisplayManager.unregisterDisplayListener(it) }
                    try { mContext.unregisterReceiver(overlayStateChangeReceiver) } catch (_: Exception) {}
                    isReceiversRegistered = false
                }
                removeMessages(MODE_CHANGED_MSG)
                mCallbackListener = null
                mIsSetModeInProgress.set(false)
            }
        }
    }

    private inner class OverlayStateChangeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            currentOverlayStatus = intent.getIntExtra(MODESWITCH_OVERLAY_EXTRA_STATE, -1)
            if (currentOverlayStatus == 3 && !isInterstitialFadeReceived) {
                mWorkHandler.removeMessages(INTERSTITIAL_TIMEOUT_MSG)
                mWorkHandler.sendEmptyMessage(INTERSTITIAL_FADED_BROADCAST_MSG)
            }
        }
    }

    fun getCurrentMode(): Mode? {
        val display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val mode = display.mode
        return mInternalDisplay.getModeInstance(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
    }

    fun getSupportedModes(): Array<Mode> {
        val display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyArray()
        return display.supportedModes.map {
            mInternalDisplay.getModeInstance(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
        }.toTypedArray()
    }

    @SuppressLint("WrongConstant")
    fun setPreferredDisplayModeId(targetWindow: Window, modeId: Int, allowOverlayDisplay: Boolean) {
        if (modeId == 0) return
        var allowOverlay = allowOverlayDisplay
        mWorkHandler.setCallbackListener(mListener)

        if (!DisplaySyncHelper.supportsDisplayModeChange()) {
            mWorkHandler.sendMessage(mWorkHandler.obtainMessage(SEND_CALLBACK_WITH_SUPPLIED_RESULT, 1, 1, null))
            return
        } else if (!DisplaySyncHelper.isAmazonFireTVDevice()) {
            allowOverlay = false
        }

        if (mIsSetModeInProgress.get()) {
            mWorkHandler.sendMessage(mWorkHandler.obtainMessage(SEND_CALLBACK_WITH_SUPPLIED_RESULT, null))
            return
        }

        val currentMode = getCurrentMode()
        if (currentMode == null || currentMode.modeId == modeId) {
            mWorkHandler.sendMessage(mWorkHandler.obtainMessage(SEND_CALLBACK_WITH_SUPPLIED_RESULT, 1, 1, currentMode))
            return
        }

        val supportedModes = getSupportedModes()
        var isRequestedModeSupported = false
        var isRequestedModeUhd = false
        for (mode in supportedModes) {
            if (mode.modeId == modeId) {
                isRequestedModeUhd = mode.physicalHeight >= HEIGHT_UHD
                isRequestedModeSupported = true
                break
            }
        }

        if (!isRequestedModeSupported) {
            mWorkHandler.sendMessage(mWorkHandler.obtainMessage(SEND_CALLBACK_WITH_SUPPLIED_RESULT, 1, 1, null))
            return
        }

        mIsSetModeInProgress.set(true)
        mWorkHandler.setExpectedMode(modeId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mContext.registerReceiver(overlayStateChangeReceiver, IntentFilter(MODESWITCH_OVERLAY_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        } else {
            mContext.registerReceiver(overlayStateChangeReceiver, IntentFilter(MODESWITCH_OVERLAY_STATE_CHANGED))
        }

        mDisplayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                mWorkHandler.sendEmptyMessage(MODE_CHANGED_MSG)
            }
        }
        mDisplayManager.registerDisplayListener(mDisplayListener, mWorkHandler)
        isReceiversRegistered = true
        mTargetWindow = targetWindow
        showInterstitial = allowOverlay && isRequestedModeUhd

        val lp = targetWindow.attributes
        try {
            val field = WindowManager.LayoutParams::class.java.getDeclaredField("preferredDisplayModeId")
            if (showInterstitial) {
                isInterstitialFadeReceived = false
                showOptimizingOverlay()
                mWorkHandler.sendEmptyMessageDelayed(INTERSTITIAL_TIMEOUT_MSG, SHOW_INTERSTITIAL_TIMEOUT_DELAY_MS)
            } else {
                initModeChange(modeId, field)
            }
        } catch (e: Exception) {
            mWorkHandler.sendMessage(mWorkHandler.obtainMessage(SEND_CALLBACK_WITH_SUPPLIED_RESULT, 1, 1, null))
        }
    }

    private fun initModeChange(modeId: Int, field: Field?) {
        val window = mTargetWindow ?: return
        val lp = window.attributes
        try {
            val f = field ?: WindowManager.LayoutParams::class.java.getDeclaredField("preferredDisplayModeId")
            f.isAccessible = true // ВАЖНО: Разрешаем доступ к скрытому полю
            if (f.getInt(lp) != modeId) {
                f.setInt(lp, modeId)
                window.attributes = lp
            }
        } catch (e: Exception) {
            mWorkHandler.sendMessage(mWorkHandler.obtainMessage(SEND_CALLBACK_WITH_SUPPLIED_RESULT, 1, 1, null))
            return
        }
        mWorkHandler.sendEmptyMessageDelayed(MODE_CHANGE_TIMEOUT_MSG, SET_MODE_TIMEOUT_DELAY_MS)
    }

    private fun showOptimizingOverlay() {
        mContext.sendBroadcast(Intent(MODESWITCH_OVERLAY_ENABLE))
    }

    private fun hideOptimizingOverlay() {
        mContext.sendBroadcast(Intent(MODESWITCH_OVERLAY_DISABLE))
    }

    fun registerModeChangeListener(listener: UhdHelperListener) { mListener = listener }
}