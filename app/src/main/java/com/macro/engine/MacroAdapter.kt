package com.macro.engine

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for displaying saved macro files.
 */
class MacroAdapter(
    private val onActivateClick: (File, Boolean) -> Unit,
    private val onConfigClick: (File) -> Unit,
    private val onDeleteClick: (File) -> Unit
) : RecyclerView.Adapter<MacroAdapter.MacroViewHolder>() {

    private val macros = mutableListOf<File>()

    fun updateMacros(files: List<File>) {
        macros.clear()
        macros.addAll(files.sortedByDescending { it.lastModified() })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MacroViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_macro, parent, false)
        return MacroViewHolder(view)
    }

    override fun onBindViewHolder(holder: MacroViewHolder, position: Int) {
        holder.bind(macros[position])
    }

    override fun getItemCount(): Int = macros.size

    inner class MacroViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvMacroName)
        private val tvInfo: TextView = view.findViewById(R.id.tvMacroInfo)
        private val switchActivate: SwitchMaterial = view.findViewById(R.id.switchActivate)
        private val btnConfig: ImageButton = view.findViewById(R.id.btnItemConfig)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnItemDelete)

        fun bind(file: File) {
            val config = MacroConfig.load(file)
            val displayName = if (config.name.isNotBlank()) {
                config.name
            } else {
                file.nameWithoutExtension.replace("_", " ")
                    .replaceFirstChar { it.uppercase() }
            }
            tvName.text = displayName

            val sizeKb = file.length() / 1024
            val date = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                .format(Date(file.lastModified()))
            val configInfo = buildString {
                append("${sizeKb}KB · $date")
                if (config.speed != 1.0f) append(" · ${config.speed}×")
                if (config.repeatCount != 1) {
                    append(" · ${if (config.repeatCount == 0) "∞" else "${config.repeatCount}"}×rep")
                }
            }
            tvInfo.text = configInfo

            switchActivate.setOnCheckedChangeListener(null)
            switchActivate.isChecked = config.trigger?.enabled == true
            switchActivate.setOnCheckedChangeListener { _, isChecked ->
                onActivateClick(file, isChecked)
            }

            btnConfig.setOnClickListener { onConfigClick(file) }
            btnDelete.setOnClickListener {
                onDeleteClick(file)
                val pos = macros.indexOf(file)
                if (pos >= 0) {
                    macros.removeAt(pos)
                    notifyItemRemoved(pos)
                }
            }
        }
    }
}
