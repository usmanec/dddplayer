package top.rootu.dddplayer.logic

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import top.rootu.dddplayer.R
import top.rootu.dddplayer.viewmodel.TrackOption
import top.rootu.dddplayer.viewmodel.VideoQualityOption
import java.util.Locale

object TrackLogic {

    @OptIn(UnstableApi::class)
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

    fun extractAudioTracks(
        tracks: Tracks,
        metadata: Map<Int, UnifiedMetadataReader.TrackInfo>
    ): Pair<List<TrackOption>, Int> {
        val audioList = mutableListOf<TrackOption>()
        var selectedAudioIdx = 0

        // Фильтруем метаданные: оставляем только аудио и сортируем по ID
        // Это нужно для fallback-стратегии (сопоставление по порядку)
        val audioMetadata = metadata.values
            .filter { it.type == UnifiedMetadataReader.TrackType.AUDIO }
            .sortedBy { it.trackId }

        Log.d("TrackLogic", "Found ${audioMetadata.size} audio tracks in metadata:")
        audioMetadata.forEach { Log.d("TrackLogic", "  Meta: ID=${it.trackId}, Name=${it.name}") }

        var audioTrackCounter = 0 // Глобальный счетчик аудио треков в ExoPlayer

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)

                    // Пытаемся найти имя
                    // 1. По ID (TrackNumber)
                    val trackId = format.id?.toIntOrNull()
                    var nameFromMeta = trackId?.let { metadata[it]?.name }

                    // 2. Если по ID не вышло, берем по порядку из списка аудио-метаданных
                    // Это работает, если ExoPlayer и парсер видят треки в одном порядке (обычно так и есть)
                    if (nameFromMeta == null && audioTrackCounter < audioMetadata.size) {
                        nameFromMeta = audioMetadata[audioTrackCounter].name
                    }

                    // 3. Строим красивое название
                    val name = buildTrackLabel(format, nameFromMeta, audioTrackCounter + 1)

                    if (group.isTrackSelected(i)) selectedAudioIdx = audioList.size
                    audioList.add(TrackOption(name, group, i))

                    audioTrackCounter++
                }
            }
        }
        return Pair(audioList, selectedAudioIdx)
    }

    fun extractSubtitleTracks(
        tracks: Tracks,
        context: Context,
        metadata: Map<Int, UnifiedMetadataReader.TrackInfo>
    ): Pair<List<TrackOption>, Int> {
        val subList = mutableListOf<TrackOption>()
        subList.add(TrackOption(context.getString(R.string.track_off), null, -1, true))
        var selectedSubIdx = 0

        // Аналогично для субтитров
        val subMetadata = metadata.values
            .filter { it.type == UnifiedMetadataReader.TrackType.SUBTITLE }
            .sortedBy { it.trackId }

        var subTrackCounter = 0

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)

                    val trackId = format.id?.toIntOrNull()
                    var nameFromMeta = trackId?.let { metadata[it]?.name }

                    if (nameFromMeta == null && subTrackCounter < subMetadata.size) {
                        nameFromMeta = subMetadata[subTrackCounter].name
                    }

                    val name = buildTrackLabel(format, nameFromMeta, subTrackCounter + 1)

                    if (group.isTrackSelected(i)) selectedSubIdx = subList.size
                    subList.add(TrackOption(name, group, i))

                    subTrackCounter++
                }
            }
        }
        return Pair(subList, selectedSubIdx)
    }

    private fun buildTrackLabel(
        format: Format,
        metaName: String?,
        index: Int
    ): String {
        // Приоритет: Имя из метаданных -> Label из ExoPlayer -> Язык
        var title = metaName?.trim()

        if (title.isNullOrEmpty()) {
            title = format.label
        }

        val langCode = format.language ?: "und"
        val techInfo = getTechInfo(format)
        val techInfoStr = if (techInfo.isNotEmpty()) " [$techInfo]" else ""

        // Если названия всё ещё нет, используем язык
        if (title.isNullOrEmpty()) {
            val displayLang = getDisplayLanguage(langCode, index)
            return "$displayLang$techInfoStr ($langCode)"
        }

        return "$title$techInfoStr ($langCode)"
    }

    private fun getDisplayLanguage(langCode: String, index: Int): String {
        if (langCode == "und") return "Track $index"

        val locale = Locale(langCode)
        val display = locale.displayLanguage
        return if (display.isNotEmpty()) {
            display.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        } else {
            langCode.uppercase()
        }
    }

    @OptIn(UnstableApi::class)
    private fun getTechInfo(format: Format): String {
        val mime = format.sampleMimeType ?: return ""

        // --- АУДИО ---
        if (MimeTypes.isAudio(mime)) {
            val codec = when (mime) {
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
                MimeTypes.AUDIO_RAW -> "PCM"
                else -> ""
            }

            val channels = when (format.channelCount) {
                1 -> "1.0"
                2 -> "2.0"
                3 -> "2.1"
                4 -> "4.0"
                5 -> "4.1"
                6 -> "5.1"
                7 -> "6.1"
                8 -> "7.1"
                else -> if (format.channelCount != Format.NO_VALUE && format.channelCount > 0) "${format.channelCount}ch" else ""
            }

            val bitrate = if (format.bitrate != Format.NO_VALUE) {
                "${format.bitrate / 1000}kbps"
            } else {
                ""
            }

            return listOf(codec, channels, bitrate)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }

        // --- СУБТИТРЫ ---
        if (MimeTypes.isText(mime) || mime.startsWith("application/")) {
            return when (mime) {
                MimeTypes.APPLICATION_SUBRIP -> "SRT"
                MimeTypes.TEXT_VTT -> "VTT"
                MimeTypes.TEXT_SSA -> "SSA"
                MimeTypes.APPLICATION_TTML -> "TTML"
                MimeTypes.APPLICATION_MP4VTT -> "VTT"
                MimeTypes.APPLICATION_PGS -> "PGS"
                MimeTypes.APPLICATION_VOBSUB -> "VobSub"
                MimeTypes.APPLICATION_DVBSUBS -> "DVB"
                MimeTypes.APPLICATION_CEA608 -> "CEA-608"
                MimeTypes.APPLICATION_CEA708 -> "CEA-708"
                MimeTypes.APPLICATION_MEDIA3_CUES -> ""
                else -> if (mime.contains("/")) mime.substringAfterLast("/") else ""
            }
        }

        return ""
    }
}