package top.rootu.dddplayer.utils.afr

import android.app.Activity
import android.content.Context
import top.rootu.dddplayer.utils.afr.DisplaySyncHelper.AutoFrameRateListener

interface AfrFormatItem {
    fun getWidth(): Int
    fun getHeight(): Int
    fun getFrameRate(): Float
}

class AutoFrameRateHelper private constructor(context: Context?) {
    private val mSyncHelper = DisplaySyncHelper(context)
    private var mPrevCall: Long = 0
    private val mFrameRateMapping = mutableMapOf<Float, Float>()
    private var mIsFpsCorrectionEnabled = false
    private var mListener: AutoFrameRateListener? = null

    init {
        mFrameRateMapping[24f] = 23.97f
        mFrameRateMapping[30f] = 29.97f
        mFrameRateMapping[60f] = 59.94f
    }

    companion object {
        private val TAG = AutoFrameRateHelper::class.java.simpleName
        private const val THROTTLE_INTERVAL_MS = 5000L
        private var sInstance: AutoFrameRateHelper? = null

        fun instance(context: Context?): AutoFrameRateHelper {
            if (sInstance == null) sInstance = AutoFrameRateHelper(context)
            sInstance?.setContext(context)
            return sInstance!!
        }
    }

    fun isSupported(): Boolean {
        mSyncHelper.resetStats()
        return mSyncHelper.supportsDisplayModeChangeComplex()
    }

    fun apply(activity: Activity?, format: AfrFormatItem?, force: Boolean = false) {
        setContext(activity)
        if (activity == null || format == null) {
            mListener?.onModeCancel()
            return
        }
        if (!isSupported()) {
            mListener?.onModeCancel()
            return
        }
        if (System.currentTimeMillis() - mPrevCall < THROTTLE_INTERVAL_MS) {
            mListener?.onModeCancel()
            return
        }
        mPrevCall = System.currentTimeMillis()

        val width = format.getWidth()
        val frameRate = correctFrameRate(format.getFrameRate())
        mSyncHelper.syncDisplayMode(activity.window, width, frameRate, force)
    }

    fun saveOriginalState(activity: Activity?) {
        setContext(activity)
        if (activity != null && isSupported()) {
            mSyncHelper.saveOriginalState()
        }
    }

    fun restoreOriginalState(activity: Activity?, force: Boolean = false) {
        if (activity != null && isSupported()) {
            mSyncHelper.restoreOriginalState(activity.window, force)
        }
    }

    private fun correctFrameRate(frameRate: Float): Float {
        return if (mIsFpsCorrectionEnabled) mFrameRateMapping[frameRate] ?: frameRate else frameRate
    }

    fun setListener(listener: AutoFrameRateListener?) {
        mListener = listener
        mSyncHelper.setListener(listener)
    }

    fun setFpsCorrectionEnabled(enabled: Boolean) { mIsFpsCorrectionEnabled = enabled }
    fun setDoubleRefreshRateEnabled(enabled: Boolean) { mSyncHelper.setDoubleRefreshRateEnabled(enabled) }
    fun setSkip24RateEnabled(enabled: Boolean) { mSyncHelper.setSkip24RateEnabled(enabled) }
    fun setResolutionSwitchEnabled(enabled: Boolean) { mSyncHelper.setResolutionSwitchEnabled(enabled) }
    private fun setContext(context: Context?) { mSyncHelper.setContext(context) }
}