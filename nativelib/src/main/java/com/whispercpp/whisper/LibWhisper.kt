package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

/**
 * Whisper C++ の推論コンテキストをラップするクラス。
 *
 * 【主な役割・設計意図】
 * - モデルコンテキスト（ptr）をJNI経由で保持・操作。
 * - Whisper.cpp の**スレッド安全制約**（「同時に複数スレッドからアクセスしない」）を守るため、
 *   コルーチンスコープを「シングルスレッド専用Executor」で確保し、すべての推論/解放操作をこのスレッドで直列化する。
 * - 音声データのテキスト化やリリースなど、**長時間かかる/重い処理もコルーチンとして呼び出せる**（UIフリーズしない）。
 *
 * @property ptr ネイティブ側 WhisperContext のポインタ（0で未初期化/解放済み）
 */
class WhisperContext private constructor(
    private var ptr: Long
) {
    /**
     * Whisper.cpp の設計思想に合わせ、「同時アクセス禁止」のため
     * 専用スレッド（Executor）にバインドした CoroutineScope を内部で確保。
     *
     * ※これにより、複数スレッドから呼ばれても順次実行になる（本質的にはスレッド1本分の速度）。
     * ※複数モデルを同時利用したい場合はこのクラス自体を複数生成すればOK。
     */
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    /**
     * PCM音声データをWhisperで推論し、テキスト化して返すサスペンド関数。
     *
     * @param data        [-1,1]正規化済みのPCM float配列
     * @param lang        推論言語（"ja", "en", "sw"など）
     * @param translate   翻訳モード（日本語→英語等の自動変換、要多言語モデル）
     * @param printTimestamp タイムスタンプ出力フラグ（未使用、今後拡張用）
     * @return            結果テキスト（すべてのセグメントを結合）
     * @throws IllegalStateException  解放済みや未初期化時
     */
    suspend fun transcribeData(
        data: FloatArray,
        lang: String,
        translate: Boolean,
        printTimestamp: Boolean = true
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L) { "WhisperContext: すでに解放されています（release()済み）" }
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Whisper推論: スレッド数=$numThreads, 言語=$lang, 翻訳=$translate")
        WhisperLib.fullTranscribe(ptr, lang, numThreads, translate, data)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        // セグメントごとにテキストを取得し、連結
        buildString {
            for (i in 0 until textCount) {
                append(WhisperLib.getTextSegment(ptr, i))
            }
        }
    }

    /**
     * JNI経由でメモリコピー速度ベンチマーク（デバッグ・性能評価用）
     * @param nthreads スレッド数
     * @return 結果文字列
     */
    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
        WhisperLib.benchMemcpy(nthreads)
    }

    /**
     * JNI経由で行列積速度ベンチマーク（デバッグ・性能評価用）
     * @param nthreads スレッド数
     * @return 結果文字列
     */
    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
        WhisperLib.benchGgmlMulMat(nthreads)
    }

    /**
     * ネイティブリソースの明示的解放（推奨）。
     * - このクラスを使い終わったら必ず呼び出すこと！
     * - すでに解放済みの場合は何もしない（安全）。
     */
    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0L
            Log.d(LOG_TAG, "WhisperContext: ネイティブリソースを解放しました")
        }
    }

    /**
     * ガーベジコレクション時の緊急自動解放（保険的な最終防御）。
     * - 明示的にrelease()を呼ぶのが基本。GCまかせはメモリリークの原因になるので注意。
     */
    protected fun finalize() {
        runBlocking { release() }
    }

    companion object {
        /**
         * ファイルパスからWhisperモデルを読み込み、コンテキストを生成
         * @throws RuntimeException 失敗時
         */
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            require(ptr != 0L) { "Couldn't create context with path $filePath" }
            return WhisperContext(ptr)
        }

        /**
         * InputStream（例: assetsやネットワーク）からWhisperモデルを読み込み、コンテキストを生成
         * @throws RuntimeException 失敗時
         */
        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            require(ptr != 0L) { "Couldn't create context from input stream" }
            return WhisperContext(ptr)
        }

        /**
         * assets内パスからWhisperモデルを読み込み、コンテキストを生成
         * @throws RuntimeException 失敗時
         */
        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            require(ptr != 0L) { "Couldn't create context from asset $assetPath" }
            return WhisperContext(ptr)
        }

        /** ライブラリやビルド環境情報の取得（デバッグ用） */
        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

/**
 * JNIバインディングをまとめた内部用クラス
 *
 * 【主な役割】
 * - CPUアーキテクチャと機能（vfpv4, fp16等）をランタイムで自動判定し、最適な.soライブラリをロード
 * - 低レイヤ（C++）の全機能をKotlin側に公開
 * - 実運用時はアプリ起動時1度だけロードすれば十分（冪等＝何度呼んでも1回だけ実行）
 */
private class WhisperLib {
    companion object {
        init {
            // プライマリABI情報をログ出力
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS.getOrNull(0) ?: "unknown"}")
            val cpuInfo = cpuInfo()
            // vfpv4やfp16対応CPUを検知して最適化ライブラリをロード
            when {
                isArmEabiV7a() && cpuInfo?.contains("vfpv4") == true -> {
                    Log.d(LOG_TAG, "CPU supports vfpv4, loading libwhisper_vfpv4.so")
                    System.loadLibrary("whisper_vfpv4")
                }
                isArmEabiV8a() && cpuInfo?.contains("fphp") == true -> {
                    Log.d(LOG_TAG, "CPU supports fp16, loading libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                }
                else -> {
                    Log.d(LOG_TAG, "Loading default libwhisper.so")
                    System.loadLibrary("whisper")
                }
            }
        }

        // ========== JNI経由でC++ whisper.cppにアクセスするメソッド群 ==========
        @JvmStatic external fun initContextFromInputStream(inputStream: InputStream): Long
        @JvmStatic external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        @JvmStatic external fun initContext(modelPath: String): Long
        @JvmStatic external fun freeContext(contextPtr: Long)
        @JvmStatic external fun fullTranscribe(contextPtr: Long, lang: String, numThreads: Int, translate: Boolean, audioData: FloatArray)
        @JvmStatic external fun getTextSegmentCount(contextPtr: Long): Int
        @JvmStatic external fun getTextSegment(contextPtr: Long, index: Int): String
        @JvmStatic external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        @JvmStatic external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        @JvmStatic external fun getSystemInfo(): String
        @JvmStatic external fun benchMemcpy(nthread: Int): String
        @JvmStatic external fun benchGgmlMulMat(nthread: Int): String
    }
}

/**
 * 10ms単位のフレーム番号をタイムスタンプ（hh:mm:ss.SSS）に変換するユーティリティ
 * @param t      10ms単位のフレーム
 * @param comma  区切り文字をカンマにする（SRT用等）
 * @return       "00:05.123" などの文字列
 */
private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000
    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

/** ARM v7a ABI判定。バイナリ最適化のために利用 */
private fun isArmEabiV7a(): Boolean = Build.SUPPORTED_ABIS.getOrNull(0) == "armeabi-v7a"

/** ARM v8a (64bit) ABI判定。バイナリ最適化のために利用 */
private fun isArmEabiV8a(): Boolean = Build.SUPPORTED_ABIS.getOrNull(0) == "arm64-v8a"

/**
 * /proc/cpuinfoからCPU情報を抜き出す。
 * CPUの命令セットやベクタ演算対応状況の判別に使用
 */
private fun cpuInfo(): String? = try {
    File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
    null
}
