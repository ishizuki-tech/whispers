package whispers.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAVファイル（リニアPCM, 16bit, 16kHz, モノラル/ステレオ対応）のデータを
 * FloatArray（-1.0～+1.0の正規化値）としてデコードするユーティリティ。
 *
 * @param file 入力するWAVファイル
 * @return 正規化済みfloat配列（ch1のみ or ch1+ch2を合成/平均化したもの）
 *
 * ※16bitリニアPCM/16kHz前提。他のフォーマットでは要拡張。
 * ※ヘッダーエラーや異常ファイルは例外が発生する可能性あり
 */
fun decodeWaveFile(file: File): FloatArray {
    // 全データをメモリに読み込む（大きいファイルの場合は要注意）
    val baos = ByteArrayOutputStream()
    file.inputStream().use { it.copyTo(baos) }
    val bytes = baos.toByteArray()

    // WAVヘッダはリトルエンディアン
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    // チャンネル数をヘッダーから取得（22byte目: 1=モノラル, 2=ステレオ）
    val channelCount = buffer.getShort(22).toInt()
    require(channelCount in 1..2) { "Unsupported channel count: $channelCount" }

    // PCMデータの開始位置（44byte以降がデータ領域/標準WAV）
    buffer.position(44)

    // 16bit PCMデータをShortArrayに展開
    val shortBuffer = buffer.asShortBuffer()
    val shortArray = ShortArray(shortBuffer.limit())
    shortBuffer.get(shortArray)

    // チャンネル数に応じてfloat配列へ変換
    return FloatArray(shortArray.size / channelCount) { index ->
        when (channelCount) {
            1 -> (shortArray[index] / 32768.0f).coerceIn(-1f..1f) // モノラル: 直接正規化
            2 -> { // ステレオ: L+Rの平均値を正規化
                val l = shortArray[2 * index]
                val r = shortArray[2 * index + 1]
                ((l + r) / 2.0f / 32768.0f).coerceIn(-1f..1f)
            }
            else -> error("Unsupported channel count")
        }
    }
}

/**
 * PCM16bitリニアデータをWAVファイルとしてエンコード・保存するユーティリティ。
 *
 * @param file 保存先ファイル
 * @param data PCMデータ配列（モノラル想定, 16bit/サンプル）
 */
fun encodeWaveFile(file: File, data: ShortArray) {
    file.outputStream().use { out ->
        // WAVヘッダ書き込み
        out.write(headerBytes(data.size * 2))
        // PCMデータ本体書き込み（リトルエンディアン）
        val buffer = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(data)
        out.write(buffer.array())
    }
}

/**
 * 標準WAVファイル用の44バイトヘッダを生成
 *
 * @param pcmDataBytes PCM本体サイズ（バイト数）
 * @return ヘッダ44バイト
 *
 * フォーマットは 16bit/16kHz/モノラルのみ
 * サンプルレートやチャンネル数変更は要編集
 */
private fun headerBytes(pcmDataBytes: Int): ByteArray {
    require(pcmDataBytes >= 0)
    val totalSize = pcmDataBytes + 44
    return ByteBuffer.allocate(44).apply {
        order(ByteOrder.LITTLE_ENDIAN)

        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(totalSize - 8)   // ファイルサイズ-8
        put("WAVE".toByteArray(Charsets.US_ASCII))

        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)             // fmtチャンクのサイズ
        putShort(1)            // フォーマットID: 1=PCM
        putShort(1)            // チャンネル数: 1=モノラル（2なら2にする）
        putInt(16000)          // サンプリングレート
        putInt(16000 * 2)      // バイトレート (サンプリングレート * チャンネル * 2)
        putShort(2)            // ブロックサイズ: チャンネル*2
        putShort(16)           // サンプルあたりビット数

        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcmDataBytes)   // データ部のバイト数
        position(0)
    }.array()
}
