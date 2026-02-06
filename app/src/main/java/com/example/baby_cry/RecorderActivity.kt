package com.example.baby_cry

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class RecorderActivity : AppCompatActivity() {

    private val PERMISSION_CODE = 200
    private val BACKEND_URL = "http://192.168.1.33:8000/predict"

    // --- AudioRecord Configuration ---
    private val RECORDER_SAMPLERATE = 44100
    private val RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO
    private val RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING)

    // --- UI and State ---
    private lateinit var recordButton: Button
    private lateinit var predictionLayout: LinearLayout
    private lateinit var predictionTextView: TextView
    private lateinit var confidenceTextView: TextView
    private lateinit var suggestionTextView: TextView
    private lateinit var backButton: TextView
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var audioFilePath: String? = null

    // --- Firebase ---
    private lateinit var mAuth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder)

        recordButton = findViewById(R.id.recordButton)
        predictionLayout = findViewById(R.id.predictionLayout)
        predictionTextView = findViewById(R.id.predictionTextView)
        confidenceTextView = findViewById(R.id.confidenceTextView)
        suggestionTextView = findViewById(R.id.suggestionTextView)
        backButton = findViewById(R.id.backButton)

        mAuth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        audioFilePath = externalCacheDir?.absolutePath + "/baby_cry.wav"

        recordButton.setOnClickListener {
            if (!isRecording) {
                if (checkPermissions()) {
                    startRecording()
                } else {
                    requestPermissions()
                }
            } else {
                stopRecording()
            }
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING, bufferSize)
        audioRecord?.startRecording()
        isRecording = true
        recordButton.text = "Stop Recording"
        predictionLayout.visibility = View.GONE

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

            recordButton.text = "Start Recording"
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
                runOnUiThread { Toast.makeText(this@RecorderActivity, "Server Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    try {
                        val json = JSONObject(responseData)
                        val reason = json.optString("prediction", "Unknown")
                        val confidence = json.getDouble("confidence")
                        val remedy = getRemedyForReason(reason)

                        runOnUiThread {
                            predictionTextView.text = "Prediction: $reason"
                            confidenceTextView.text = "Confidence: ${String.format("%.2f", confidence)}%"
                            suggestionTextView.text = "Suggestion: $remedy"
                            predictionLayout.visibility = View.VISIBLE
                            saveToFirestore(reason, remedy, confidence)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@RecorderActivity, "Server Error: ${response.code} ${response.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun getRemedyForReason(reason: String): String {
        return when (reason) {
            "hungry" -> "Baby is likely hungry. Try feeding."
            "discomfort" -> "Baby may be uncomfortable. Check diaper and clothing."
            "tired" -> "Baby might be tired. A nap could help."
            "belly_pain" -> "Baby could have belly pain. Try burping or gentle tummy massage."
            else -> "Baby seems comfortable 😊"
        }
    }

    private fun saveToFirestore(reason: String, remedy: String, confidence: Double) {
        val user = mAuth.currentUser ?: return
        val cryData = CryLog(reason, remedy, Date(), user.uid, confidence)
        db.collection("cry_logs").add(cryData)
            .addOnSuccessListener { Toast.makeText(this@RecorderActivity, "Log Saved", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Toast.makeText(this@RecorderActivity, "Failed to save log", Toast.LENGTH_SHORT).show() }
    }

    private fun copyWaveFile(pcmFile: File, wavFile: File) {
        val pcmData = pcmFile.readBytes()
        val pcmDataLength = pcmData.size.toLong()
        val wavHeader = getWavHeader(pcmDataLength)
        val fileOutputStream = FileOutputStream(wavFile)
        fileOutputStream.write(wavHeader)
        fileOutputStream.write(pcmData)
        fileOutputStream.close()
        pcmFile.delete()
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
        header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte(); header[33] = 0
        header[34] = 16.toByte(); header[35] = 0
        header[36] = 'd'.toByte(); header[37] = 'a'.toByte(); header[38] = 't'.toByte(); header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte(); header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        return header
    }

    private fun checkPermissions(): Boolean {
        val resultAudio = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO)
        return resultAudio == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
