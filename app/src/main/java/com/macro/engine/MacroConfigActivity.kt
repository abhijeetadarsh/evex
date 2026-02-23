package com.macro.engine

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.macro.engine.databinding.ActivityMacroConfigBinding
import java.io.File

/**
 * Activity for editing per-macro configuration: name, delay, repeat, speed, trigger.
 */
class MacroConfigActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MACRO_PATH = "macro_path"
    }

    private lateinit var binding: ActivityMacroConfigBinding
    private lateinit var macroFile: File
    private var config = MacroConfig()
    private var repeatCount = 1
    private var isInfinite = false

    // Speed chip ID → float value mapping
    private val speedChips by lazy {
        mapOf(
            R.id.chipSpeed025 to 0.25f,
            R.id.chipSpeed05 to 0.5f,
            R.id.chipSpeed1 to 1.0f,
            R.id.chipSpeed15 to 1.5f,
            R.id.chipSpeed2 to 2.0f,
            R.id.chipSpeed4 to 4.0f
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMacroConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_MACRO_PATH)
        if (path == null) {
            Toast.makeText(this, "No macro path provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        macroFile = File(path)
        if (!macroFile.exists()) {
            Toast.makeText(this, "Macro file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        config = MacroConfig.load(macroFile)
        populateUI()
        setupListeners()
    }

    private fun populateUI() {
        binding.apply {
            // Name
            etName.setText(config.name)

            // Delay
            sliderDelay.value = config.startDelayMs.toFloat()
            tvDelayValue.text = "%.1fs".format(config.startDelayMs / 1000f)

            // Repeat count
            repeatCount = config.repeatCount
            isInfinite = repeatCount == 0
            switchInfinite.isChecked = isInfinite
            updateRepeatDisplay()

            // Speed — select matching chip
            val chipId = speedChips.entries.find { it.value == config.speed }?.key ?: R.id.chipSpeed1
            chipGroupSpeed.check(chipId)

            // Trigger
            val trigger = config.trigger ?: TriggerConfig()
            switchTrigger.isChecked = trigger.enabled
            layoutTriggerOptions.visibility = if (trigger.enabled) View.VISIBLE else View.GONE

            when (trigger.shape) {
                "circle" -> rgShape.check(R.id.rbCircle)
                "square" -> rgShape.check(R.id.rbSquare)
                "rounded_rect" -> rgShape.check(R.id.rbRounded)
            }

            sliderOpacity.value = trigger.opacity
            tvOpacityValue.text = "${(trigger.opacity * 100).toInt()}%"

            switchMode.isChecked = trigger.mode == "hold"
            switchLocked.isChecked = trigger.locked
        }
    }

    private fun setupListeners() {
        binding.apply {
            btnBack.setOnClickListener { finish() }

            // Delay slider
            sliderDelay.addOnChangeListener { _, value, _ ->
                tvDelayValue.text = "%.1fs".format(value / 1000f)
            }

            // Repeat −/+
            btnRepeatMinus.setOnClickListener {
                if (!isInfinite && repeatCount > 1) {
                    repeatCount--
                    updateRepeatDisplay()
                }
            }
            btnRepeatPlus.setOnClickListener {
                if (!isInfinite) {
                    repeatCount++
                    updateRepeatDisplay()
                }
            }
            switchInfinite.setOnCheckedChangeListener { _, checked ->
                isInfinite = checked
                updateRepeatDisplay()
            }

            // Trigger toggle
            switchTrigger.setOnCheckedChangeListener { _, checked ->
                layoutTriggerOptions.visibility = if (checked) View.VISIBLE else View.GONE
            }

            // Opacity slider
            sliderOpacity.addOnChangeListener { _, value, _ ->
                tvOpacityValue.text = "${(value * 100).toInt()}%"
            }

            // Save
            btnSaveConfig.setOnClickListener { saveConfig() }
        }
    }

    private fun updateRepeatDisplay() {
        binding.apply {
            if (isInfinite) {
                tvRepeatCount.text = "∞"
                btnRepeatMinus.isEnabled = false
                btnRepeatPlus.isEnabled = false
                btnRepeatMinus.alpha = 0.4f
                btnRepeatPlus.alpha = 0.4f
            } else {
                tvRepeatCount.text = repeatCount.toString()
                btnRepeatMinus.isEnabled = repeatCount > 1
                btnRepeatPlus.isEnabled = true
                btnRepeatMinus.alpha = if (repeatCount > 1) 1.0f else 0.4f
                btnRepeatPlus.alpha = 1.0f
            }
        }
    }

    private fun saveConfig() {
        val selectedSpeed = speedChips[binding.chipGroupSpeed.checkedChipId] ?: 1.0f
        val shape = when (binding.rgShape.checkedRadioButtonId) {
            R.id.rbCircle -> "circle"
            R.id.rbSquare -> "square"
            R.id.rbRounded -> "rounded_rect"
            else -> "circle"
        }

        val trigger = if (binding.switchTrigger.isChecked) {
            // Preserve existing position if we have one
            val existingTrigger = config.trigger ?: TriggerConfig()
            TriggerConfig(
                enabled = true,
                x = existingTrigger.x,
                y = existingTrigger.y,
                shape = shape,
                opacity = binding.sliderOpacity.value,
                mode = if (binding.switchMode.isChecked) "hold" else "tap",
                locked = binding.switchLocked.isChecked
            )
        } else {
            config.trigger?.copy(enabled = false)
        }

        config = MacroConfig(
            name = binding.etName.text.toString().trim(),
            startDelayMs = binding.sliderDelay.value.toInt(),
            repeatCount = if (isInfinite) 0 else repeatCount,
            speed = selectedSpeed,
            trigger = trigger
        )

        MacroConfig.save(macroFile, config)

        // Notify OverlayService to refresh triggers
        val refreshIntent = android.content.Intent(this, OverlayService::class.java).apply {
            action = "ACTION_REFRESH_TRIGGERS"
        }
        startService(refreshIntent)

        Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
