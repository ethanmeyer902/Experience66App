package com.example.experience66hello

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class PoiListActivity : ComponentActivity() {

    private var tts: android.speech.tts.TextToSpeech? = null
    private var isTtsReady = false
    private lateinit var poiAdapter: PoiAdapter
    private lateinit var allPois: List<Route66Landmark>

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun filterList(query: String) {
        val filtered = if (query.isBlank()) {
            allPois
        } else {
            allPois.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true) ||
                        it.id.contains(query, ignoreCase = true)
            }
        }
        poiAdapter.submitList(filtered)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        allPois = ArizonaLandmarks.landmarks

        poiAdapter = PoiAdapter(
            onShow = { landmark ->
                val data = Intent()
                    .putExtra("landmark_id", landmark.id)
                setResult(RESULT_OK, data)
                finish()
            },
            onNavigate = { landmark ->
                navigateToLandmark(landmark)
            },
            onAbout = { landmark ->
                openAboutForLandmark(landmark)
            },
            onListen = { landmark ->
                listenToLandmark(landmark)
            }
        )
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(java.util.Locale.US)
                isTtsReady = result != android.speech.tts.TextToSpeech.LANG_MISSING_DATA &&
                        result != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                isTtsReady = false
            }
        }
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val searchBar = EditText(this).apply {
            hint = "Search POIs..."
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 10.dp()
            }
            setSingleLine(true)
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(Color.WHITE)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterList(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val backBtn = Button(this).apply {
            text = "Back to Map"
            setBackgroundColor(Color.parseColor("#424242"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        topRow.addView(searchBar)
        topRow.addView(backBtn)
        rootLayout.addView(topRow)

        rootLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                12.dp()
            )
        })

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PoiListActivity)
            adapter = poiAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        rootLayout.addView(recyclerView)
        setContentView(rootLayout)

        poiAdapter.submitList(allPois)
    }
    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
    private fun navigateToLandmark(landmark: Route66Landmark) {
        val lat = landmark.latitude
        val lon = landmark.longitude

        val gmmIntentUri = android.net.Uri.parse("google.navigation:q=$lat,$lon&mode=d")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
        }

        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            val browserUri = android.net.Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving"
            )
            startActivity(Intent(Intent.ACTION_VIEW, browserUri))
        }
    }

    private fun listenToLandmark(landmark: Route66Landmark) {
        if (!isTtsReady || tts == null) {
            android.widget.Toast.makeText(this, "Voice not ready yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val textToSpeak = buildString {
            append(landmark.name).append(". ")
            append(landmark.description)
        }

        tts?.stop()
        tts?.speak(
            textToSpeak,
            android.speech.tts.TextToSpeech.QUEUE_FLUSH,
            null,
            "POI_LIST_TTS"
        )
    }

    private fun openAboutForLandmark(landmark: Route66Landmark) {
        Thread {
            try {
                val archiveRepository = ArchiveRepository(this)
                val route66Repository = Route66DatabaseRepository(this)

                if (!archiveRepository.isLoaded) {
                    archiveRepository.loadArchiveData()
                }

                val allItems = archiveRepository.getAllItems()
                val matchedItems = route66Repository
                    .matchArchiveItemsToLandmark(landmark, allItems)
                    .toMutableList()

                if (matchedItems.isEmpty()) {
                    val landmarkNameLower = landmark.name.lowercase()
                    val landmarkIdLower = landmark.id.lowercase()
                    val landmarkWords = landmarkNameLower
                        .split(" ", "-", "_", "'", "Ghost", "Town")
                        .filter { it.length > 3 }

                    allItems.forEach { item ->
                        val callLower = item.callNumber.lowercase()
                        val matches = landmarkWords.any { word -> callLower.contains(word) } ||
                                callLower.contains(landmarkIdLower)

                        if (matches && !matchedItems.contains(item)) {
                            matchedItems.add(item)
                        }
                    }
                }

                runOnUiThread {
                    if (matchedItems.isNotEmpty()) {
                        val firstItem = matchedItems.first()
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(firstItem.referenceUrl)))
                    } else {
                        val contentDmBaseUrl = "http://cdm16748.contentdm.oclc.org"
                        val collectionUrl = "$contentDmBaseUrl/digital/collection/cpa"
                        val searchQuery = landmark.name.replace(" ", "+").replace("'", "%27")
                        val searchUrl = "$collectionUrl/search/searchterm/$searchQuery"

                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(searchUrl)))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        "Could not open archive: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}

private class PoiAdapter(
    private val onShow: (Route66Landmark) -> Unit,
    private val onNavigate: (Route66Landmark) -> Unit,
    private val onAbout: (Route66Landmark) -> Unit,
    private val onListen: (Route66Landmark) -> Unit
) : ListAdapter<Route66Landmark, PoiAdapter.VH>(DIFF) {

    private val expandedIds = mutableSetOf<String>()

    class VH(
        val row: LinearLayout,
        val headerBar: LinearLayout,
        val title: TextView,
        val expandBtn: TextView,
        val descriptionText: TextView,
        val buttonRow: LinearLayout,
        val listenBtn: Button,
        val aboutBtn: Button,
        val navigateBtn: Button
    ) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val context = parent.context
        fun Int.dp(): Int = (this * context.resources.displayMetrics.density + 0.5f).toInt()

        fun styleActionButton(button: Button, color: String) {
            button.background = androidx.core.content.ContextCompat.getDrawable(
                context,
                R.drawable.btn_pill
            )
            button.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(color))
            button.setTextColor(Color.WHITE)
            button.textSize = 12f
            button.isAllCaps = false
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 14.dp()
            }
            elevation = 10f
        }

        val headerBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FF7A00"))
            setPadding(18.dp(), 14.dp(), 18.dp(), 14.dp())
        }

        val title = TextView(context).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val expandBtn = TextView(context).apply {
            text = "▼"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
        }

        headerBar.addView(title)
        headerBar.addView(expandBtn)

        val descriptionText = TextView(context).apply {
            textSize = 14.5f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(6f, 1.05f)
            setPadding(18.dp(), 16.dp(), 18.dp(), 8.dp())
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(18.dp(), 8.dp(), 18.dp(), 16.dp())
        }

        val listenBtn = Button(context).apply {
            text = "🔊 Listen"
            styleActionButton(this, "#1976D2")
        }

        val aboutBtn = Button(context).apply {
            text = "ℹ️ About"
            styleActionButton(this, "#7B1FA2")
        }

        val navigateBtn = Button(context).apply {
            text = "Navigate"
            styleActionButton(this, "#2E7D32")
        }

        fun spacer(): View {
            return View(context).apply {
                layoutParams = LinearLayout.LayoutParams(12.dp(), 1)
            }
        }

        buttonRow.addView(listenBtn)
        buttonRow.addView(spacer())
        buttonRow.addView(aboutBtn)
        buttonRow.addView(spacer())
        buttonRow.addView(navigateBtn)

        row.addView(headerBar)
        row.addView(descriptionText)
        row.addView(buttonRow)

        return VH(
            row = row,
            headerBar = headerBar,
            title = title,
            expandBtn = expandBtn,
            descriptionText = descriptionText,
            buttonRow = buttonRow,
            listenBtn = listenBtn,
            aboutBtn = aboutBtn,
            navigateBtn = navigateBtn
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val isExpanded = expandedIds.contains(item.id)

        holder.title.text = item.name

        val fullDescription = item.description.ifBlank { "No description available." }
        val previewDescription =
            if (fullDescription.length > 140) fullDescription.take(140) + "..."
            else fullDescription

        if (isExpanded) {
            holder.descriptionText.text = fullDescription
            holder.descriptionText.maxLines = Int.MAX_VALUE
            holder.descriptionText.ellipsize = null
            holder.expandBtn.text = "▲"
        } else {
            holder.descriptionText.text = previewDescription
            holder.descriptionText.maxLines = 3
            holder.descriptionText.ellipsize = TextUtils.TruncateAt.END
            holder.expandBtn.text = "▼"
        }

        holder.expandBtn.setOnClickListener {
            if (expandedIds.contains(item.id)) {
                expandedIds.remove(item.id)
            } else {
                expandedIds.add(item.id)
            }

            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                notifyItemChanged(currentPos)
            }
        }

        holder.row.setOnClickListener {
            onShow(item)
        }

        holder.listenBtn.setOnClickListener {
            onListen(item)
        }

        holder.aboutBtn.setOnClickListener {
            onAbout(item)
        }

        holder.navigateBtn.setOnClickListener {
            onNavigate(item)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Route66Landmark>() {
            override fun areItemsTheSame(oldItem: Route66Landmark, newItem: Route66Landmark): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Route66Landmark, newItem: Route66Landmark): Boolean {
                return oldItem == newItem
            }
        }
    }
}