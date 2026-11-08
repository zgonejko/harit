package com.neler.suriyenot

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var store: Store
    private lateinit var spFilter: Spinner
    private var locationOverlay: MyLocationNewOverlay? = null
    private val markers = mutableListOf<Marker>()

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

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(6.5)
        map.controller.setCenter(GeoPoint(35.0, 38.5)) // Suriye

        // Haritada bir yere dokununca yeni yer ekleme formu açılır
        val tapReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                showPlaceForm(null, p.latitude, p.longitude, false)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        map.overlays.add(MapEventsOverlay(tapReceiver))

        spFilter = findViewById(R.id.spFilter)
        spFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newFilter = if (position == 0) null else store.categories.getOrNull(position - 1)
                if (newFilter != filter) {
                    filter = newFilter
                    refreshMarkers()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshFilterSpinner()

        findViewById<Button>(R.id.btnMe).setOnClickListener {
            val me = locationOverlay?.myLocation
            if (me == null) {
                Toast.makeText(this, "GPS konumu henüz alınamadı. GPS açık mı?", Toast.LENGTH_SHORT).show()
            } else {
                map.controller.animateTo(me)
                if (map.zoomLevelDouble < 15.0) map.controller.setZoom(16.0)
            }
        }

        findViewById<Button>(R.id.btnMarkHere).setOnClickListener {
            val me = locationOverlay?.myLocation
            if (me == null) {
                Toast.makeText(this, "GPS konumu henüz alınamadı. GPS açık mı?", Toast.LENGTH_SHORT).show()
            } else {
                showPlaceForm(null, me.latitude, me.longitude, true)
            }
        }

        findViewById<Button>(R.id.btnCategories).setOnClickListener { showCategories() }

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
     * Sol tarafta renkli nokta, sağında açıklama yazısı olan tek bir resim üretir.
     * rawSub verilirse ana yazının altına küçük harflerle (kategori adı) eklenir.
     */
    private fun labeledMarker(fill: Int, ring: Int, rawText: String, rawSub: String?): Pair<Bitmap, Float> {
        val dm = resources.displayMetrics
        val dotDp = 28
        val dot = dpToPx(dotDp)
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
        val blockH = if (sub != null) textH + subH else textH

        var textWidthF = textPaint.measureText(text)
        if (sub != null) textWidthF = Math.max(textWidthF, subPaint.measureText(sub))
        val textWidth = Math.ceil(textWidthF.toDouble()).toInt()

        val width = dot + gap + textWidth + pad
        val height = Math.max(dot, Math.ceil((blockH + pad).toDouble()).toInt())

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Nokta dikeyde ortada; işaretçi çapası da buraya denk gelir
        canvas.drawBitmap(dotBitmap(fill, ring, dotDp), 0f, (height - dot) / 2f, null)

        val x = (dot + gap).toFloat()
        val top = (height - blockH) / 2f
        val baseline1 = top - fm.ascent
        canvas.drawText(text, x, baseline1, haloPaint)
        canvas.drawText(text, x, baseline1, textPaint)
        if (sub != null) {
            val baseline2 = top + textH - sfm.ascent
            canvas.drawText(sub, x, baseline2, subHaloPaint)
            canvas.drawText(sub, x, baseline2, subPaint)
        }

        return Pair(bmp, (dot / 2f) / width)
    }

    private fun categoryColor(category: String): Int {
        val idx = store.categories.indexOf(category)
        val i = if (idx >= 0) idx else Math.abs(category.hashCode())
        return palette[i % palette.size]
    }

    private fun refreshMarkers() {
        map.overlays.removeAll(markers.toSet())
        markers.clear()

        store.places
            .filter { filter == null || it.category == filter }
            .forEach { place ->
                val marker = Marker(map)
                marker.position = GeoPoint(place.lat, place.lon)
                // Dolgu rengi = kategori, yeşil halka = gidildi, beyaz halka = henüz gidilmedi
                val ring = if (place.visited) Color.parseColor("#2E7D32") else Color.WHITE
                // Noktanın yanında açıklama yazısı görünür (not yoksa kategori adı).
                // İşaretçi ikonları ekranda sabit boyutta kalır, harita yakınlaşsa da yazı büyümez/küçülmez.
                // "Tümü" seçiliyken notun altında kategori adı da görünür; tek kategori seçiliyken sadece not.
                val hasNote = place.note.isNotBlank()
                val mainText = if (hasNote) place.note else place.category
                val subText = if (hasNote && filter == null) place.category else null
                val (bmp, anchorU) = labeledMarker(categoryColor(place.category), ring, mainText, subText)
                marker.icon = BitmapDrawable(resources, bmp)
                marker.setAnchor(anchorU, Marker.ANCHOR_CENTER)
                marker.title = place.category
                marker.snippet = place.note
                marker.setOnMarkerClickListener { _, _ ->
                    showPlaceDetails(place)
                    true
                }
                markers.add(marker)
                map.overlays.add(marker)
            }
        map.invalidate()
    }

    // ---------- Yer ekleme / düzenleme ----------

    private fun showPlaceForm(existing: Place?, lat: Double, lon: Double, visitedDefault: Boolean) {
        if (store.categories.isEmpty()) {
            Toast.makeText(this, "Önce en az bir kategori ekle (ör. Elektronik, Otomobil).", Toast.LENGTH_LONG).show()
            showCategories()
            return
        }

        val v = layoutInflater.inflate(R.layout.dialog_place, null)
        val tvCoords = v.findViewById<TextView>(R.id.tvCoords)
        val spCategory = v.findViewById<Spinner>(R.id.spCategory)
        val etNote = v.findViewById<EditText>(R.id.etNote)
        val cbVisited = v.findViewById<CheckBox>(R.id.cbVisited)

        tvCoords.text = String.format("%.5f, %.5f", lat, lon)

        val options = store.categories.toMutableList()
        if (existing != null && existing.category !in options) options.add(existing.category)
        spCategory.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)

        if (existing != null) {
            spCategory.setSelection(options.indexOf(existing.category).coerceAtLeast(0))
            etNote.setText(existing.note)
        }
        cbVisited.isChecked = existing?.visited ?: visitedDefault

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Yeni yer" else "Yeri düzenle")
            .setView(v)
            .setPositiveButton("Kaydet") { _, _ ->
                val category = spCategory.selectedItem as String
                val note = etNote.text.toString().trim()
                if (existing == null) {
                    store.places.add(
                        Place(System.currentTimeMillis(), lat, lon, category, note, cbVisited.isChecked)
                    )
                } else {
                    existing.category = category
                    existing.note = note
                    existing.visited = cbVisited.isChecked
                }
                store.savePlaces()
                refreshMarkers()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showPlaceDetails(place: Place) {
        val status = if (place.visited) "Gidildi ✔" else "Henüz gidilmedi"
        val note = if (place.note.isBlank()) "(not yok)" else place.note
        AlertDialog.Builder(this)
            .setTitle(place.category)
            .setMessage("$note\n\n$status\n${String.format("%.5f, %.5f", place.lat, place.lon)}")
            .setPositiveButton(if (place.visited) "Gidilmedi yap" else "Gidildi yap") { _, _ ->
                place.visited = !place.visited
                store.savePlaces()
                refreshMarkers()
            }
            .setNeutralButton("Düzenle") { _, _ ->
                showPlaceForm(place, place.lat, place.lon, place.visited)
            }
            .setNegativeButton("Sil") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Bu yer silinsin mi?")
                    .setPositiveButton("Sil") { _, _ ->
                        store.places.remove(place)
                        store.savePlaces()
                        refreshMarkers()
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
            .show()
    }

    // ---------- Kategoriler ----------

    private fun refreshFilterSpinner() {
        val items = mutableListOf("Tümü")
        items.addAll(store.categories)
        spFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        val sel = filter?.let { items.indexOf(it) } ?: 0
        if (sel > 0) {
            spFilter.setSelection(sel)
        } else {
            filter = null
        }
    }

    private fun showCategories() {
        val builder = AlertDialog.Builder(this)
            .setPositiveButton("Yeni kategori") { _, _ -> askNewCategory() }
            .setNegativeButton("Kapat", null)

        if (store.categories.isEmpty()) {
            builder.setTitle("Kategoriler").setMessage("Henüz kategori yok. \"Yeni kategori\" ile ekle.")
        } else {
            builder.setTitle("Kategoriler (silmek için dokun)")
                .setItems(store.categories.toTypedArray()) { _, i ->
                    val name = store.categories[i]
                    AlertDialog.Builder(this)
                        .setMessage("\"$name\" kategorisi silinsin mi? Bu kategorideki yerler silinmez.")
                        .setPositiveButton("Sil") { _, _ ->
                            store.categories.remove(name)
                            store.saveCategories()
                            refreshFilterSpinner()
                            refreshMarkers()
                            showCategories()
                        }
                        .setNegativeButton("Vazgeç") { _, _ -> showCategories() }
                        .show()
                }
        }
        builder.show()
    }

    private fun askNewCategory() {
        val input = EditText(this)
        input.hint = "Kategori adı (ör. Elektronik)"
        input.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16))
        AlertDialog.Builder(this)
            .setTitle("Yeni kategori")
            .setView(input)
            .setPositiveButton("Ekle") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && store.categories.none { it.equals(name, ignoreCase = true) }) {
                    store.categories.add(name)
                    store.saveCategories()
                    refreshFilterSpinner()
                }
                showCategories()
            }
            .setNegativeButton("İptal") { _, _ -> showCategories() }
            .show()
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
