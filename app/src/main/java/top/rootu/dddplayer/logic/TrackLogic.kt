package top.rootu.dddplayer.logic

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.utils.LocaleUtils
import top.rootu.dddplayer.viewmodel.TrackOption
import top.rootu.dddplayer.viewmodel.VideoQualityOption

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

    fun extractAudioTracks(
        tracks: Tracks,
        metadata: Map<Int, UnifiedMetadataReader.TrackInfo>
    ): Pair<List<TrackOption>, Int> {
        val audioList = mutableListOf<TrackOption>()
        // Добавляем пункт "Выкл."
        audioList.add(TrackOption(null, null, -1, null, -1, isOff = true))
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

                    // 3. Добавляем
                    if (group.isTrackSelected(i)) selectedAudioIdx = audioList.size
                    audioList.add(TrackOption(
                        format = format,
                        nameFromMeta = nameFromMeta,
                        index = ++audioTrackCounter,
                        group = group,
                        trackIndex = i
                    ))
                }
            }
        }
        return Pair(audioList, selectedAudioIdx)
    }

    fun extractSubtitleTracks(
        tracks: Tracks,
        metadata: Map<Int, UnifiedMetadataReader.TrackInfo>
    ): Pair<List<TrackOption>, Int> {
        val subList = mutableListOf<TrackOption>()
        // Добавляем пункт "Off"
        subList.add(TrackOption(null, null, -1, null, -1, true))
        var selectedSubIdx = 0

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

                    if (group.isTrackSelected(i)) selectedSubIdx = subList.size
                    subList.add(TrackOption(
                        format = format,
                        nameFromMeta = nameFromMeta,
                        index = ++subTrackCounter,
                        group = group,
                        trackIndex = i
                    ))
                }
            }
        }
        return Pair(subList, selectedSubIdx)
    }

    /**
     * Подпись аудиодорожки для выбора такой же дорожки в других видео:
     * название, язык, кодек и число каналов. null для пункта "Выкл".
     */
    fun audioTrackSignature(option: TrackOption): String? {
        if (option.isOff) return null
        val format = option.format ?: return null
        val name = option.nameFromMeta?.trim()?.ifEmpty { null } ?: format.label?.trim().orEmpty()
        return listOf(name, format.language ?: "und", format.sampleMimeType.orEmpty(), format.channelCount.toString())
            .joinToString("|")
            .replace('\n', ' ')
    }

    /** Подпись выключенных субтитров. */
    const val SUBTITLE_OFF = "off"

    /**
     * Подпись субтитров, выбранных пользователем: по ней в следующих элементах плейлиста ищутся такие же.
     * Название, язык и вид (форсированные или полные); [SUBTITLE_OFF] - субтитры выключены.
     */
    fun subtitleTrackSignature(option: TrackOption): String? {
        if (option.isOff) return SUBTITLE_OFF
        val format = option.format ?: return null
        val name = option.nameFromMeta?.trim()?.ifEmpty { null } ?: format.label?.trim().orEmpty()
        return listOf(name, format.language ?: "und", subtitleKind(format))
            .joinToString("|")
            .replace('\n', ' ')
    }

    /**
     * Субтитры по подписи [subtitleTrackSignature]: сначала точное совпадение, иначе тот же язык и вид
     * (у серий название дорожки бывает разным). null - подходящих нет.
     */
    fun findSubtitleIndex(options: List<TrackOption>, signature: String): Int? {
        if (signature == SUBTITLE_OFF) return options.indexOfFirst { it.isOff }.takeIf { it != -1 }
        options.indexOfFirst { !it.isOff && subtitleTrackSignature(it) == signature }
            .takeIf { it != -1 }?.let { return it }
        // Название может содержать "|", поэтому язык и вид берем с конца
        val parts = signature.split('|')
        if (parts.size < 3) return null
        val language = parts[parts.size - 2]
        val kind = parts.last()
        return options.indexOfFirst { option ->
            val format = option.format
            !option.isOff && format != null && sameLanguage(format.language, language) && subtitleKind(format) == kind
        }.takeIf { it != -1 }
    }

    private fun subtitleKind(format: Format): String =
        if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "forced" else "full"

    /**
     * Индекс субтитров для автовключения при загрузке видео (настройка subtitle_auto_mode).
     * null - ничего не менять (режим "По умолчанию" или подходящей дорожки нет).
     * @param preferredLangs предпочитаемые языки субтитров по порядку
     * @param audioLang язык текущей аудиодорожки (для форсированных субтитров)
     */
    fun autoSubtitleIndex(
        options: List<TrackOption>,
        mode: Int,
        preferredLangs: List<String>,
        audioLang: String?
    ): Int? {
        val offIdx = options.indexOfFirst { it.isOff }.takeIf { it != -1 }
        if (mode == SettingsRepository.SUB_AUTO_OFF) return offIdx

        val tracks = options.withIndex().filter { !it.value.isOff && it.value.format != null }
        val (forced, full) = tracks.partition { it.value.format!!.selectionFlags and C.SELECTION_FLAG_FORCED != 0 }

        fun byLanguage(list: List<IndexedValue<TrackOption>>, langs: List<String>): Int? {
            for (lang in langs) {
                list.firstOrNull { sameLanguage(it.value.format?.language, lang) }?.let { return it.index }
            }
            return null
        }

        fun byDefaultFlag(list: List<IndexedValue<TrackOption>>): Int? =
            list.firstOrNull { it.value.format!!.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }?.index

        return when (mode) {
            SettingsRepository.SUB_AUTO_FORCED ->
                // Форсированные субтитры переводят надписи, ищем их сначала на языке звука; нет форсированных - выключаем
                byLanguage(forced, listOfNotNull(audioLang) + preferredLangs)
                    ?: byDefaultFlag(forced)
                    ?: forced.firstOrNull()?.index
                    ?: offIdx
            SettingsRepository.SUB_AUTO_FULL ->
                byLanguage(full, preferredLangs)
                    ?: byDefaultFlag(full)
                    ?: full.firstOrNull()?.index
                    ?: byLanguage(forced, preferredLangs)
                    ?: forced.firstOrNull()?.index
            else -> null
        }
    }

    /** Сравнение кодов языков без учета региона и формата (ru, rus, ru-RU) */
    fun sameLanguage(a: String?, b: String?): Boolean {
        val x = primaryLanguage(a) ?: return false
        val y = primaryLanguage(b) ?: return false
        return x == y
    }

    private fun primaryLanguage(code: String?): String? {
        if (code.isNullOrBlank()) return null
        val normalized = Util.normalizeLanguageCode(code.trim())?.substringBefore('-')?.lowercase()
        return normalized?.takeIf { it.isNotEmpty() && it != "und" && it != "ext" }
    }

    fun buildTrackLabel(option: TrackOption, context: Context): String {
        if (option.isOff) return context.getString(R.string.track_off)

        val format = option.format ?: return context.getString(R.string.track_unknown)

        // Приоритет: Имя из метаданных -> Label из ExoPlayer -> Язык
        var title = option.nameFromMeta?.trim()

        if (title.isNullOrEmpty()) {
            title = format.label
        }

        val langCode = format.language ?: "und"
        val techInfo = getTechInfo(format)
        val techInfoStr = if (techInfo.isNotEmpty()) " [$techInfo]" else ""

        // Если названия всё ещё нет, используем язык
        if (title.isNullOrEmpty()) {
            title =
                if (langCode == "und" || langCode == "ext")
                    "Track ${option.index}"
                else
                    LocaleUtils.getFormattedLanguageName(langCode, context)
        }

        return "$title$techInfoStr ($langCode)"
    }

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
                5 -> "5.0"
                6 -> "5.1"
                7 -> "6.1"
                8 -> "7.1"
                else -> if (format.channelCount != Format.NO_VALUE && format.channelCount > 0) "${format.channelCount}ch" else ""
            }

            val bitrate = if (format.bitrate != Format.NO_VALUE) {
                "${format.bitrate / 1000}k"
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