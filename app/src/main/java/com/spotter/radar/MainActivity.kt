package com.spotter.radar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

data class SpottedCar(
    val modelName: String,
    val estimatedPriceEur: Int,
    val source: String,
    val snippet: String,
    val url: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var tvLocation: TextView
    private lateinit var btnScan: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private var currentCity: String = "Praha"

    // Databáze orientačních cen v EUR pro řazení od nejdražších
    private val luxuryCarsDb = mapOf(
        "bugatti" to 3200000,
        "koenigsegg" to 3000000,
        "pagani" to 2800000,
        "laferrari" to 3500000,
        "mclaren p1" to 1800000,
        "porsche 918" to 1600000,
        "ferrari sf90" to 550000,
        "ferrari 812" to 420000,
        "ferrari 296" to 340000,
        "ferrari f8" to 320000,
        "lamborghini revuelto" to 600000,
        "lamborghini aventador" to 480000,
        "lamborghini huracan" to 260000,
        "lamborghini urus" to 250000,
        "porsche gt3 rs" to 380000,
        "porsche gt3" to 240000,
        "porsche 911 turbo" to 230000,
        "mclaren 765lt" to 450000,
        "mclaren 720s" to 310000,
        "rolls-royce" to 450000,
        "bentley" to 260000,
        "aston martin" to 240000,
        "mercedes amg gt" to 190000,
        "g63" to 210000,
        "audi r8" to 180000,
        "nissan gt-r" to 130000
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLocation = findViewById(R.id.tvLocation)
        btnScan = findViewById(R.id.btnScan)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        requestLocation()

        btnScan.setOnClickListener {
            scanNearbySpots(currentCity)
        }
    }

    private fun requestLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }
        getLastLocation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getLastLocation()
        } else {
            tvLocation.text = "Lokalita: Výchozí ($currentCity)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastLocation() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                try {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        currentCity = addresses[0].locality ?: addresses[0].subAdminArea ?: "Praha"
                        tvLocation.text = "Lokalita: $currentCity (GPS aktivní)"
                        scanNearbySpots(currentCity)
                    }
                } catch (e: Exception) {
                    tvLocation.text = "Lokalita: $currentCity"
                }
            }
        }
    }

    private fun scanNearbySpots(city: String) {
        progressBar.visibility = View.VISIBLE
        btnScan.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val foundCars = mutableListOf<SpottedCar>()

            // Dotazy na X (Twitter) příspěvky a Car Spotting portály v daném městě
            val queries = listOf(
                "site:x.com \"$city\" (Ferrari OR Lamborghini OR Porsche OR Bugatti OR McLaren OR spotted)",
                "site:autogespot.com \"$city\"",
                "site:exoticspotter.com \"$city\"",
                "\"$city\" car spotting (Ferrari OR Lamborghini OR Porsche GT3 OR McLaren)"
            )

            for (q in queries) {
                try {
                    val encoded = URLEncoder.encode(q, "UTF-8")
                    val doc = Jsoup.connect("https://html.duckduckgo.com/html/?q=$encoded")
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .timeout(8000)
                        .get()

                    val results = doc.select(".result")
                    for (res in results) {
                        val title = res.select(".result__title").text()
                        val snippet = res.select(".result__snippet").text()
                        val rawUrl = res.select(".result__a").attr("href")
                        val fullText = "$title $snippet".lowercase()

                        for ((carKey, price) in luxuryCarsDb) {
                            if (fullText.contains(carKey)) {
                                val sourceTag = when {
                                    rawUrl.contains("x.com") || rawUrl.contains("twitter.com") -> "X (Twitter) Post"
                                    rawUrl.contains("autogespot") -> "Autogespot"
                                    rawUrl.contains("exoticspotter") -> "Exoticspotter"
                                    else -> "Car Spotting Web"
                                }
                                foundCars.add(
                                    SpottedCar(
                                        modelName = carKey.uppercase(),
                                        estimatedPriceEur = price,
                                        source = sourceTag,
                                        snippet = title.ifEmpty { snippet },
                                        url = if (rawUrl.startsWith("http")) rawUrl else "https://duckduckgo.com$rawUrl"
                                    )
                                )
                                break
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

            // Seřazení podle odhadované ceny od nejdražšího a odstranění duplicit
            val sortedUnique = foundCars
                .distinctBy { it.url }
                .sortedByDescending { it.estimatedPriceEur }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                btnScan.isEnabled = true
                recyclerView.adapter = CarAdapter(sortedUnique) { car ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(car.url)))
                }
                if (sortedUnique.isEmpty()) {
                    Toast.makeText(this@MainActivity, "V okolí ($city) nebyly nalezeny žádné nedávné spoty.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

class CarAdapter(
    private val items: List<SpottedCar>,
    private val onClick: (SpottedCar) -> Unit
) : RecyclerView.Adapter<CarAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvModel: TextView = view.findViewById(android.R.id.text1)
        val tvDetails: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        view.setBackgroundColor(0xFF161A23.toInt())
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val formattedPrice = "%,d €".format(item.estimatedPriceEur).replace(',', ' ')
        holder.tvModel.text = "#${position + 1}  ${item.modelName}  •  ~$formattedPrice"
        holder.tvModel.setTextColor(0xFF00E5FF.toInt())
        holder.tvModel.textSize = 16f

        holder.tvDetails.text = "[${item.source}] ${item.snippet}"
        holder.tvDetails.setTextColor(0xFFD0D6E2.toInt())
        holder.tvDetails.setPadding(0, 4, 0, 16)

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
