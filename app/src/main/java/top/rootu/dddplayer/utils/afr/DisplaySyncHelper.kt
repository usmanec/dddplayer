package top.rootu.dddplayer.utils.afr

import android.content.Context
import android.os.Build
import android.view.Window
import top.rootu.dddplayer.utils.afr.DisplayHolder.Mode

class DisplaySyncHelper(context: Context?) : UhdHelperListener {
    private var mContext: Context? = context?.applicationContext
    private var mDisplaySyncInProgress = false
    private var mUhdHelper: UhdHelper? = null
    var originalMode: Mode? = null
        private set
    var newMode: Mode? = null
        private set
    private var mIsResolutionSwitchEnabled = false
    private var mIsDoubleRefreshRateEnabled = true
    private var mIsSkip24RateEnabled = false
    private var mModeLength = -1
    private var mListener: AutoFrameRateListener? = null

    interface AutoFrameRateListener {
        fun onModeStart(newMode: Mode?)
        fun onModeError(newMode: Mode?)
        fun onModeCancel()
    }

    companion object {
        private const val HD = 1200

        fun isAmazonFireTVDevice(): Boolean {
            return Build.MODEL.startsWith("AFT") && "Amazon".equals(Build.MANUFACTURER, ignoreCase = true)
        }

        fun supportsDisplayModeChange(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        }
    }

    fun supportsDisplayModeChangeComplex(): Boolean {
        if (mContext == null) return false
        if (mModeLength == -1) {
            val modes = getUhdHelper().getSupportedModes()
            mModeLength = modes.size
        }
        return mModeLength >= 1 && supportsDisplayModeChange()
    }

    override fun onModeChanged(mode: Mode?) {
        mDisplaySyncInProgress = false
        val target = newMode ?: return
        val current = getUhdHelper().getCurrentMode()
        if (current?.modeId != target.modeId) {
            mListener?.onModeError(target)
        }
    }

    fun syncDisplayMode(window: Window, videoWidth: Int, videoFramerate: Float, force: Boolean = false): Boolean {
        if (supportsDisplayModeChange() && videoWidth >= 10) {
            val helper = getUhdHelper()
            val modes = helper.getSupportedModes()
            var resultModes: List<Mode> = emptyList()

            if (mIsResolutionSwitchEnabled) {
                resultModes = filterModesByWidth(modes, videoWidth.coerceAtLeast(HD))
            }

            val currentMode = helper.getCurrentMode()
            if (resultModes.isEmpty()) {
                resultModes = filterSameResolutionModes(modes, currentMode)
            }

            val skipFps = mIsSkip24RateEnabled && videoFramerate >= 23.96 && videoFramerate <= 24.98 && currentMode != null
            val closerMode = findCloserMode(resultModes, if (skipFps) currentMode.refreshRate else videoFramerate)

            if (closerMode == null) {
                if (modes.size == 1) mListener?.onModeError(null) else mListener?.onModeCancel()
                return false
            }

            if (!force && closerMode == currentMode) {
                mListener?.onModeCancel()
                return false
            }

            newMode = closerMode
            helper.setPreferredDisplayModeId(window, closerMode.modeId, true)
            mDisplaySyncInProgress = true
            mListener?.onModeStart(closerMode)
            return true
        }
        return false
    }

    private fun filterSameResolutionModes(oldModes: Array<Mode>, currentMode: Mode?): List<Mode> {
        if (currentMode == null) return emptyList()
        return oldModes.filter { it.physicalHeight == currentMode.physicalHeight && it.physicalWidth == currentMode.physicalWidth }
    }

    private fun filterModesByWidth(allModes: Array<Mode>, videoWidth: Int): List<Mode> {
        if (videoWidth == -1) return emptyList()
        return allModes.sortedByDescending { it.physicalWidth }
            .filter { it.physicalWidth >= (videoWidth - 100) }
    }

    private fun findCloserMode(modes: List<Mode>, videoFramerate: Float): Mode? {
        val relatedRates = getRateMapping()
        var myRate = (videoFramerate * 100.0f).toInt()
        if (myRate in 2300..2399) myRate = 2397

        val rates = relatedRates[myRate] ?: return null
        val rateAndMode = modes.associateBy { (it.refreshRate * 100.0f).toInt() }

        for (rate in rates) {
            rateAndMode[rate]?.let { return it }
        }
        return null
    }

    private fun getRateMapping(): Map<Int, IntArray> {
        return if (mIsDoubleRefreshRateEnabled) getDoubleRateMapping() else getSingleRateMapping()
    }

    private fun getSingleRateMapping(): Map<Int, IntArray> = mapOf(
        1500 to intArrayOf(3000, 6000),
        2397 to intArrayOf(2397, 2400, 3000, 6000),
        2400 to intArrayOf(2400, 3000, 6000),
        2500 to intArrayOf(2500, 5000),
        2997 to intArrayOf(2997, 3000, 6000),
        3000 to intArrayOf(3000, 6000),
        5000 to intArrayOf(5000, 2500),
        5994 to intArrayOf(5994, 6000, 3000),
        6000 to intArrayOf(6000, 3000)
    )

    private fun getDoubleRateMapping(): Map<Int, IntArray> = mapOf(
        1500 to intArrayOf(6000, 3000),
        2397 to intArrayOf(4794, 4800, 2397, 2400),
        2400 to intArrayOf(4800, 2400),
        2497 to intArrayOf(4994, 5000, 2497, 2500),
        2500 to intArrayOf(5000, 2500),
        2997 to intArrayOf(5994, 6000, 2997, 3000),
        3000 to intArrayOf(6000, 3000),
        5000 to intArrayOf(5000, 2500),
        5994 to intArrayOf(5994, 6000, 2997, 3000),
        6000 to intArrayOf(6000, 3000)
    )

    fun saveOriginalState() {
        originalMode = getUhdHelper().getCurrentMode()
    }

    fun restoreOriginalState(window: Window, force: Boolean = false): Boolean {
        val target = originalMode ?: return false
        val current = getUhdHelper().getCurrentMode()
        if (!force && target == current) return false
        getUhdHelper().setPreferredDisplayModeId(window, target.modeId, true)
        mListener?.onModeStart(target)
        return true
    }

    private fun getUhdHelper(): UhdHelper {
        if (mUhdHelper == null) {
            mUhdHelper = UhdHelper(mContext!!).apply { registerModeChangeListener(this@DisplaySyncHelper) }
        }
        return mUhdHelper!!
    }

    fun setListener(listener: AutoFrameRateListener?) { mListener = listener }
    fun setResolutionSwitchEnabled(enabled: Boolean) { mIsResolutionSwitchEnabled = enabled }
    fun setDoubleRefreshRateEnabled(enabled: Boolean) { mIsDoubleRefreshRateEnabled = enabled }
    fun setSkip24RateEnabled(enabled: Boolean) { mIsSkip24RateEnabled = enabled }
    fun resetStats() { mModeLength = -1 }
    fun setContext(context: Context?) {
        context?.let { mContext = it.applicationContext }
        mUhdHelper = null
    }
}