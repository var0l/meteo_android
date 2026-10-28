package org.kawk.meteopolska

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val previewWidgetId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val image = findViewById<ImageView>(R.id.preview_image)
        val status = findViewById<TextView>(R.id.preview_status)
        val addBtn = findViewById<Button>(R.id.btn_add_widget)
        val refreshBtn = findViewById<Button>(R.id.btn_refresh)
        val citySpinner = findViewById<Spinner>(R.id.city_spinner)

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, Cities.names()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        citySpinner.adapter = adapter
        citySpinner.setSelection(Cities.ALL.indexOf(Cities.DEFAULT))

        render(image, status)

        addBtn.setOnClickListener {
            val mgr = AppWidgetManager.getInstance(this)
            val provider = ComponentName(this, MeteoWidgetProvider::class.java)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(provider, null, null)
            } else {
                Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
            }
        }

        refreshBtn.setOnClickListener {
            val cityName = citySpinner.selectedItem as String
            val city = Cities.byName(cityName) ?: Cities.DEFAULT
            ImageStore.saveCity(this, previewWidgetId, city)
            Scheduler.refreshNow(this)
            Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        render(
            findViewById(R.id.preview_image),
            findViewById(R.id.preview_status),
        )
        MeteoWidgetProvider.refreshAll(this)
    }

    private fun render(image: ImageView, status: TextView) {
        ImageStore.loadOrSample(this, previewWidgetId)?.let { image.setImageBitmap(it) }
        status.text = if (ImageStore.hasImage(this, previewWidgetId)) {
            getString(R.string.updated_at, "")
        } else {
            getString(R.string.sample_image)
        }
    }
}
