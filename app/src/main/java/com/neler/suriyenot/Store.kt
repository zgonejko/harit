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

/** Kategorileri ve yerleri telefonda (SharedPreferences içinde JSON olarak) saklar. */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("suriye_notlari", Context.MODE_PRIVATE)

    val categories: MutableList<String> = loadCategories()
    val places: MutableList<Place> = loadPlaces()

    private fun loadCategories(): MutableList<String> {
        val list = mutableListOf<String>()
        val arr = JSONArray(prefs.getString("categories", "[]"))
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
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

    fun saveCategories() {
        val arr = JSONArray()
        categories.forEach { arr.put(it) }
        prefs.edit().putString("categories", arr.toString()).apply()
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
