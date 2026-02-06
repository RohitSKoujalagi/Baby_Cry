package com.example.baby_cry

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.lang.Exception

class LogsActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var backButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        logsRecyclerView = findViewById(R.id.logsRecyclerView)
        logsRecyclerView.layoutManager = LinearLayoutManager(this)

        backButton = findViewById(R.id.backButton)
        backButton.setOnClickListener {
            finish()
        }

        loadLogs()
    }

    private fun loadLogs() {
        val user = auth.currentUser ?: return

        db.collection("cry_logs")
            .whereEqualTo("userId", user.uid)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                val logs = mutableListOf<CryLog>()
                for (document in documents) {
                    try {
                        val reason = document.getString("reason")
                        val remedy = document.getString("remedy")
                        val timestamp = document.getDate("timestamp")
                        val userId = document.getString("userId")

                        // Handle confidence carefully - it might be missing or a different number type
                        val confidence: Double? = when (val conf = document.get("confidence")) {
                            is Double -> conf
                            is Long -> conf.toDouble()
                            else -> null
                        }

                        // Ensure we have a reason before adding to the list
                        if (reason != null) {
                            logs.add(CryLog(reason, remedy, timestamp, userId, confidence))
                        }

                    } catch (e: Exception) {
                        Log.e("LogsActivity", "Error parsing document ${document.id}", e)
                        // This will skip any document that is fundamentally corrupt
                    }
                }
                logsRecyclerView.adapter = LogsAdapter(logs)
            }
            .addOnFailureListener { e ->
                Log.e("LogsActivity", "Error loading logs", e)
            }
    }
}
