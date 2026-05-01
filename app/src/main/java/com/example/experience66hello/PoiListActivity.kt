package com.example.experience66hello

import android.content.Intent
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import com.google.android.gms.location.LocationServices
import android.content.res.ColorStateList
import android.widget.FrameLayout

class PoiListActivity : AppCompatActivity() {

    private var tts: android.speech.tts.TextToSpeech? = null
    private var isTtsReady = false
    private lateinit var poiAdapter: PoiAdapter
    private lateinit var allPois: List<Route66Landmark>
    private lateinit var clearSearchBtn: TextView

    private fun color(resId: Int): Int {
        return androidx.core.content.ContextCompat.getColor(this, resId)
    }

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
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val darkModeEnabled = prefs.getBoolean(AppSettings.KEY_DARK_MODE, false)

        AppCompatDelegate.setDefaultNightMode(
            if (darkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)

        // Same source as the map: [Route66DatabaseParser.POI_DATASET_ASSET_NAME] (CUpdated.csv).
        val route66Repository = Route66DatabaseRepository(this)
        route66Repository.loadDatabase()

        allPois = route66Repository.getAllLandmarks()

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (androidx.core.app.ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    allPois = allPois.sortedBy { poi ->
                        val result = FloatArray(1)
                        android.location.Location.distanceBetween(
                            location.latitude,
                            location.longitude,
                            poi.latitude,
                            poi.longitude,
                            result
                        )
                        result[0]
                    }

                    poiAdapter.submitList(allPois)
                }
            }
        }

        poiAdapter = PoiAdapter(
            onShow = { landmark ->
                val data = Intent().putExtra("landmark_id", landmark.id)
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
            setBackgroundColor(color(R.color.surface))
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(color(R.color.text_primary))
            background = null
            layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp()).apply {
                marginEnd = 8.dp()
            }
            setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.card_background))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setPadding(10.dp(), 0, 10.dp(), 0)
        }

        val searchBar = EditText(this).apply {
            hint = "Search POIs..."
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setSingleLine(true)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_muted))

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString().orEmpty()
                    clearSearchBtn.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
                    filterList(query)
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }

        clearSearchBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(color(R.color.text_muted))
            visibility = View.GONE
            setPadding(12.dp(), 8.dp(), 4.dp(), 8.dp())
            setOnClickListener {
                searchBar.setText("")
            }
        }

        searchContainer.addView(searchBar)
        searchContainer.addView(clearSearchBtn)

        topRow.addView(backBtn)
        topRow.addView(searchContainer)
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
            setBackgroundColor(color(R.color.surface))
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
        NavigationHelper.startNavigation(this, landmark)
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
        val listenBtn: LinearLayout,
        val aboutBtn: LinearLayout,
        val navigateBtn: LinearLayout,
        val image: ImageView,
    ) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val context = parent.context
        fun Int.dp(): Int = (this * context.resources.displayMetrics.density + 0.5f).toInt()

        fun createPillButton(
            text: String,
            iconRes: Int,
            bgColorRes: Int,
            contentColorRes: Int
        ): LinearLayout {
            val bgColor = androidx.core.content.ContextCompat.getColor(context, bgColorRes)
            val contentColor = androidx.core.content.ContextCompat.getColor(context, contentColorRes)

            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp(), 0, 14.dp(), 0)
                elevation = 4f
                isClickable = true
                isFocusable = true

                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    40.dp()
                ).apply {
                    marginEnd = 10.dp()
                }

                background = androidx.core.content.ContextCompat.getDrawable(
                    context,
                    R.drawable.btn_pill
                )

                backgroundTintList = ColorStateList.valueOf(bgColor)

                addView(ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(contentColor)
                    layoutParams = LinearLayout.LayoutParams(
                        18.dp(),
                        18.dp()
                    ).apply {
                        marginEnd = 8.dp()
                    }
                })

                addView(TextView(context).apply {
                    this.text = text
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(contentColor)
                })
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(context, R.color.card_background)
            )
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
            setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(context, R.color.accent_orange)
            )
            setPadding(18.dp(), 14.dp(), 18.dp(), 14.dp())
        }

        val title = TextView(context).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(
                androidx.core.content.ContextCompat.getColor(context, R.color.white)
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val expandBtn = TextView(context).apply {
            text = "▼"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(16, 8, 16, 8)
        }

        headerBar.addView(title)
        headerBar.addView(expandBtn)

        val image = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                150.dp()
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(context, R.color.surface)
            )
        }

        val descriptionText = TextView(context).apply {
            textSize = 14.5f
            setTextColor(
                androidx.core.content.ContextCompat.getColor(context, R.color.text_primary)
            )
            setLineSpacing(6f, 1.05f)
            setPadding(18.dp(), 16.dp(), 18.dp(), 8.dp())
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(18.dp(), 8.dp(), 18.dp(), 16.dp())
        }

        val listenBtn = createPillButton(
            text = "Listen",
            iconRes = R.drawable.volume_up,
            bgColorRes = R.color.blue_tonal,
            contentColorRes = R.color.poi_blue
        )

        val aboutBtn = createPillButton(
            text = "More",
            iconRes = R.drawable.more_info,
            bgColorRes = R.color.purple_tonal,
            contentColorRes = R.color.poi_purple
        )

        val navigateBtn = createPillButton(
            text = "Navigate",
            iconRes = R.drawable.navigation,
            bgColorRes = R.color.green_tonal,
            contentColorRes = R.color.poi_green
        )


        buttonRow.addView(listenBtn)
        buttonRow.addView(aboutBtn)
        buttonRow.addView(navigateBtn)

        row.addView(headerBar)
        row.addView(image)
        row.addView(descriptionText)
        row.addView(buttonRow)

        return VH(
            row = row,
            headerBar = headerBar,
            title = title,
            expandBtn = expandBtn,
            descriptionText = descriptionText,
            image = image,
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
            holder.image.visibility = View.VISIBLE

            val imageName = item.id.lowercase()
                .replace("-", "_")
                .replace(" ", "_")

            val imageResId = holder.itemView.context.resources.getIdentifier(
                imageName,
                "drawable",
                holder.itemView.context.packageName
            )

            if (imageResId != 0) {
                holder.image.setImageResource(imageResId)
            } else {
                holder.image.setImageResource(R.drawable.ic_launcher_foreground) // safe fallback
            }

            holder.descriptionText.text = fullDescription
            holder.descriptionText.maxLines = Int.MAX_VALUE
            holder.descriptionText.ellipsize = null
            holder.expandBtn.text = "▲"

        } else {
            holder.image.visibility = View.GONE

            holder.descriptionText.text = previewDescription
            holder.descriptionText.maxLines = 1
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