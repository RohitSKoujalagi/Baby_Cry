package com.example.baby_cry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    private val PERMISSION_CODE = 200
    private val BACKEND_URL = "http://192.168.1.33:8000/predict"

    // --- AudioRecord Configuration ---
    private val RECORDER_SAMPLERATE = 44100
    private val RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO
    private val RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING)

    // --- UI and State ---
    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvHistoryLog: TextView
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var audioFilePath: String? = null

    // --- Firebase ---
    private lateinit var mAuth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        tvStatus = findViewById(R.id.tvStatus)
        tvHistoryLog = findViewById(R.id.tvHistoryLog)

        mAuth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        if (mAuth.currentUser == null) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        audioFilePath = externalCacheDir?.absolutePath + "/baby_cry.wav"

        setupRecordButton()
        loadHistory()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecordButton() {
        btnRecord.setOnTouchListener { _, motionEvent ->
            if (!checkPermissions()) {
                requestPermissions()
                return@setOnTouchListener true // Consume event
            }
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> startRecording()
                MotionEvent.ACTION_UP -> stopRecording()
            }
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING, bufferSize)
        audioRecord?.startRecording()
        isRecording = true
        tvStatus.text = "Recording..."

        recordingThread = Thread(Runnable { writeAudioDataToFile() })
        recordingThread?.start()
    }

    private fun writeAudioDataToFile() {
        val pcmFile = File(externalCacheDir?.absolutePath + "/raw_audio.pcm")
        val fileOutputStream = FileOutputStream(pcmFile)
        val data = ByteArray(bufferSize)

        while (isRecording) {
            val read = audioRecord?.read(data, 0, bufferSize) ?: 0
            if (read != AudioRecord.ERROR_INVALID_OPERATION) {
                fileOutputStream.write(data)
            }
        }
        fileOutputStream.close()
    }

    private fun stopRecording() {
        if (audioRecord != null) {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread = null

            tvStatus.text = "Analyzing audio..."
            val pcmFile = File(externalCacheDir?.absolutePath + "/raw_audio.pcm")
            val wavFile = File(audioFilePath!!)
            copyWaveFile(pcmFile, wavFile)
            uploadAudioToBackend(wavFile)
        }
    }

    private fun uploadAudioToBackend(file: File) {
        val client = OkHttpClient()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder().url(BACKEND_URL).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { tvStatus.text = "Server Error: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    try {
                        val json = JSONObject(responseData)
                        val reason = json.optString("prediction", "Unknown")
                        val confidence = json.optString("confidence", "")
                        runOnUiThread {
                            tvStatus.text = "Cry Reason: $reason ($confidence%)"
                            saveToFirestore(reason, "Check baby") // Assuming a default remedy
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "Server Error: ${response.code} ${response.message}"
                    }
                }
            }
        })
    }
    
    private fun copyWaveFile(pcmFile: File, wavFile: File) {
        val pcmData = pcmFile.readBytes()
        val pcmDataLength = pcmData.size.toLong()
        val wavHeader = getWavHeader(pcmDataLength)
        val fileOutputStream = FileOutputStream(wavFile)
        fileOutputStream.write(wavHeader)
        fileOutputStream.write(pcmData)
        fileOutputStream.close()
        pcmFile.delete() // Clean up raw file
    }

    private fun getWavHeader(totalAudioLen: Long): ByteArray {
        val totalDataLen = totalAudioLen + 36
        val sampleRate = RECORDER_SAMPLERATE.toLong()
        val channels = 1
        val byteRate = (RECORDER_SAMPLERATE * channels * 16 / 8).toLong()
        val header = ByteArray(44)

        header[0] = 'R'.toByte(); header[1] = 'I'.toByte(); header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte(); header[9] = 'A'.toByte(); header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
        header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte() // fmt chunk
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // 16 for PCM
        header[20] = 1; header[21] = 0 // PCM=1
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte(); header[33] = 0 // block align
        header[34] = 16.toByte(); header[35] = 0 // bits per sample
        header[36] = 'd'.toByte(); header[37] = 'a'.toByte(); header[38] = 't'.toByte(); header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte(); header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        return header
    }

    // --- Unchanged Methods Below ---

    private fun saveToFirestore(reason: String, remedy: String) {
        val user = mAuth.currentUser ?: return
        val cryData = hashMapOf("timestamp" to Date(), "reason" to reason, "remedy" to remedy, "userId" to user.uid)
        db.collection("cry_logs").add(cryData)
            .addOnSuccessListener { Toast.makeText(this@MainActivity, "Log Saved", Toast.LENGTH_SHORT).show(); loadHistory() }
            .addOnFailureListener { e -> Log.w("Firestore", "Error adding document", e) }
    }

    private fun loadHistory() {
        val user = mAuth.currentUser ?: return
        db.collection("cry_logs").whereEqualTo("userId", user.uid).orderBy("timestamp", Query.Direction.DESCENDING).limit(10).get()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val sb = StringBuilder()
                    for (document in task.result) {
                        val r = document.getString("reason")
                        val s = document.getString("remedy")
                        val d = document.getDate("timestamp")
                        sb.append("• ").append(r).append(" (").append(d).append(")\n").append("  Tip: ").append(s).append("\n\n")
                    }
                    tvHistoryLog.text = sb.toString()
                }
            }
    }

    private fun checkPermissions(): Boolean {
        val resultAudio = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO)
        return resultAudio == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_CODE)
    }
}
