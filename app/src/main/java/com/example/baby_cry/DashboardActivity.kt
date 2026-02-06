package com.example.baby_cry

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import com.google.firebase.auth.FirebaseAuth

class DashboardActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        auth = FirebaseAuth.getInstance()

        val recordButton = findViewById<LinearLayout>(R.id.recordButton)
        val logsButton = findViewById<LinearLayout>(R.id.logsButton)
        val logoutButton = findViewById<Button>(R.id.logoutButton)

        recordButton.setOnClickListener {
            startActivity(Intent(this, RecorderActivity::class.java))
        }

        logsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        logoutButton.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
