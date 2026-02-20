package com.macro.engine

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for displaying saved macro files.
 */
class MacroAdapter(
    private val onPlayClick: (File) -> Unit,
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
        private val btnPlay: ImageButton = view.findViewById(R.id.btnItemPlay)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnItemDelete)

        fun bind(file: File) {
            // Display a friendly name (strip extension)
            tvName.text = file.nameWithoutExtension.replace("_", " ")
                .replaceFirstChar { it.uppercase() }

            // Show file size and date
            val sizeKb = file.length() / 1024
            val date = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                .format(Date(file.lastModified()))
            tvInfo.text = "${sizeKb}KB · $date"

            btnPlay.setOnClickListener { onPlayClick(file) }
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
