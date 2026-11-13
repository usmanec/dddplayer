/*
 * DDD Video Player: кодировщик AC3 / E-AC3 (FFmpeg libavcodec) для вывода многоканального звука
 * битстримом на HDMI/ARC. Java: top.rootu.dddplayer.player.AudioEncoderJni.
 *
 * На входе - планарный float [канал][отсчет] ровно на один кадр (frameSize отсчетов, 1536 для AC3),
 * на выходе - один кадр AC3/E-AC3. Раскладка - маска каналов FFmpeg (AV_CH_*), порядок каналов
 * во входном массиве совпадает с порядком битов маски.
 */
#include <jni.h>
#include <string.h>
#include <android/log.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/frame.h>

#define LOG_TAG "ddd_audioenc"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    AVCodecContext *ctx;
    AVFrame *frame;
    AVPacket *pkt;
    int channels;
} Encoder;

static void release_encoder(Encoder *enc) {
    if (!enc) return;
    av_packet_free(&enc->pkt);
    av_frame_free(&enc->frame);
    avcodec_free_context(&enc->ctx);
    av_free(enc);
}

JNIEXPORT jlong JNICALL
Java_top_rootu_dddplayer_player_AudioEncoderJni_nCreate(JNIEnv *env, jclass clazz, jboolean eac3,
                                                       jint sample_rate, jlong channel_mask, jint bit_rate) {
    const AVCodec *codec = avcodec_find_encoder(eac3 ? AV_CODEC_ID_EAC3 : AV_CODEC_ID_AC3);
    if (!codec) {
        LOGE("Encoder not found (eac3=%d)", eac3);
        return 0;
    }
    Encoder *enc = av_mallocz(sizeof(Encoder));
    if (!enc) return 0;
    enc->ctx = avcodec_alloc_context3(codec);
    if (!enc->ctx) goto fail;
    enc->ctx->sample_rate = sample_rate;
    enc->ctx->bit_rate = bit_rate;
    enc->ctx->sample_fmt = AV_SAMPLE_FMT_FLTP;
    if (av_channel_layout_from_mask(&enc->ctx->ch_layout, (uint64_t) channel_mask) < 0) goto fail;
    enc->channels = enc->ctx->ch_layout.nb_channels;

    int ret = avcodec_open2(enc->ctx, codec, NULL);
    if (ret < 0) {
        LOGE("avcodec_open2 failed: %d (rate %d, mask 0x%llx, bitrate %d)", ret, sample_rate,
             (unsigned long long) channel_mask, bit_rate);
        goto fail;
    }
    enc->frame = av_frame_alloc();
    enc->pkt = av_packet_alloc();
    if (!enc->frame || !enc->pkt) goto fail;
    enc->frame->nb_samples = enc->ctx->frame_size;
    enc->frame->format = enc->ctx->sample_fmt;
    enc->frame->sample_rate = sample_rate;
    if (av_channel_layout_copy(&enc->frame->ch_layout, &enc->ctx->ch_layout) < 0) goto fail;
    if (av_frame_get_buffer(enc->frame, 0) < 0) goto fail;
    return (jlong) (intptr_t) enc;

fail:
    release_encoder(enc);
    return 0;
}

JNIEXPORT jint JNICALL
Java_top_rootu_dddplayer_player_AudioEncoderJni_nFrameSize(JNIEnv *env, jclass clazz, jlong handle) {
    Encoder *enc = (Encoder *) (intptr_t) handle;
    return enc ? enc->ctx->frame_size : 0;
}

/**
 * Кодирует один кадр. input - планарный float длиной channels * frameSize.
 * @return размер кадра в output, 0 - кодировщик еще не выдал кадр, < 0 - ошибка
 */
JNIEXPORT jint JNICALL
Java_top_rootu_dddplayer_player_AudioEncoderJni_nEncode(JNIEnv *env, jclass clazz, jlong handle,
                                                       jfloatArray input, jobject output) {
    Encoder *enc = (Encoder *) (intptr_t) handle;
    if (!enc) return -1;
    const int frame_size = enc->ctx->frame_size;
    if ((*env)->GetArrayLength(env, input) < enc->channels * frame_size) return -2;

    if (av_frame_make_writable(enc->frame) < 0) return -3;
    for (int ch = 0; ch < enc->channels; ch++) {
        (*env)->GetFloatArrayRegion(env, input, ch * frame_size, frame_size, (jfloat *) enc->frame->data[ch]);
    }
    int ret = avcodec_send_frame(enc->ctx, enc->frame);
    if (ret < 0) {
        LOGE("avcodec_send_frame failed: %d", ret);
        return -4;
    }
    ret = avcodec_receive_packet(enc->ctx, enc->pkt);
    if (ret == AVERROR(EAGAIN)) return 0;
    if (ret < 0) {
        LOGE("avcodec_receive_packet failed: %d", ret);
        return -5;
    }
    uint8_t *out = (*env)->GetDirectBufferAddress(env, output);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, output);
    int size = enc->pkt->size;
    if (!out || capacity < size) {
        av_packet_unref(enc->pkt);
        return -6;
    }
    memcpy(out, enc->pkt->data, size);
    av_packet_unref(enc->pkt);
    return size;
}

JNIEXPORT void JNICALL
Java_top_rootu_dddplayer_player_AudioEncoderJni_nRelease(JNIEnv *env, jclass clazz, jlong handle) {
    release_encoder((Encoder *) (intptr_t) handle);
}
