package com.neler.suriyenot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Place(
    val id: Long,
    val lat: Double,
    val lon: Double,
    var category: String,
    var note: String,
    var visited: Boolean
)

/** Kategorileri, kategori renklerini ve yerleri telefonda (SharedPreferences içinde JSON olarak) saklar. */
class Store(context: Context) {

    companion object {
        const val PALETTE_SIZE = 8
    }

    private val prefs = context.getSharedPreferences("suriye_notlari", Context.MODE_PRIVATE)

    val categories: MutableList<String> = loadCategories()

    /** Kategori adı -> palet sırası. Kategori silinse de diğerlerinin rengi değişmesin diye saklanır. */
    val colorIdx: MutableMap<String, Int> = loadColors()

    val places: MutableList<Place> = loadPlaces()

    init {
        var changed = false
        categories.forEachIndexed { i, c ->
            if (c !in colorIdx) {
                colorIdx[c] = i % PALETTE_SIZE
                changed = true
            }
        }
        if (changed) saveCategories()
    }

    private fun loadCategories(): MutableList<String> {
        val list = mutableListOf<String>()
        val arr = JSONArray(prefs.getString("categories", "[]"))
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    private fun loadColors(): MutableMap<String, Int> {
        val map = mutableMapOf<String, Int>()
        val obj = JSONObject(prefs.getString("cat_colors", "{}"))
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.getInt(k)
        }
        return map
    }

    private fun loadPlaces(): MutableList<Place> {
        val list = mutableListOf<Place>()
        val arr = JSONArray(prefs.getString("places", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Place(
                    id = o.getLong("id"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    category = o.getString("category"),
                    note = o.optString("note", ""),
                    visited = o.optBoolean("visited", false)
                )
            )
        }
        return list
    }

    /** Yeni kategori ekler. Aynı isimde (büyük/küçük harf fark etmez) varsa false döner. */
    fun addCategory(name: String): Boolean {
        if (categories.any { it.equals(name, ignoreCase = true) }) return false
        categories.add(name)
        val used = colorIdx.values.toSet()
        colorIdx[name] = (0 until PALETTE_SIZE).firstOrNull { it !in used }
            ?: (categories.size % PALETTE_SIZE)
        saveCategories()
        return true
    }

    fun removeCategory(name: String) {
        categories.remove(name)
        colorIdx.remove(name)
        saveCategories()
    }

    fun saveCategories() {
        val arr = JSONArray()
        categories.forEach { arr.put(it) }
        val colors = JSONObject()
        colorIdx.forEach { (k, v) -> colors.put(k, v) }
        prefs.edit()
            .putString("categories", arr.toString())
            .putString("cat_colors", colors.toString())
            .apply()
    }

    fun savePlaces() {
        val arr = JSONArray()
        places.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("lat", it.lat)
                    .put("lon", it.lon)
                    .put("category", it.category)
                    .put("note", it.note)
                    .put("visited", it.visited)
            )
        }
        prefs.edit().putString("places", arr.toString()).apply()
    }
}
