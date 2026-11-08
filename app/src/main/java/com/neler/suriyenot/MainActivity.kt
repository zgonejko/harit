package com.neler.suriyenot

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var store: Store
    private lateinit var topCard: MaterialCardView
    private lateinit var filterChips: ChipGroup
    private lateinit var bubble: View
    private var locationOverlay: MyLocationNewOverlay? = null
    private val markers = mutableListOf<Marker>()

    /** Haritada dokunulan, henüz kaydedilmemiş nokta (turuncu pin + "Buraya ekle" balonu) */
    private var selectionMarker: Marker? = null
    private var selected: GeoPoint? = null

    /** null = tüm kategoriler */
    private var filter: String? = null

    private val palette = intArrayOf(
        Color.parseColor("#E53935"), Color.parseColor("#1E88E5"),
        Color.parseColor("#8E24AA"), Color.parseColor("#FB8C00"),
        Color.parseColor("#00897B"), Color.parseColor("#6D4C41"),
        Color.parseColor("#D81B60"), Color.parseColor("#3949AB")
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                setupLocationOverlay()
            } else {
                Toast.makeText(this, "Konum izni verilmedi, mavi nokta görünmez.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName
        // Gezdiğin harita parçaları telefonda uzun süre saklansın (internetsiz kullanım için)
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 1500L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 1300L * 1024 * 1024
        Configuration.getInstance().expirationOverrideDuration = 90L * 24 * 60 * 60 * 1000

        setContentView(R.layout.activity_main)
        store = Store(this)

        topCard = findViewById(R.id.topCard)
        filterChips = findViewById(R.id.filterChips)
        bubble = findViewById(R.id.bubble)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(6.5)
        map.controller.setCenter(GeoPoint(35.0, 38.5)) // Suriye

        // Haritada bir yere dokununca orada pin belirir, yanında "Buraya ekle" balonu çıkar
        val tapReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                selectPoint(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        map.overlays.add(MapEventsOverlay(tapReceiver))

        // Harita kayarken/yakınlaşırken balon pinin yanında kalsın
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                positionBubble()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                positionBubble()
                return false
            }
        })

        findViewById<View>(R.id.btnBubbleAdd).setOnClickListener {
            selected?.let { showPlaceSheet(null, it.latitude, it.longitude, false) }
        }
        findViewById<View>(R.id.btnBubbleClose).setOnClickListener { clearSelection() }

        findViewById<View>(R.id.fabMe).setOnClickListener {
            val me = locationOverlay?.myLocation
            if (me == null) {
                Toast.makeText(this, "GPS konumu henüz alınamadı. GPS açık mı?", Toast.LENGTH_SHORT).show()
            } else {
                map.controller.animateTo(me)
                if (map.zoomLevelDouble < 15.0) map.controller.setZoom(16.0)
            }
        }

        findViewById<View>(R.id.fabHere).setOnClickListener {
            val me = locationOverlay?.myLocation
            if (me == null) {
                Toast.makeText(this, "GPS konumu henüz alınamadı. GPS açık mı?", Toast.LENGTH_SHORT).show()
            } else {
                showPlaceSheet(null, me.latitude, me.longitude, true)
            }
        }

        findViewById<View>(R.id.btnCategories).setOnClickListener { showCategoriesSheet() }

        // Geri tuşu önce seçimi kaldırır
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selected != null) {
                    clearSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        buildFilterChips()
        requestLocation()
        refreshMarkers()
    }

    // ---------- Konum ----------

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            setupLocationOverlay()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun setupLocationOverlay() {
        if (locationOverlay != null) return
        val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        val dot = dotBitmap(Color.parseColor("#1565C0"), Color.WHITE, 22)
        overlay.setPersonIcon(dot)
        overlay.setDirectionArrow(dot, dot)
        overlay.setPersonAnchor(0.5f, 0.5f)
        overlay.setDirectionAnchor(0.5f, 0.5f)
        overlay.enableMyLocation()
        overlay.runOnFirstFix {
            runOnUiThread {
                overlay.myLocation?.let {
                    map.controller.animateTo(it)
                    map.controller.setZoom(15.0)
                }
            }
        }
        map.overlays.add(overlay)
        locationOverlay = overlay
        map.invalidate()
    }

    // ---------- Seçim (dokunulan nokta) ----------

    private fun selectPoint(p: GeoPoint) {
        selected = p
        selectionMarker?.let { map.overlays.remove(it) }

        // Turuncu pin, yazısız. Sivri uç tam dokunulan koordinattadır.
        val (bmp, anchorU, anchorV) = labeledMarker(Color.parseColor("#FF6D00"), Color.WHITE, false, "", null)
        val marker = Marker(map)
        marker.position = p
        marker.icon = BitmapDrawable(resources, bmp)
        marker.setAnchor(anchorU, anchorV)
        marker.setOnMarkerClickListener { _, _ -> true }
        map.overlays.add(marker)
        selectionMarker = marker

        bubble.visibility = View.VISIBLE
        positionBubble()
        map.invalidate()
    }

    private fun clearSelection() {
        selectionMarker?.let { map.overlays.remove(it) }
        selectionMarker = null
        selected = null
        bubble.visibility = View.GONE
        map.invalidate()
    }

    /** "Buraya ekle" balonunu pinin başının yanına koyar (sağa sığmazsa sola). */
    private fun positionBubble() {
        val p = selected ?: return
        if (bubble.visibility != View.VISIBLE) return

        val pt = Point()
        map.projection.toPixels(p, pt)

        bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bw = bubble.measuredWidth
        val bh = bubble.measuredHeight

        val headCenterY = pt.y - dpToPx(27) // pin başının ortası, sivri uçtan 27dp yukarıda
        var x = pt.x + dpToPx(20)
        if (x + bw > map.width - dpToPx(8)) x = pt.x - dpToPx(20) - bw
        x = Math.max(dpToPx(8), x)

        val minY = topCard.bottom + dpToPx(8)
        val maxY = Math.max(minY, map.height - bh - dpToPx(8))
        val y = (headCenterY - bh / 2).coerceIn(minY, maxY)

        bubble.x = x.toFloat()
        bubble.y = y.toFloat()
    }

    /** Alt panel açılacağı zaman nokta panelin altında kalmasın diye haritayı yukarı kaydırır. */
    private fun ensureVisibleAbove(p: GeoPoint) {
        val pt = Point()
        map.projection.toPixels(p, pt)
        if (pt.y > map.height * 0.30f) {
            val desiredY = (map.height * 0.16f).toInt()
            val target = map.projection.fromPixels(map.width / 2, map.height / 2 + (pt.y - desiredY))
            map.controller.animateTo(target)
        }
    }

    // ---------- Marker çizimi ----------

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun dotBitmap(fill: Int, ring: Int, sizeDp: Int): Bitmap {
        val size = dpToPx(sizeDp)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = size * 0.16f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = fill
        c.drawCircle(size / 2f, size / 2f, size / 2f - stroke / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = ring
        c.drawCircle(size / 2f, size / 2f, size / 2f - stroke / 2f, paint)
        return bmp
    }

    /**
     * Ucu sivri bir "pin" ve sağında açıklama yazısı olan tek bir resim üretir.
     * Pinin sivri ucu işaretçinin çapasıdır, yani tam kaydedilen koordinatın üstüne oturur.
     * rawSub verilirse ana yazının altına küçük harflerle (kategori adı) eklenir.
     * Döndürülen üçlü: resim, yatay çapa, dikey çapa.
     */
    private fun labeledMarker(
        fill: Int, ring: Int, visited: Boolean, rawText: String, rawSub: String?
    ): Triple<Bitmap, Float, Float> {
        val dm = resources.displayMetrics
        val gap = dpToPx(4)
        val pad = dpToPx(4)

        fun clip(s: String): String {
            val t = s.replace("\n", " ").trim()
            return if (t.length > 40) t.take(40) + "…" else t
        }

        val text = clip(rawText)
        val sub = rawSub?.let { clip(it) }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, dm)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.color = Color.parseColor("#212121")

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        subPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, dm)
        subPaint.typeface = Typeface.DEFAULT
        subPaint.color = Color.parseColor("#555555")

        // Okunaklı olsun diye yazıların etrafına beyaz kontur
        val haloPaint = Paint(textPaint)
        haloPaint.style = Paint.Style.STROKE
        haloPaint.strokeWidth = dpToPx(3).toFloat()
        haloPaint.color = Color.WHITE

        val subHaloPaint = Paint(subPaint)
        subHaloPaint.style = Paint.Style.STROKE
        subHaloPaint.strokeWidth = dpToPx(3).toFloat()
        subHaloPaint.color = Color.WHITE

        val fm = textPaint.fontMetrics
        val sfm = subPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val subH = sfm.descent - sfm.ascent
        val blockH = if (text.isEmpty()) 0f else if (sub != null) textH + subH else textH

        var textWidthF = textPaint.measureText(text)
        if (sub != null) textWidthF = Math.max(textWidthF, subPaint.measureText(sub))
        val textWidth = Math.ceil(textWidthF.toDouble()).toInt()

        // --- Pin ölçüleri ---
        val m = dpToPx(2).toFloat()          // kenar boşluğu
        val r = dpToPx(13).toFloat()         // pin başının yarıçapı
        val pinH = dpToPx(40).toFloat()      // baş üstünden sivri uca yükseklik
        val pinW = 2 * r + 2 * m
        val headCy = m + r

        // Yazı bloğu pin başından yüksekse üstte ek boşluk bırak
        val shift = Math.max(0f, blockH / 2f + pad / 2f - headCy)
        val tipY = shift + m + pinH
        val textBottom = shift + headCy + blockH / 2f + pad / 2f

        val labelW = if (text.isEmpty()) 0 else gap + textWidth + pad
        val width = Math.ceil((pinW + labelW).toDouble()).toInt()
        val height = Math.ceil(Math.max(tipY + m, textBottom).toDouble()).toInt()

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val cx = m + r
        val cy = shift + headCy

        // Pin gövdesi: daire + uca inen üçgen (birleşim)
        val d = tipY - cy
        val theta = Math.acos((r / d).toDouble())
        val dx = (r * Math.sin(theta)).toFloat()
        val dy = (r * Math.cos(theta)).toFloat()
        val body = Path()
        body.moveTo(cx, tipY)
        body.lineTo(cx - dx, cy + dy)
        body.lineTo(cx + dx, cy + dy)
        body.close()
        val head = Path()
        head.addCircle(cx, cy, r, Path.Direction.CW)
        body.op(head, Path.Op.UNION)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = fill
        canvas.drawPath(body, fillPaint)

        val outline = Paint(Paint.ANTI_ALIAS_FLAG)
        outline.style = Paint.Style.STROKE
        outline.strokeWidth = dpToPx(2).toFloat() + dpToPx(1) / 2f
        outline.strokeJoin = Paint.Join.ROUND
        outline.color = ring
        canvas.drawPath(body, outline)

        // Baş içindeki işaret: gidildiyse beyaz tik, gidilmediyse beyaz nokta
        val mark = Paint(Paint.ANTI_ALIAS_FLAG)
        mark.color = Color.WHITE
        if (visited) {
            mark.style = Paint.Style.STROKE
            mark.strokeWidth = dpToPx(2).toFloat()
            mark.strokeCap = Paint.Cap.ROUND
            mark.strokeJoin = Paint.Join.ROUND
            val tick = Path()
            tick.moveTo(cx - r * 0.42f, cy)
            tick.lineTo(cx - r * 0.1f, cy + r * 0.32f)
            tick.lineTo(cx + r * 0.44f, cy - r * 0.32f)
            canvas.drawPath(tick, mark)
        } else {
            mark.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r * 0.36f, mark)
        }

        // Yazı: pin başının sağında, baş ortasına hizalı
        if (text.isNotEmpty()) {
            val x = pinW + gap
            val top = cy - blockH / 2f
            val baseline1 = top - fm.ascent
            canvas.drawText(text, x, baseline1, haloPaint)
            canvas.drawText(text, x, baseline1, textPaint)
            if (sub != null) {
                val baseline2 = top + textH - sfm.ascent
                canvas.drawText(sub, x, baseline2, subHaloPaint)
                canvas.drawText(sub, x, baseline2, subPaint)
            }
        }

        // Çapa = pinin sivri ucu
        return Triple(bmp, cx / width, tipY / height)
    }

    private fun categoryColor(category: String): Int {
        val idx = store.colorIdx[category] ?: Math.abs(category.hashCode())
        return palette[idx % palette.size]
    }

    private fun refreshMarkers() {
        map.overlays.removeAll(markers.toSet())
        markers.clear()

        store.places
            .filter { filter == null || it.category == filter }
            .forEach { place ->
                val marker = Marker(map)
                marker.position = GeoPoint(place.lat, place.lon)
                // Pin rengi = kategori; yeşil kenar + beyaz tik = gidildi, beyaz kenar = henüz gidilmedi.
                // Pinin sivri ucu tam işaretlenen konumdadır.
                val ring = if (place.visited) Color.parseColor("#2E7D32") else Color.WHITE
                // "Tümü" seçiliyken notun altında kategori adı da görünür; tek kategori seçiliyken sadece not.
                val hasNote = place.note.isNotBlank()
                val mainText = if (hasNote) place.note else place.category
                val subText = if (hasNote && filter == null) place.category else null
                val (bmp, anchorU, anchorV) =
                    labeledMarker(categoryColor(place.category), ring, place.visited, mainText, subText)
                marker.icon = BitmapDrawable(resources, bmp)
                marker.setAnchor(anchorU, anchorV)
                marker.setOnMarkerClickListener { _, _ ->
                    showDetailsSheet(place)
                    true
                }
                markers.add(marker)
                map.overlays.add(marker)
            }

        // Seçim pini her zaman en üstte kalsın
        selectionMarker?.let {
            map.overlays.remove(it)
            map.overlays.add(it)
        }
        map.invalidate()
    }

    // ---------- Alt paneller (haritayı kapatmayan) ----------

    private fun newSheet(): BottomSheetDialog {
        val d = BottomSheetDialog(this)
        d.window?.setDimAmount(0.08f)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return d
    }

    private fun showSheet(d: BottomSheetDialog, content: View) {
        d.setContentView(content)
        d.behavior.skipCollapsed = true
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.show()
    }

    private fun styleDot(chip: Chip, color: Int) {
        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(color)
        dot.setSize(dpToPx(12), dpToPx(12))
        chip.chipIcon = dot
        chip.isChipIconVisible = true
        chip.chipIconSize = dpToPx(12).toFloat()
        chip.chipIconTint = null
        chip.isCheckedIconVisible = false
    }

    private fun showPlaceSheet(existing: Place?, lat: Double, lon: Double, visitedDefault: Boolean) {
        if (store.categories.isEmpty()) {
            Toast.makeText(this, "Önce bir kategori ekle", Toast.LENGTH_SHORT).show()
            showCategoriesSheet()
            return
        }
        ensureVisibleAbove(GeoPoint(lat, lon))

        val dialog = newSheet()
        val v = layoutInflater.inflate(R.layout.sheet_place, null)
        val tvTitle = v.findViewById<TextView>(R.id.tvTitle)
        val tvCoords = v.findViewById<TextView>(R.id.tvCoords)
        val group = v.findViewById<ChipGroup>(R.id.categoryChips)
        val etNote = v.findViewById<TextInputEditText>(R.id.etNote)
        val swVisited = v.findViewById<MaterialSwitch>(R.id.swVisited)
        val btnNewCat = v.findViewById<MaterialButton>(R.id.btnNewCat)
        val newCatRow = v.findViewById<View>(R.id.newCatRow)
        val etNewCat = v.findViewById<TextInputEditText>(R.id.etNewCat)
        val btnAddCat = v.findViewById<MaterialButton>(R.id.btnAddCat)
        val btnCancel = v.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = v.findViewById<MaterialButton>(R.id.btnSave)

        tvTitle.text = if (existing == null) "Yeni yer" else "Yeri düzenle"
        tvCoords.text = String.format(Locale.US, "%.5f, %.5f", lat, lon)

        var selectedCategory: String = existing?.category
            ?: filter?.takeIf { it in store.categories }
            ?: store.categories.first()

        fun addChip(cat: String, checked: Boolean) {
            val chip = layoutInflater.inflate(R.layout.chip_filter, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = cat
            styleDot(chip, categoryColor(cat))
            chip.isChecked = checked
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedCategory = cat
            }
            group.addView(chip)
        }

        val options = store.categories.toMutableList()
        if (existing != null && existing.category !in options) options.add(existing.category)
        options.forEach { addChip(it, it == selectedCategory) }

        // Formdan çıkmadan yeni kategori ekleme
        btnNewCat.setOnClickListener {
            newCatRow.visibility = View.VISIBLE
            etNewCat.requestFocus()
        }

        fun commitNewCategory() {
            val name = etNewCat.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return
            if (store.addCategory(name)) {
                buildFilterChips()
                selectedCategory = name
                addChip(name, true)
                etNewCat.setText("")
                newCatRow.visibility = View.GONE
            } else {
                Toast.makeText(this, "Bu kategori zaten var", Toast.LENGTH_SHORT).show()
            }
        }
        btnAddCat.setOnClickListener { commitNewCategory() }
        etNewCat.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitNewCategory()
                true
            } else {
                false
            }
        }

        if (existing != null) etNote.setText(existing.note)
        swVisited.isChecked = existing?.visited ?: visitedDefault

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val note = etNote.text?.toString()?.trim().orEmpty()
            if (existing == null) {
                store.places.add(
                    Place(System.currentTimeMillis(), lat, lon, selectedCategory, note, swVisited.isChecked)
                )
                val sel = selected
                if (sel != null && sel.latitude == lat && sel.longitude == lon) clearSelection()
            } else {
                existing.category = selectedCategory
                existing.note = note
                existing.visited = swVisited.isChecked
            }
            store.savePlaces()
            refreshMarkers()
            dialog.dismiss()
        }

        showSheet(dialog, v)
    }

    private fun showDetailsSheet(place: Place) {
        ensureVisibleAbove(GeoPoint(place.lat, place.lon))

        val dialog = newSheet()
        val v = layoutInflater.inflate(R.layout.sheet_details, null)
        val tvCategory = v.findViewById<TextView>(R.id.tvCategory)
        val tvStatus = v.findViewById<TextView>(R.id.tvStatus)
        val tvNote = v.findViewById<TextView>(R.id.tvNote)
        val tvCoords = v.findViewById<TextView>(R.id.tvCoords)
        val btnDelete = v.findViewById<MaterialButton>(R.id.btnDelete)
        val btnEdit = v.findViewById<MaterialButton>(R.id.btnEdit)
        val btnToggle = v.findViewById<MaterialButton>(R.id.btnToggle)

        val pill = GradientDrawable()
        pill.cornerRadius = dpToPx(16).toFloat()
        pill.setColor(categoryColor(place.category))
        tvCategory.background = pill
        tvCategory.text = place.category

        val green = Color.parseColor("#2E7D32")
        if (place.visited) {
            tvStatus.text = "Gidildi"
            tvStatus.setTextColor(green)
            tvStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0)
            tvStatus.compoundDrawableTintList = ColorStateList.valueOf(green)
        } else {
            tvStatus.text = "Henüz gidilmedi"
            tvStatus.setTextColor(Color.parseColor("#6F7977"))
        }

        if (place.note.isBlank()) {
            tvNote.text = "Açıklama yok"
            tvNote.setTextColor(Color.parseColor("#6F7977"))
        } else {
            tvNote.text = place.note
        }
        tvCoords.text = String.format(Locale.US, "%.5f, %.5f", place.lat, place.lon)

        btnToggle.text = if (place.visited) "Gidilmedi yap" else "Gidildi yap"
        btnToggle.setOnClickListener {
            place.visited = !place.visited
            store.savePlaces()
            refreshMarkers()
            dialog.dismiss()
        }

        btnEdit.setOnClickListener {
            dialog.dismiss()
            showPlaceSheet(place, place.lat, place.lon, place.visited)
        }

        // Silince onay sorulmaz, "Geri al" çıkar
        btnDelete.setOnClickListener {
            dialog.dismiss()
            val idx = store.places.indexOf(place)
            store.places.remove(place)
            store.savePlaces()
            refreshMarkers()
            Snackbar.make(map, "Yer silindi", Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.fabHere)
                .setAction("GERİ AL") {
                    store.places.add(idx.coerceIn(0, store.places.size), place)
                    store.savePlaces()
                    refreshMarkers()
                }
                .show()
        }

        showSheet(dialog, v)
    }

    private fun showCategoriesSheet() {
        val dialog = newSheet()
        val v = layoutInflater.inflate(R.layout.sheet_categories, null)
        val chips = v.findViewById<ChipGroup>(R.id.catChips)
        val tvEmpty = v.findViewById<TextView>(R.id.tvEmpty)
        val etNew = v.findViewById<TextInputEditText>(R.id.etNewCat)
        val btnAdd = v.findViewById<MaterialButton>(R.id.btnAddCat)
        val btnDone = v.findViewById<MaterialButton>(R.id.btnDone)

        fun rebuild() {
            chips.removeAllViews()
            tvEmpty.visibility = if (store.categories.isEmpty()) View.VISIBLE else View.GONE
            store.categories.toList().forEach { cat ->
                val chip = layoutInflater.inflate(R.layout.chip_input, chips, false) as Chip
                chip.text = cat
                chip.chipBackgroundColor = ColorStateList.valueOf(categoryColor(cat))
                chip.chipStrokeWidth = 0f
                chip.setTextColor(Color.WHITE)
                chip.isCloseIconVisible = true
                chip.closeIconTint = ColorStateList.valueOf(Color.WHITE)
                chip.setOnCloseIconClickListener {
                    store.removeCategory(cat)
                    buildFilterChips()
                    refreshMarkers()
                    rebuild()
                }
                chips.addView(chip)
            }
        }

        fun addNew() {
            val name = etNew.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return
            if (store.addCategory(name)) {
                etNew.setText("")
                buildFilterChips()
                rebuild()
            } else {
                Toast.makeText(this, "Bu kategori zaten var", Toast.LENGTH_SHORT).show()
            }
        }

        btnAdd.setOnClickListener { addNew() }
        etNew.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addNew()
                true
            } else {
                false
            }
        }
        btnDone.setOnClickListener { dialog.dismiss() }

        rebuild()
        showSheet(dialog, v)
    }

    // ---------- Üstteki kategori filtresi ----------

    private fun buildFilterChips() {
        filterChips.setOnCheckedStateChangeListener(null)
        filterChips.removeAllViews()

        if (filter != null && filter !in store.categories) filter = null

        val ids = mutableMapOf<Int, String?>()
        fun add(label: String, value: String?, color: Int?) {
            val chip = layoutInflater.inflate(R.layout.chip_filter, filterChips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = label
            if (color != null) styleDot(chip, color)
            ids[chip.id] = value
            filterChips.addView(chip)
            if (value == filter) chip.isChecked = true
        }

        add("Tümü", null, null)
        store.categories.forEach { add(it, it, categoryColor(it)) }

        filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newFilter = ids[id]
            if (newFilter != filter) {
                filter = newFilter
                refreshMarkers()
            }
        }
        refreshMarkers()
    }

    // ---------- Yaşam döngüsü ----------

    override fun onResume() {
        super.onResume()
        map.onResume()
        locationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        locationOverlay?.disableMyLocation()
        map.onPause()
    }
}
