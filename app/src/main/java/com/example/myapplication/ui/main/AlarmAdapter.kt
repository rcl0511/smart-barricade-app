package com.example.myapplication.ui.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.model.AlarmEvent
import com.example.myapplication.data.model.AlarmLevel
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmAdapter(
    private val onAcknowledge: (AlarmEvent) -> Unit,
    private val onDetails: (AlarmEvent) -> Unit,
    private val onDismiss: (AlarmEvent) -> Unit
) : ListAdapter<AlarmEvent, AlarmAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AlarmEvent>() {
            override fun areItemsTheSame(oldItem: AlarmEvent, newItem: AlarmEvent): Boolean =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: AlarmEvent, newItem: AlarmEvent): Boolean =
                oldItem == newItem
        }
        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_alarm, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), onAcknowledge, onDetails, onDismiss)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val chipSeverity: Chip = view.findViewById(R.id.chipSeverity)
        private val txtTime: TextView = view.findViewById(R.id.txtTime)
        private val txtSource: TextView = view.findViewById(R.id.txtSource)
        private val txtTitle: TextView = view.findViewById(R.id.txtTitle)
        private val txtMessage: TextView = view.findViewById(R.id.txtMessage)
        private val btnAcknowledge: MaterialButton = view.findViewById(R.id.btnAcknowledge)
        private val btnDetails: MaterialButton = view.findViewById(R.id.btnDetails)
        private val btnDismiss: ImageButton = view.findViewById(R.id.btnDismiss)

        fun bind(
            item: AlarmEvent,
            onAcknowledge: (AlarmEvent) -> Unit,
            onDetails: (AlarmEvent) -> Unit,
            onDismiss: (AlarmEvent) -> Unit
        ) {
            chipSeverity.text = when (item.level) {
                AlarmLevel.INFO  -> "INFO"
                AlarmLevel.WARN  -> "WARN"
                AlarmLevel.ERROR -> "ERROR"
            }
            val tone = when (item.level) {
                AlarmLevel.INFO  -> 0xFF284C6D.toInt()
                AlarmLevel.WARN  -> 0xFFFFA000.toInt()
                AlarmLevel.ERROR -> 0xFFD32F2F.toInt()
            }
            chipSeverity.chipBackgroundColor = ColorStateList.valueOf(tone)
            chipSeverity.setTextColor(0xFFFFFFFF.toInt())

            txtTime.text = timeFmt.format(Date(item.timeMillis))
            txtSource.isVisible = item.deviceId.isNotBlank()
            txtSource.text = if (item.deviceId.isBlank()) "" else "#${item.deviceId}"

            txtTitle.text = item.title
            txtMessage.text = item.detail

            btnAcknowledge.setOnClickListener { onAcknowledge(item) }
            btnDetails.setOnClickListener { onDetails(item) }
            btnDismiss.setOnClickListener { onDismiss(item) }
        }
    }
}
