package top.rootu.dddplayer.utils.afr

import android.os.Parcel
import android.os.Parcelable

class DisplayHolder {
    fun getModeInstance(modeId: Int, width: Int, height: Int, refreshRate: Float): Mode {
        return Mode(modeId, width, height, refreshRate)
    }

    class Mode(
        val modeId: Int,
        val physicalWidth: Int,
        val physicalHeight: Int,
        val refreshRate: Float
    ) : Parcelable, Comparable<Mode> {

        constructor(parcel: Parcel) : this(
            parcel.readInt(),
            parcel.readInt(),
            parcel.readInt(),
            parcel.readFloat()
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(modeId)
            parcel.writeInt(physicalWidth)
            parcel.writeInt(physicalHeight)
            parcel.writeFloat(refreshRate)
        }

        override fun describeContents(): Int = 0

        override fun compareTo(other: Mode): Int {
            return if (physicalWidth == other.physicalWidth) {
                ((other.refreshRate * 1000).toInt() - (refreshRate * 1000).toInt())
            } else {
                other.physicalWidth - physicalWidth
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Mode) return false
            return modeId == other.modeId &&
                    physicalHeight == other.physicalHeight &&
                    physicalWidth == other.physicalWidth &&
                    refreshRate.compareTo(other.refreshRate) == 0
        }

        override fun hashCode(): Int {
            var result = modeId
            result = 31 * result + physicalHeight
            result = 31 * result + physicalWidth
            result = 31 * result + refreshRate.hashCode()
            return result
        }

        fun matches(width: Int, height: Int, rate: Float): Boolean {
            return physicalWidth == width && physicalHeight == height && refreshRate.compareTo(rate) == 0
        }

        override fun toString(): String {
            return "Mode [id=$modeId, h=$physicalHeight, w=$physicalWidth, fps=$refreshRate]"
        }

        companion object CREATOR : Parcelable.Creator<Mode> {
            override fun createFromParcel(parcel: Parcel): Mode = Mode(parcel)
            override fun newArray(size: Int): Array<Mode?> = arrayOfNulls(size)
        }
    }
}