@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package whispers.ui.main

import android.app.Application
import android.icu.text.SimpleDateFormat
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.*
import whispers.media.decodeWaveFile
import whispers.recorder.Recorder

private const val LOG_TAG = "MainScreenViewModel"

/**
 * ViewModel for the main screen: Whisper モデル読み込み、録音/再生、文字起こし、履歴管理を担う。
 */
class MainScreenViewModel(private val application: Application) : ViewModel() {

    // ---------------------------------------
    // UI 状態（Compose から参照される mutableState）
    // ---------------------------------------
    var canTranscribe by mutableStateOf(false)
        private set

    var isRecording by mutableStateOf(false)
        private set

    var isModelLoading by mutableStateOf(false)
        private set

    var isConfigDialogOpen by mutableStateOf(false)
        private set

    var selectedLanguage by mutableStateOf("en")
        private set

    var selectedModel by mutableStateOf("ggml-tiny-q5_1.bin")
        private set

    var myRecords by mutableStateOf(emptyList<myRecord>())
        private set

    var translateToEnglish by mutableStateOf(false)
        private set

    // ---------------------------------------
    // 内部用パス / リソース
    // ---------------------------------------
    private val modelsPath = File(application.filesDir, "models")
    private val samplesPath = File(application.filesDir, "samples")

    private var whisperContext: com.whispercpp.whisper.WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRecordedFile: File? = null
    private val recorder = Recorder()

    init {
        // 初期化：ディレクトリ作成・レコードの復元・モデル読み込み
        viewModelScope.launch {
            setupDirectories()
            loadRecords() // 先に履歴復元
            loadModel(selectedModel)
            canTranscribe = true
        }

        // myRecords の変更を監視して自動保存（最初の発行は無視）
        viewModelScope.launch {
            snapshotFlow { myRecords }
                .drop(1) // 初回読み込みによる発行をスキップ
                .collectLatest {
                    saveRecords()
                }
        }
    }

    // ---------------------------------------
    // 外部から呼ばれる UI 操作系
    // ---------------------------------------

    /** 設定ダイアログを開く */
    fun openConfigDialog() {
        isConfigDialogOpen = true
    }

    /** 設定ダイアログを閉じる */
    fun closeConfigDialog() {
        isConfigDialogOpen = false
    }

    /** 言語選択を更新する */
    fun updateSelectedLanguage(lang: String) {
        selectedLanguage = lang
    }

    /** モデル選択を更新し、非同期で読み込む */
    fun updateSelectedModel(model: String) {
        selectedModel = model
        viewModelScope.launch { loadModel(model) }
    }

    /** 翻訳フラグの更新 */
    fun updateTranslate(toEnglish: Boolean) {
        translateToEnglish = toEnglish
    }

    /** 指定インデックスの記録を削除 */
    fun removeRecordAt(index: Int) {
        if (index in myRecords.indices) {
            myRecords = myRecords.toMutableList().apply { removeAt(index) }
        }
    }

    /**
     * 録音のトグル。録音中なら停止して文字起こし、そうでなければ新規録音開始。
     * @param onUpdateIndex 新しいレコードのインデックスで UI を更新するコールバック
     */
    fun toggleRecord(onUpdateIndex: (Int) -> Unit) = viewModelScope.launch {
        try {
            if (isRecording) {
                recorder.stopRecording()
                isRecording = false
                currentRecordedFile?.let {
                    addNewRecordingLog(it.name, it.absolutePath)
                    onUpdateIndex(myRecords.lastIndex)
                    transcribeAudio(it)
                }
            } else {
                stopPlayback() // 再生中なら止める
                val file = createTempAudioFile()
                recorder.startRecording(file) {
                    // 録音中のエラー時コールバック（簡易）
                    isRecording = false
                    Log.e(LOG_TAG, "Recorder reported error during startRecording")
                }
                currentRecordedFile = file
                isRecording = true
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Recording toggle error", e)
            isRecording = false
        }
    }

    /**
     * 既存の録音を再生＆文字起こし（再生の合間に録音状態でなければ実行）
     * @param path 再生するファイルパス
     * @param index 対象レコードのインデックス（ログ追加用）
     */
    fun playRecording(path: String, index: Int) = viewModelScope.launch {
        if (!isRecording) {
            stopPlayback()
            addResultLog(path, index) // 再生ログ（何を再生したか）
            transcribeAudio(File(path), index)
        }
    }

    // ---------------------------------------
    // モデル読み込み / 文字起こし関連
    // ---------------------------------------

    /**
     * Whisper モデルを非同期で読み込む。
     * @param model アセット内のモデルファイル名（例: "ggml-..."）
     */
    private suspend fun loadModel(model: String) {
        isModelLoading = true
        try {
            releaseWhisperContext()
            releaseMediaPlayer()

            whisperContext = withContext(Dispatchers.IO) {
                Log.d(LOG_TAG, "Loading model: $model")
                com.whispercpp.whisper.WhisperContext.createContextFromAsset(
                    application.assets, "models/$model"
                ).also {
                    Log.d(LOG_TAG, "Model loaded: $model")
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to load model: $model", e)
        } finally {
            isModelLoading = false
        }
    }

    /**
     * 指定ファイルを文字起こしして結果を対応するレコードに追加する。
     * @param file 音声ファイル（WAV 等）
     * @param index 既存レコードに紐づけるならそのインデックス、デフォルトは -1（直近追加）
     */
    private suspend fun transcribeAudio(file: File, index: Int = -1) {
        if (!canTranscribe) return
        canTranscribe = false
        try {
            val data = readAudioSamples(file)
            val start = System.currentTimeMillis()
            val result = whisperContext?.transcribeData(data, selectedLanguage, translateToEnglish)
            val elapsedMs = System.currentTimeMillis() - start
            val seconds = elapsedMs / 1000
            val milliseconds = elapsedMs % 1000

            val resultText = buildString {
                appendLine("✅ Done.")
                appendLine("🕒 Finished in ${seconds}.${"%03d".format(milliseconds)}s")
                appendLine("🎯 Model     : $selectedModel")
                appendLine("🌐 Language  : $selectedLanguage")
                appendLine("📝 Converted Text Result")
                if (translateToEnglish) appendLine("🌐 Translate To Eng")
                appendLine(result)
            }
            addResultLog(resultText, index)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Transcription error", e)
        } finally {
            canTranscribe = true
        }
    }

    /**
     * オーディオファイルの PCM データを取得する（内部で再生もトリガー）。
     * @param file WAV 等の音声ファイル
     * @return FloatArray で正規化されたサンプル
     */
    suspend fun readAudioSamples(file: File): FloatArray {
        stopPlayback()
        startPlayback(file)
        return withContext(Dispatchers.IO) {
            decodeWaveFile(file)
        }
    }

    // ---------------------------------------
    // 再生 / 解放
    // ---------------------------------------

    /**
     * 再生開始。既存の MediaPlayer は先に確実に解放する。
     */
    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        // 古いをクリアしてから作る
        releaseMediaPlayer()
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri()).apply {
            start()
        }
    }

    /** 再生停止／解放 */
    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        releaseMediaPlayer()
    }

    /** WhisperContext を安全に解放 */
    private suspend fun releaseWhisperContext() = withContext(Dispatchers.IO) {
        runCatching {
            whisperContext?.release()
            whisperContext = null
        }.onFailure {
            Log.w(LOG_TAG, "Failed to release whisperContext", it)
        }
    }

    /** MediaPlayer を安全に解放 */
    private suspend fun releaseMediaPlayer() = withContext(Dispatchers.Main) {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }.onFailure {
            Log.w(LOG_TAG, "Failed to release MediaPlayer", it)
        }
        mediaPlayer = null
    }

    // ---------------------------------------
    // 履歴 / ログ操作
    // ---------------------------------------

    /**
     * 新しい録音を履歴に追加（ファイル名 + タイムスタンプ入りのログ）。
     */
    private fun addNewRecordingLog(filename: String, path: String) {
        val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date())
        val log = "🎤 $filename recorded at $timestamp"
        myRecords = myRecords + myRecord(log, path)
    }

    /**
     * 既存レコードに文字起こしなどの結果ログを追加。
     * @param text 追加するテキスト
     * @param index 対象インデックス（-1 なら直近）
     */
    private fun addResultLog(text: String, index: Int) {
        val target = if (index == -1) myRecords.lastIndex else index
        if (target in myRecords.indices) {
            val updated = myRecords.toMutableList()
            updated[target] = updated[target].copy(logs = updated[target].logs + "\n$text")
            myRecords = updated
        }
    }

    // ---------------------------------------
    // ファイル IO / 初期セットアップ
    // ---------------------------------------

    /** 一時録音ファイルを作成 */
    private suspend fun createTempAudioFile(): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File.createTempFile("recording_$timestamp", ".wav", samplesPath)
    }

    /** モデル & サンプル用ディレクトリを事前に作る */
    private suspend fun setupDirectories() = withContext(Dispatchers.IO) {
        modelsPath.mkdirs()
        samplesPath.mkdirs()
    }

    /**
     * 保存されている履歴（records.json）を読み込む。
     * ファイルがなければ何もしない。
     */
    fun loadRecords() {
        try {
            val file = File(application.filesDir, "records.json")
            Log.d(LOG_TAG, "loadRecords: checking $file")
            if (file.exists()) {
                val text = file.readText()
                Log.d(LOG_TAG, "loadRecords json = $text")
                myRecords = Json.decodeFromString(text)
            } else {
                Log.d(LOG_TAG, "loadRecords: records.json not found, skipping.")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to load records", e)
        }
    }

    /** 現在の履歴を JSON 化して保存する（上書き）。 */
    fun saveRecords() {
        try {
            val file = File(application.filesDir, "records.json")
            val json = Json.encodeToString<List<myRecord>>(myRecords)
            file.outputStream().bufferedWriter().use { writer ->
                writer.write(json)
                writer.flush() // 通常は use が flush もやってくれるが明示
            }
            Log.d(LOG_TAG, "saveRecords: flushed to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "保存失敗", e)
        }
    }

    // ---------------------------------------
    // ライフサイクル / クリーンアップ
    // ---------------------------------------

    override fun onCleared() {
        // ViewModel が破棄されるときは同期的にリソースを開放（必要なら別スレッドでやるべきだが短時間なら許容）
        runBlocking {
            releaseWhisperContext()
            stopPlayback()
        }
    }

    companion object {
        /** ViewModelProvider 用ファクトリ */
        fun factory() = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(app)
            }
        }
    }
}
