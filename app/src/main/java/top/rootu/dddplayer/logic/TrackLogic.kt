package top.rootu.dddplayer.logic

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import top.rootu.dddplayer.R
import top.rootu.dddplayer.viewmodel.TrackOption
import top.rootu.dddplayer.viewmodel.VideoQualityOption
import java.util.Locale

object TrackLogic {

    fun extractVideoTracks(tracks: Tracks): List<VideoQualityOption> {
        val options = mutableListOf<VideoQualityOption>()
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.width > 0 && format.height > 0) {
                        options.add(
                            VideoQualityOption(
                                "${format.height}p",
                                format.width,
                                format.height,
                                format.bitrate,
                                group,
                                i
                            )
                        )
                    }
                }
            }
        }
        val sortedOptions = options.sortedByDescending { it.height }.toMutableList()
        sortedOptions.add(0, VideoQualityOption("Auto", 0, 0, 0, null, -1, true))
        return sortedOptions
    }

    fun extractAudioTracks(tracks: Tracks): Pair<List<TrackOption>, Int> {
        val audioList = mutableListOf<TrackOption>()
        var selectedAudioIdx = 0
        var undCounter = 1
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val name = buildAudioLabel(format, undCounter)
                    if (name.startsWith("und")) undCounter++
                    if (group.isTrackSelected(i)) selectedAudioIdx = audioList.size
                    audioList.add(TrackOption(name, group, i))
                }
            }
        }
        return Pair(audioList, selectedAudioIdx)
    }

    fun extractSubtitleTracks(tracks: Tracks, context: Context): Pair<List<TrackOption>, Int> {
        val subList = mutableListOf<TrackOption>()
        subList.add(TrackOption(context.getString(R.string.track_off), null, -1, true))
        var selectedSubIdx = 0
        var undCounter = 1
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val name = buildSubtitleLabel(format, undCounter)
                    if (name.startsWith("und")) undCounter++
                    if (group.isTrackSelected(i)) selectedSubIdx = subList.size
                    subList.add(TrackOption(name, group, i))
                }
            }
        }
        return Pair(subList, selectedSubIdx)
    }

    private fun buildAudioLabel(format: Format, undIndex: Int): String {
        val und = "und$undIndex"
        val lang = format.language ?: und
        val locale = Locale(lang)
        var displayLang = locale.displayLanguage
        displayLang = if (lang == und || displayLang.isEmpty()) "Undefined"
        else
            displayLang.replaceFirstChar {
                if (it.isLowerCase())
                    it.titlecase(Locale.getDefault())
                else
                    it.toString()
            }
        val techInfo = getTechInfo(format)
        return if (!format.label.isNullOrEmpty())
            "${format.label!!} [$techInfo] ($lang)"
        else
            "$displayLang [$techInfo] ($lang)"
    }

    private fun buildSubtitleLabel(format: Format, undIndex: Int): String {
        val und = "und$undIndex"
        val lang = format.language ?: und
        val locale = Locale(lang)
        var displayLang = locale.displayLanguage
        displayLang = if (lang == und || displayLang.isEmpty()) "Undefined"
        else
            displayLang.replaceFirstChar {
                if (it.isLowerCase())
                    it.titlecase(Locale.getDefault())
                else
                    it.toString()
            }
        return if (!format.label.isNullOrEmpty())
            "${format.label!!} ($lang)"
        else
            "$displayLang ($lang)"
    }

    private fun getTechInfo(format: Format): String {
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_E_AC3 -> "E-AC3"
            MimeTypes.AUDIO_E_AC3_JOC -> "DDP"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS_EXPRESS -> "DTS-X"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            else -> ""
        }
        val channels = when (format.channelCount) {
            1 -> "1.0"
            2 -> "2.0"
            3 -> "2.1"
            6 -> "5.1"
            8 -> "7.1"
            else -> if (format.channelCount != Format.NO_VALUE) "${format.channelCount}ch" else ""
        }
        return if (codec.isNotEmpty() && channels.isNotEmpty()) "$codec $channels" else codec + channels
    }
}