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
import kotlinx.serialization.json.Json
import whispers.media.decodeWaveFile
import whispers.recorder.Recorder
import java.io.File
import java.util.*
import kotlinx.serialization.encodeToString
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest

private const val LOG_TAG = "MainScreenViewModel"

class MainScreenViewModel(private val application: Application) : ViewModel() {

    // UI States
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

    fun updateTranslate(toEnglish: Boolean) {
        translateToEnglish = toEnglish
    }

    // Internals
    private val modelsPath = File(application.filesDir, "models")
    private val samplesPath = File(application.filesDir, "samples")

    private var whisperContext: com.whispercpp.whisper.WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRecordedFile: File? = null
    private val recorder = Recorder()

    init {
        // ① 初期化処理
        viewModelScope.launch {
            setupDirectories()
            loadRecords()         // ✅ 先に myRecords を復元
            loadModel(selectedModel)
            canTranscribe = true
        }

        // ② myRecords の変更を検知して保存（load直後は無視）
        viewModelScope.launch {
            var first = true
            snapshotFlow { myRecords }
                .collectLatest {
                    if (first) {
                        first = false
                    } else {
                        saveRecords()
                    }
                }
        }
    }

    fun saveRecords() {
        try {
            val file = File(application.filesDir, "records.json")
            val json = Json.encodeToString<List<myRecord>>(myRecords)
            file.outputStream().bufferedWriter().use { writer ->
                writer.write(json)
                writer.flush() // 明示的な flush（実は use{} でも自動的にされる）
            }
            Log.d("MainScreenViewModel", "Flush & 保存完了: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("MainScreenViewModel", "保存失敗", e)
        }
    }

    fun loadRecords() {
        try {
            val file = File(application.filesDir, "records.json")
            Log.d("MainScreenViewModel", "file $file")
            Log.d("MainScreenViewModel", "loadRecords Records ${application.filesDir}")
            if (file.exists()) {
                Log.d("MainScreenViewModel", "file ${file.toString()}")
                val text = file.readText()
                Log.d("MainScreenViewModel", "json = $text")
                myRecords = Json.decodeFromString(text)
            }
            else {
                Log.d("MainScreenViewModel", "FAILED!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            }
        } catch (e: Exception) {
            Log.d("MainScreenViewModel", "Exception FAILED!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            Log.e("MainScreenViewModel", "Failed to load records", e)
        }
    }

    // UI操作
    fun openConfigDialog() { isConfigDialogOpen = true }
    fun closeConfigDialog() { isConfigDialogOpen = false }
    fun updateSelectedLanguage(lang: String) { selectedLanguage = lang }
    fun updateSelectedModel(model: String) {
        selectedModel = model
        viewModelScope.launch { loadModel(model) }
    }

    fun removeRecordAt(index: Int) {
        if (index in myRecords.indices) {
            myRecords = myRecords.toMutableList().apply { removeAt(index) }
        }
    }

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
                stopPlayback()
                val file = createTempAudioFile()
                recorder.startRecording(file) {
                    isRecording = false
                }
                currentRecordedFile = file
                isRecording = true
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Recording error", e)
            isRecording = false
        }
    }

    fun playRecording(path: String, index: Int) = viewModelScope.launch {
        if (!isRecording) {
            stopPlayback()
            addResultLog(path, index)
            transcribeAudio(File(path), index)
        }
    }

    // モデル読み込み
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
                appendLine("✅ Done. ")
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

    suspend fun readAudioSamples(file: File): FloatArray {
        stopPlayback()
        startPlayback(file)
        return withContext(Dispatchers.IO) {
            decodeWaveFile(file)
        }
    }

    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri()).apply { start() }
    }

    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        releaseMediaPlayer()
    }

    private suspend fun releaseWhisperContext() = withContext(Dispatchers.IO) {
        runCatching {
            whisperContext?.release()
            whisperContext = null
        }.onFailure {
            Log.w(LOG_TAG, "Failed to release whisperContext", it)
        }
    }

    private suspend fun releaseMediaPlayer() = withContext(Dispatchers.Main) {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }.onFailure {
            Log.w(LOG_TAG, "Failed to release MediaPlayer", it)
        }
        mediaPlayer = null
    }


    private fun addNewRecordingLog(filename: String, path: String) {
        val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date())
        val log = "🎤 $filename recorded at $timestamp"
        myRecords = myRecords + myRecord(log, path)
    }

    private fun addResultLog(text: String, index: Int) {
        val target = if (index == -1) myRecords.lastIndex else index
        if (target in myRecords.indices) {
            val updated = myRecords.toMutableList()
            updated[target] = updated[target].copy(logs = updated[target].logs + "\n$text")
            myRecords = updated
        }
    }

    private suspend fun createTempAudioFile(): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File.createTempFile("recording_$timestamp", ".wav", samplesPath)
    }

    private suspend fun setupDirectories() = withContext(Dispatchers.IO) {
        modelsPath.mkdirs()
        samplesPath.mkdirs()
    }

    override fun onCleared() {
        runBlocking {
            releaseWhisperContext()
            stopPlayback()
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(app)
            }
        }
    }
}
