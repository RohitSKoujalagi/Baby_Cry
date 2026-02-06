package com.example.baby_cry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogsAdapter(private val logs: List<CryLog>) : RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]
        holder.predictionTextView.text = "Prediction: ${log.reason ?: "N/A"}"
        holder.confidenceTextView.text = "Confidence: ${if (log.confidence != null) String.format("%.2f", log.confidence) else "N/A"}%"
        holder.suggestionTextView.text = "Suggestion: ${log.remedy ?: "N/A"} 😊"
    }

    override fun getItemCount() = logs.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val predictionTextView: TextView = itemView.findViewById(R.id.predictionTextView)
        val confidenceTextView: TextView = itemView.findViewById(R.id.confidenceTextView)
        val suggestionTextView: TextView = itemView.findViewById(R.id.suggestionTextView)
    }
}
