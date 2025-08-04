#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

// ===================================
// 共通マクロとロギング定義
// ===================================

// JNI unused引数対策マクロ（警告回避用）
#define UNUSED(x) (void)(x)
#define TAG "JNI"

// Androidのlogcatに出力するマクロ（ビルド時も検索しやすい）
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// 汎用min/max関数
static inline int min(int a, int b) { return (a < b) ? a : b; }
static inline int max(int a, int b) { return (a > b) ? a : b; }

// ===================================
// Java InputStreamラップ機能
// ===================================

/**
 * Java InputStreamをC側からラップして操作するための構造体。
 * - env: JNI環境ポインタ
 * - thiz: 呼び出し元オブジェクト（未使用、将来拡張用）
 * - input_stream: JavaのInputStreamインスタンス
 * - mid_available: InputStream#available()のメソッドID（残りバイト数）
 * - mid_read: InputStream#read(byte[], int, int)のメソッドID（バッファへの読み込み）
 * - offset: 現在の読み取りバイト位置（必要に応じて利用）
 */
struct input_stream_context {
    size_t offset;
    JNIEnv *env;
    jobject thiz;
    jobject input_stream;
    jmethodID mid_available;
    jmethodID mid_read;
};

/**
 * JavaのInputStreamから最大read_sizeバイトをoutputバッファに読み出す。
 * ・JNI経由でInputStream#available()→InputStream#read()を呼び出す
 * ・読み出し不足時はlogcat出力
 * @param ctx   input_stream_context*（状態・メソッドIDなど格納）
 * @param output 出力バッファ
 * @param read_size 読み出し要求サイズ
 * @return 実際に読み出したバイト数
 */
size_t inputStreamRead(void *ctx, void *output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    // InputStream#available()で現在読めるバイト数を取得
    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    // 読み取りサイズ（availableを超えない範囲）
    jint size_to_copy = (jint)min(read_size, (size_t)avail_size);

    // Java byte[]バッファを生成
    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);
    // InputStream#read(byte[], 0, size_to_copy)
    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, ActuallyRead=%d", read_size, size_to_copy, n_read);
    }

    // Java byte[]からCバッファへコピー
    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);
    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy; // オフセット更新

    return size_to_copy;
}

/**
 * InputStreamのEOF判定（InputStream#available()==0を以てEOFとみなす）
 * @return true: 読み出し不能
 */
bool inputStreamEof(void *ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}

/**
 * InputStreamのclose（ここでは何もせずno-op。必要なら拡張）
 */
void inputStreamClose(void *ctx) {
    // Java InputStream#close()を呼ぶ場合はここで実装
}

// ===================================
// JNI: InputStreamからWhisperコンテキストを初期化
// ===================================

/**
 * JavaのInputStreamからWhisperモデルを初期化
 * - Java側のInputStream（通常はassets/rawから取得したモデルなど）をC++のwhisper_init()に渡す
 * - input_stream_context/whisper_model_loaderでコールバック関数群をセット
 * @return whisper_context*へのポインタ（jlongとして返す）
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    // context初期化
    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    // メソッドID取得
    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    // loaderコールバックセット
    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    // whisper初期化
    context = whisper_init(&loader);
    return (jlong) context;
}

// ===================================
// AssetManager経由でWhisper初期化
// ===================================

/**
 * Assetからバイト読み出し用関数
 */
static size_t asset_read(void *ctx, void *output, size_t read_size) {
    // ctxはAAsset*
    return AAsset_read((AAsset *) ctx, output, read_size);
}
/**
 * Asset EOF判定
 */
static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}
/**
 * Assetクローズ
 */
static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

/**
 * AssetManager経由でWhisperモデルを初期化
 * @param assetManager Java側AssetManager
 * @param asset_path assetsディレクトリ以下のファイルパス（例: "models/ggml-base.bin"）
 */
static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };
    // whisper_init_with_params: モデルローダー経由でモデル初期化
    return whisper_init_with_params(&loader, whisper_context_default_params());
}

/**
 * JNI: AssetManager経由でWhisper初期化
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

// ===================================
// ファイルパス指定でWhisper初期化
// ===================================

/**
 * JNI: モデルファイルのフルパスからWhisperコンテキストを初期化
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    context = whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

// ===================================
// Whisperコンテキスト解放
// ===================================

/**
 * JNI: Whisperコンテキストを安全に開放
 */
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

// ===================================
// メイン認識・推論本体（音声→テキスト変換）
// ===================================

/**
 * JNI: Whisperによる音声データのストリーム全文書き起こし
 * @param context_ptr Whisperコンテキスト
 * @param lang_str 言語コード（"en"や"ja"など）
 * @param num_threads 利用スレッド数
 * @param translate 翻訳（enへの翻訳タスクを実行）
 * @param audio_data 入力音声（FloatArray, -1.0～1.0正規化PCM）
 */
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_fullTranscribe(
        JNIEnv *env, jclass clazz, jlong context_ptr, jstring lang_str, jint num_threads, jboolean translate, jfloatArray audio_data) {

    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    const char *lang_cstr = (*env)->GetStringUTFChars(env, lang_str, NULL);

    LOGI("Language: %s", lang_cstr);

    // Whisper推論用パラメータを初期化
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.translate = (translate == JNI_TRUE);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.language = lang_cstr;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    whisper_reset_timings(context);

    LOGI("About to run whisper_full");
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }
    (*env)->ReleaseStringUTFChars(env, lang_str, lang_cstr);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

// ===================================
// セグメント情報取得系
// ===================================

/**
 * JNI: 認識結果のセグメント数を取得
 * @return セグメント数（=分割された音声文節数）
 */
JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

/**
 * JNI: 各セグメントごとのテキストを取得
 * @param context_ptr Whisperコンテキスト
 * @param index セグメント番号
 * @return セグメント文字列
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

/**
 * JNI: セグメント開始タイムスタンプ（10ms単位、0開始）
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

/**
 * JNI: セグメント終了タイムスタンプ（10ms単位、0開始）
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

// ===================================
// システム情報・ベンチマーク用関数
// ===================================

/**
 * JNI: ライブラリ内部のビルド・システム情報取得（デバッグ用）
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

/**
 * JNI: ggml memcpyベンチマーク（n_threads指定）
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchMemcpy(JNIEnv *env, jobject thiz,
                                                   jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}
/**
 * JNI: ggml行列積ベンチマーク（n_threads指定）
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                       jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}
