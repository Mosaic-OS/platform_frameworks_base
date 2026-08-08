package com.android.systemui.qs.tiles.dialog

import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.bluetooth.BluetoothCodecController
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Codec picker for the active A2DP device, mirroring Developer options -> Bluetooth audio codec. */
class BluetoothCodecDialogDelegate
@AssistedInject
constructor(
    @Assisted private val context: Context,
    private val dialogFactory: SystemUIDialog.Factory,
    private val manager: BluetoothCodecDialogManager,
    private val codecController: BluetoothCodecController,
) : SystemUIDialog.Delegate {

    /**
     * A picker row. [codecType] stays SOURCE_CODEC_TYPE_INVALID for the "system selection" entry
     * and [ldacQuality] is non-null only for an LDAC preset, so the two can never be confused.
     */
    private data class CodecRow(
        val title: String,
        val summary: String? = null,
        val codecType: Int = BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID,
        val ldacQuality: Long? = null,
        val isHeader: Boolean = false,
        val isSelected: Boolean = false,
    )

    override fun createDialog(): SystemUIDialog {
        val dialog = dialogFactory.create(this, context)
        dialog.setView(buildContentView(dialog))
        dialog.setOnDismissListener { manager.destroyDialog() }
        return dialog
    }

    private fun buildContentView(dialog: SystemUIDialog): View {
        val root =
            LayoutInflater.from(context).inflate(R.layout.blutilities_dialog, null, false)
        val device = codecController.activeDevice

        root.requireViewById<TextView>(R.id.blutilities_title).text =
            device?.let { deviceName(it) } ?: context.getString(R.string.blutilities_title)

        root.requireViewById<RecyclerView>(R.id.blutilities_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter =
                CodecAdapter(context, buildRows(device)) { row ->
                    dialog.dismiss()
                    if (device != null) applySelection(device, row)
                }
        }

        root.requireViewById<View>(R.id.blutilities_cancel).setOnClickListener {
            dialog.dismiss()
        }
        return root
    }

    private fun deviceName(device: BluetoothDevice): String =
        runCatching { device.alias ?: device.name }.getOrNull()
            ?: context.getString(R.string.blutilities_title)

    private fun buildRows(device: BluetoothDevice?): List<CodecRow> {
        if (device == null) {
            return listOf(
                CodecRow(context.getString(R.string.blutilities_no_device), isHeader = true)
            )
        }
        val status: BluetoothCodecStatus? = codecController.getCodecStatus(device)
        val current = status?.codecConfig
        val selectable = status?.codecsSelectableCapabilities.orEmpty()
        val systemSelected =
            current?.codecPriority == BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT

        return buildList {
            add(
                CodecRow(
                    title = context.getString(R.string.blutilities_system),
                    summary = context.getString(R.string.blutilities_system_summary),
                    isSelected = systemSelected,
                )
            )
            if (selectable.isEmpty()) return@buildList

            add(CodecRow(context.getString(R.string.blutilities_header), isHeader = true))
            selectable
                .sortedByDescending { sortOrder(it.codecType) }
                .forEach { capability ->
                    val active = current?.codecType == capability.codecType && !systemSelected
                    add(
                        CodecRow(
                            title = codecController.codecName(capability.codecType),
                            codecType = capability.codecType,
                            isSelected = active,
                        )
                    )
                    if (
                        active &&
                            capability.codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
                    ) {
                        add(
                            CodecRow(
                                context.getString(R.string.blutilities_header_ldac),
                                isHeader = true,
                            )
                        )
                        addAll(ldacRows(current))
                    }
                }
        }
    }

    private fun ldacRows(current: BluetoothCodecConfig?): List<CodecRow> {
        val presets =
            listOf(
                Triple(
                    R.string.blutilities_ldac_quality,
                    "990/909 kbps",
                    BluetoothCodecController.LDAC_QUALITY_BEST,
                ),
                Triple(
                    R.string.blutilities_ldac_balanced,
                    "660/606 kbps",
                    BluetoothCodecController.LDAC_QUALITY_BALANCED,
                ),
                Triple(
                    R.string.blutilities_ldac_connection,
                    "330/303 kbps",
                    BluetoothCodecController.LDAC_QUALITY_CONNECTION,
                ),
                Triple(
                    R.string.blutilities_ldac_adaptive,
                    context.getString(R.string.blutilities_ldac_adaptive_summary),
                    BluetoothCodecController.LDAC_QUALITY_ADAPTIVE,
                ),
            )
        val currentQuality = current?.codecSpecific1 ?: -1L

        return presets.map { (labelRes, summary, quality) ->
            CodecRow(
                title = context.getString(labelRes),
                summary = summary,
                codecType = BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                ldacQuality = quality,
                isSelected = currentQuality == quality,
            )
        }
    }

    private fun applySelection(device: BluetoothDevice, row: CodecRow) {
        if (!codecController.setCodecPreference(device, buildConfig(row))) {
            Toast.makeText(context, R.string.blutilities_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildConfig(row: CodecRow): BluetoothCodecConfig =
        BluetoothCodecConfig.Builder()
            .apply {
                when {
                    row.codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID -> {
                        setCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC)
                        setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT)
                    }
                    row.ldacQuality != null -> {
                        setCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC)
                        setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                        setCodecSpecific1(row.ldacQuality)
                    }
                    else -> {
                        setCodecType(row.codecType)
                        setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                    }
                }
            }
            .build()

    private fun sortOrder(codecType: Int): Int =
        when (codecType) {
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> 0
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> 1
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> 2
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> 3
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> 4
            BluetoothCodecController.CODEC_TYPE_APTX_ADAPTIVE -> 5
            BluetoothCodecController.CODEC_TYPE_LC3 -> 6
            BluetoothCodecController.CODEC_TYPE_OPUS -> 7
            else -> -1
        }

    private class CodecAdapter(
        private val context: Context,
        private val rows: List<CodecRow>,
        private val onSelected: (CodecRow) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) =
            if (rows[position].isHeader) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(inflater.inflate(R.layout.blutilities_header, parent, false))
            } else {
                ItemHolder(inflater.inflate(R.layout.blutilities_item, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            when (holder) {
                is HeaderHolder -> holder.header.text = row.title
                is ItemHolder -> {
                    holder.name.text = row.title
                    holder.summary.text = row.summary
                    holder.summary.visibility = if (row.summary == null) View.GONE else View.VISIBLE
                    holder.selected.visibility =
                        if (row.isSelected) View.VISIBLE else View.INVISIBLE
                    holder.itemView.setOnClickListener { onSelected(row) }
                }
            }
        }

        override fun getItemCount() = rows.size

        private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            val header: TextView = view.requireViewById(R.id.header)
        }

        private class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.requireViewById(R.id.name)
            val summary: TextView = view.requireViewById(R.id.summary)
            val selected: ImageView = view.requireViewById(R.id.selected)
        }

        private companion object {
            const val TYPE_HEADER = 0
            const val TYPE_ITEM = 1
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(context: Context): BluetoothCodecDialogDelegate
    }
}
