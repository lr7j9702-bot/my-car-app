package com.carlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.carlauncher.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration as OsmConfiguration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class DrawerMode { CLOSED, BROWSE, PICK_FIRST, PICK_SECOND }

    private lateinit var b: ActivityMainBinding
    private lateinit var pinned: PinnedStore
    private lateinit var settings: SharedPreferences
    private lateinit var locationManager: LocationManager
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var dockAdapter: AppAdapter
    private lateinit var gridAdapter: AppAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var allApps: List<AppInfo> = emptyList()
    private var mode = DrawerMode.CLOSED
    private var firstPick: AppInfo? = null

    private var useMph = false
    private var nightMap = false
    private var locationStarted = false
    private var lastFixMs = 0L
    private var lastSpeedMs = 0f

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid must be configured before the MapView is inflated.
        val osm = OsmConfiguration.getInstance()
        osm.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osm.userAgentValue = packageName
        osm.osmdroidBasePath = File(cacheDir, "osmdroid")
        osm.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        pinned = PinnedStore(this)
        settings = getSharedPreferences("settings", MODE_PRIVATE)
        useMph = settings.getBoolean("mph", false)
        nightMap = settings.getBoolean("night_map", false)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setupMap()
        setupLists()
        setupButtons()
        updateUnitLabel()

        // As a home screen, Back should never leave the launcher; it only closes the app grid.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode != DrawerMode.CLOSED) closeDrawer()
            }
        })

        ensureLocationPermission()
    }

    // Start/stop on onStart/onStop (not onResume/onPause) so the map pane keeps updating
    // while another app has focus in split screen.
    override fun onStart() {
        super.onStart()
        b.map.onResume()
        startLocation()
        handler.post(staleCheck)
        loadApps()
    }

    override fun onStop() {
        handler.removeCallbacks(staleCheck)
        stopLocation()
        b.map.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        b.map.onDetach()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while the app grid is open returns to the map.
        if (mode != DrawerMode.CLOSED) closeDrawer()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        (b.gridRecycler.layoutManager as? GridLayoutManager)?.spanCount = spanCount()
    }

    // ---------------------------------------------------------------- setup

    private fun setupMap() {
        b.map.setTileSource(TileSourceFactory.MAPNIK)
        b.map.setMultiTouchControls(true)
        b.map.isTilesScaledToDpi = true
        b.map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
        b.map.controller.setZoom(3.0)
        b.map.keepScreenOn = true

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), b.map)
        locationOverlay.enableFollowLocation()
        b.map.overlays.add(locationOverlay)
        applyNightMap()
    }

    private fun setupLists() {
        dockAdapter = AppAdapter(
            dock = true,
            onClick = { launch(it.packageName) },
            onLongClick = { togglePin(it) }
        )
        b.dockRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.dockRecycler.adapter = dockAdapter

        gridAdapter = AppAdapter(
            dock = false,
            onClick = { onGridClick(it) },
            onLongClick = { onGridLongClick(it) }
        )
        b.gridRecycler.layoutManager = GridLayoutManager(this, spanCount())
        b.gridRecycler.adapter = gridAdapter
    }

    private fun setupButtons() {
        b.btnApps.setOnClickListener { openDrawer(DrawerMode.BROWSE) }
        b.btnSplit.setOnClickListener {
            firstPick = null
            openDrawer(DrawerMode.PICK_FIRST)
        }
        b.btnCloseDrawer.setOnClickListener { closeDrawer() }
        b.btnRecenter.setOnClickListener { recenter() }
        b.btnNight.setOnClickListener {
            nightMap = !nightMap
            settings.edit().putBoolean("night_map", nightMap).apply()
            applyNightMap()
        }
        // Tap the speed card to switch between km/h and mph.
        b.speedCard.setOnClickListener {
            useMph = !useMph
            settings.edit().putBoolean("mph", useMph).apply()
            updateUnitLabel()
            if (lastFixMs != 0L) showSpeed(lastSpeedMs)
        }
    }

    private fun spanCount(): Int = maxOf(3, resources.configuration.screenWidthDp / 110)

    // ---------------------------------------------------------------- map and speed

    private fun applyNightMap() {
        b.map.overlayManager.tilesOverlay.setColorFilter(
            if (nightMap) TilesOverlay.INVERT_COLORS else null
        )
        b.map.invalidate()
    }

    private fun recenter() {
        locationOverlay.enableFollowLocation()
        val here = locationOverlay.myLocation
        if (here != null) b.map.controller.animateTo(here)
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFixMs = SystemClock.elapsedRealtime()
            lastSpeedMs = if (location.hasSpeed()) location.speed else 0f
            showSpeed(lastSpeedMs)
            b.gpsStatus.text = getString(R.string.gps_ok, location.accuracy.toInt())
        }

        // Must be implemented: on Android 10 and older these are abstract at runtime.
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {
            b.gpsStatus.setText(R.string.gps_waiting)
        }

        override fun onProviderDisabled(provider: String) {
            lastFixMs = 0L
            b.speedValue.text = "--"
            b.gpsStatus.setText(R.string.gps_off)
        }
    }

    // If GPS goes quiet (tunnel, parking garage), do not leave an old speed on screen.
    private val staleCheck = object : Runnable {
        override fun run() {
            if (lastFixMs != 0L && SystemClock.elapsedRealtime() - lastFixMs > 5000L) {
                b.speedValue.text = "--"
                b.gpsStatus.setText(R.string.gps_lost)
            }
            handler.postDelayed(this, 2000L)
        }
    }

    private fun showSpeed(metersPerSecond: Float) {
        val value = if (useMph) metersPerSecond * 2.23694f else metersPerSecond * 3.6f
        val shown = if (value < 2f) 0 else Math.round(value)  // ignore GPS jitter when parked
        b.speedValue.text = shown.toString()
    }

    private fun updateUnitLabel() {
        b.speedUnit.setText(if (useMph) R.string.unit_mph else R.string.unit_kmh)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQ_LOCATION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (hasLocationPermission()) {
            startLocation()
        } else {
            Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationStarted || !hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener
            )
            locationStarted = true
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                b.gpsStatus.setText(R.string.gps_off)
            }
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) {
                b.map.controller.setZoom(16.0)
                b.map.controller.setCenter(GeoPoint(last.latitude, last.longitude))
            }
            locationOverlay.enableMyLocation()
        } catch (e: SecurityException) {
            b.gpsStatus.setText(R.string.location_denied)
        } catch (e: IllegalArgumentException) {
            b.gpsStatus.setText(R.string.gps_off)
        }
    }

    private fun stopLocation() {
        if (!locationStarted) return
        locationManager.removeUpdates(locationListener)
        locationOverlay.disableMyLocation()
        locationStarted = false
    }

    // ---------------------------------------------------------------- apps, dock, grid

    private fun loadApps() {
        Thread {
            val apps = AppRepository.loadLaunchable(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps = apps
                refreshDock()
                if (mode != DrawerMode.CLOSED) gridAdapter.submit(gridItems())
            }
        }.start()
    }

    private fun refreshDock() {
        val byPackage = allApps.associateBy { it.packageName }
        val items = pinned.get().mapNotNull { byPackage[it] }
        dockAdapter.submit(items)
        b.dockEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun togglePin(app: AppInfo) {
        if (app.packageName == packageName) return
        val nowPinned = pinned.toggle(app.packageName)
        refreshDock()
        Toast.makeText(
            this,
            if (nowPinned) R.string.pinned else R.string.unpinned,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun launch(pkg: String) {
        if (pkg == packageName) return
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun mapEntry(): AppInfo {
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_launcher)!!
        return AppInfo(getString(R.string.map_option), packageName, icon)
    }

    private fun gridItems(): List<AppInfo> = when (mode) {
        DrawerMode.PICK_FIRST -> listOf(mapEntry()) + allApps
        DrawerMode.PICK_SECOND -> allApps.filter { it.packageName != firstPick?.packageName }
        else -> allApps
    }

    private fun openDrawer(newMode: DrawerMode) {
        mode = newMode
        updateDrawerContent()
        b.drawer.visibility = View.VISIBLE
    }

    private fun closeDrawer() {
        mode = DrawerMode.CLOSED
        firstPick = null
        b.drawer.visibility = View.GONE
    }

    private fun updateDrawerContent() {
        when (mode) {
            DrawerMode.BROWSE -> {
                b.drawerTitle.setText(R.string.all_apps)
                b.drawerHint.setText(R.string.hint_browse)
            }
            DrawerMode.PICK_FIRST -> {
                b.drawerTitle.setText(R.string.pick_first)
                b.drawerHint.setText(R.string.hint_split_first)
            }
            DrawerMode.PICK_SECOND -> {
                b.drawerTitle.setText(R.string.pick_second)
                b.drawerHint.setText(R.string.hint_split_second)
            }
            DrawerMode.CLOSED -> Unit
        }
        gridAdapter.submit(gridItems())
        b.gridRecycler.scrollToPosition(0)
    }

    private fun onGridClick(app: AppInfo) {
        when (mode) {
            DrawerMode.PICK_FIRST -> {
                firstPick = app
                mode = DrawerMode.PICK_SECOND
                updateDrawerContent()
            }
            DrawerMode.PICK_SECOND -> {
                val first = firstPick
                closeDrawer()
                if (first != null) startSplit(first, app)
            }
            else -> {
                closeDrawer()
                launch(app.packageName)
            }
        }
    }

    private fun onGridLongClick(app: AppInfo) {
        if (mode == DrawerMode.BROWSE) togglePin(app)
    }

    // ---------------------------------------------------------------- split screen

    private fun startSplit(first: AppInfo, second: AppInfo) {
        val service = SplitScreenService.instance
        if (service == null) {
            showEnableSplitDialog()
            return
        }
        if (!service.startSplit(first.packageName, second.packageName)) {
            Toast.makeText(this, R.string.split_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    private fun showEnableSplitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.split_enable_title)
            .setMessage(R.string.split_enable_msg)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.settings_not_found, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val REQ_LOCATION = 1
    }
}
