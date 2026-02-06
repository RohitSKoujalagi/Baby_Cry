package com.example.baby_cry

import java.util.Date

data class CryLog(
    val reason: String? = null,
    val remedy: String? = null,
    val timestamp: Date? = null,
    val userId: String? = null,
    val confidence: Double? = null
)
