package com.example.landscape_view_recognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.sceneform.ArSceneView
import org.osmdroid.config.Configuration.getInstance
import org.osmdroid.library.BuildConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.osmdroid.library.R as osmR


class MainActivity : AppCompatActivity() {

    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private val CAMERA_PERMISSION_REQUEST_CODE = 2

    private lateinit var textureView: TextureView
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCaptureSessions: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var imageDimension: android.util.Size

    private lateinit var arSession: Session
    private lateinit var arView: ArSceneView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    private lateinit var sensorManager: SensorManager

    //    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gravitySensor: Sensor? = null

    private var lastAccelerometer = FloatArray(3)
    private var lastMagnetometer = FloatArray(3)
    private var isLastAccelerometerArrayCopied = false
    private var isLastMagnetometerArrayCopied = false
    private val orientationAngles = FloatArray(3)

    private lateinit var mapView: MapView
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var btnSwitchView: Button

    private var currentLocation: Location? = null
    private var horizontalFoV: Float = 0f
    private var verticalFoV: Float = 0f

    private var currentConePolygon: Polygon? = null


    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        @RequiresApi(Build.VERSION_CODES.P)
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
            val textViewHFov: TextView = findViewById(R.id.tvHFoV)
            val textViewVFov: TextView = findViewById(R.id.tvVFoV)

            textViewHFov.text = "hFoV: $horizontalFoV"
            textViewVFov.text = "vFoV: $verticalFoV"
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private val sensorEventListener = object : SensorEventListener {
        private var lastTimeChanged = System.currentTimeMillis()
        private val ALPHA = 0.05f // Smoothing factor, experiment with this value

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> {
                    lastAccelerometer = applyLowPassFilter(event.values, lastAccelerometer)
                    isLastAccelerometerArrayCopied = true
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    lastMagnetometer = applyLowPassFilter(event.values, lastMagnetometer)
                    isLastMagnetometerArrayCopied = true
                }
            }

            if (isLastAccelerometerArrayCopied && isLastMagnetometerArrayCopied) {
                val rotationMatrix = FloatArray(9)
                if (SensorManager.getRotationMatrix(
                        rotationMatrix,
                        null,
                        lastAccelerometer,
                        lastMagnetometer
                    )
                ) {
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    // Now orientationAngles contains the azimuth, pitch, and roll values.
                    val currentTime = System.currentTimeMillis()
                    if ((currentTime - lastTimeChanged) > 500) {
                        if (mapView.visibility == View.VISIBLE) {
                            val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                            if (currentLocation != null)
                                updateMapLocationAndOrientation(currentLocation!!, azimuth)
                        } else
                            updateOrientationDisplay()

                        lastTimeChanged = currentTime
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // Can be implemented as needed
        }

        private fun applyLowPassFilter(input: FloatArray, output: FloatArray?): FloatArray {
            if (output == null) return input

            for (i in input.indices) {
                output[i] = output[i] + ALPHA * (input[i] - output[i])
            }
            return output
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fetchPOIs()

        if (!ArCoreApk.getInstance().checkAvailability(this).isSupported) {
            println("ARCore is not supported on this device")
        }

        // Important to set user agent to prevent getting banned from the OSM servers
        getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map)
        mapView.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE) // Use the default tile source

        // Add location overlay to show current location
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        val bitmapNotMoving =
            BitmapFactory.decodeResource(resources, osmR.drawable.twotone_navigation_black_48)
        val bitmapMoving =
            BitmapFactory.decodeResource(resources, osmR.drawable.twotone_navigation_black_48)
        myLocationOverlay.setDirectionArrow(bitmapNotMoving, bitmapMoving)
        mapView.overlays.add(myLocationOverlay)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)

        mapView.setMultiTouchControls(true)

        val mapController = mapView.controller
        mapController.setZoom(15.0)  // Set a reasonable default zoom, e.g., city-level zoom

        textureView = findViewById(R.id.textureView)
        textureView.surfaceTextureListener = textureListener

        btnSwitchView = findViewById(R.id.btnSwitchView)

        setupSwitchButton()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationRequest()
        createLocationCallback()
        startLocationUpdates()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
//        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    }

    fun fetchPOIs() {
        val lat = 32.096310
        val lon = 34.776800
        val urlString = "https://overpass-api.de/api/interpreter"
        val query = "[out:json];node[amenity=cafe](around:500, $lat, $lon);out;"

        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.write(query.toByteArray())

            val inputStream = BufferedReader(InputStreamReader(conn.inputStream))
            val response = StringBuffer()
            var inputLine = inputStream.readLine()
            while (inputLine != null) {
                response.append(inputLine)
                inputLine = inputStream.readLine()
            }
            inputStream.close()
            conn.disconnect()

            // Process the response here
            parsePOIs(response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            // Handle exceptions
        }
    }

    fun parsePOIs(response: String) {
        println(response)
        // Parse the JSON response to extract POIs
    }

    private fun setupSwitchButton() {
        var isMapViewVisible = false
        mapView.visibility = View.GONE
        textureView.visibility = View.VISIBLE
        btnSwitchView.text = "Show Map"

        btnSwitchView.setOnClickListener {
            if (isMapViewVisible) {
                mapView.visibility = View.GONE
                textureView.visibility = View.VISIBLE
                btnSwitchView.text = "Show Map"
            } else {
                mapView.visibility = View.VISIBLE
                textureView.visibility = View.GONE
                btnSwitchView.text = "Show Camera"
            }
            isMapViewVisible = !isMapViewVisible
        }
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10_000L
        ).build()
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    currentLocation = location
                    if (mapView.visibility == View.VISIBLE) {
                        val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                        updateMapLocationAndOrientation(location, azimuth)
                        val geoPoint = GeoPoint(location.latitude, location.longitude)
                        mapView.controller.setCenter(geoPoint)
                        mapView.controller.setZoom(18.0)  // Zoom in closer, e.g., street-level zoom
                    } else
                        updateCoordinates(location.latitude, location.longitude, location.altitude)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissions()
            return
        }
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(imageDimension.width, imageDimension.height)
            val surface = Surface(texture)
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)

            // List of surfaces to be used in capture session
            val surfaces = listOf(surface)

            // Convert the list of surfaces to a list of OutputConfigurations
            val outputConfigs = surfaces.map { OutputConfiguration(it) }

            // Create a camera capture session for the given configuration
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                // Use the main executor associated with the main thread
                this.mainExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        cameraCaptureSessions = cameraCaptureSession
                        updatePreview()
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        Toast.makeText(
                            this@MainActivity,
                            "Configuration failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            // Create the capture session
            cameraDevice.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission()
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0] // back camera
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            imageDimension = map.getOutputSizes(SurfaceTexture::class.java)[0]

            val (hFoV, vFoV) = calculateFieldOfView(cameraId) // Replace "0" with the appropriate camera ID.
            horizontalFoV = hFoV
            verticalFoV = vFoV

            // Add code to check camera hardware level and capabilities if needed
            manager.openCamera(cameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updateMapLocationAndOrientation(location: Location, azimuth: Float) {
        val latLng = GeoPoint(location.latitude, location.longitude)
        mapView.controller.setCenter(latLng)

        val conePoints = calculateConePoints(location, azimuth, horizontalFoV, 1000f)
        drawConeOnMap(mapView, conePoints)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // Location permission granted
                    startLocationUpdates()
                } else {
                    // Location permission denied
                    Toast.makeText(
                        this,
                        "Location permission is needed to display coordinates",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            CAMERA_PERMISSION_REQUEST_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // Camera permission granted
                    openCamera()
                } else {
                    // Camera permission denied
                    Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
//        accelerometer?.also { accel ->
//            sensorManager.registerListener(sensorEventListener, accel, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
//        }
        magnetometer?.also { mag ->
            sensorManager.registerListener(
                sensorEventListener,
                mag,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
        gravitySensor?.also { mag ->
            sensorManager.registerListener(
                sensorEventListener,
                mag,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        mapView.onResume() // needed for compass, my location overlays, v6.0.0 and up
        myLocationOverlay.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        if (::cameraDevice.isInitialized) {
            cameraDevice.close()
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(sensorEventListener)

        mapView.onPause()  //needed for compass, my location overlays, v6.0.0 and up
        myLocationOverlay.disableMyLocation()
    }

    private fun updateCoordinates(lat: Double, lon: Double, alt: Double) {
        val textViewLat: TextView = findViewById(R.id.tvCoordLat)
        val textViewLon: TextView = findViewById(R.id.tvCoordLong)
        val textViewAlt: TextView = findViewById(R.id.tvCoordAlt)

        textViewLat.text = "Lat: $lat"
        textViewLon.text = "Lon: $lon"
        textViewAlt.text = "Altitude: $alt"
    }

    private fun updateOrientationDisplay() {
        val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
        var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

        if (currentLocation != null) {
            val geoField = GeomagneticField(
                currentLocation!!.latitude.toFloat(),
                currentLocation!!.longitude.toFloat(),
                currentLocation!!.altitude.toFloat(),
                System.currentTimeMillis()
            )
            azimuth += geoField.declination // converts magnetic north to true north
        }

        val textViewAzimuth: TextView = findViewById(R.id.tvAzimuth)
        val textViewPitch: TextView = findViewById(R.id.tvPitch)
        val textViewRoll: TextView = findViewById(R.id.tvRoll)

        textViewAzimuth.text = "Azimuth: $azimuth"
        textViewPitch.text = "Pitch: $pitch"
        textViewRoll.text = "Roll: $roll"
    }

    private fun calculateFieldOfView(cameraId: String): Pair<Float, Float> {

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)

        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengths =
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

        if (sensorSize != null && focalLengths != null && focalLengths.isNotEmpty()) {
            val focalLength =
                focalLengths[0] // Typically, the first one is the primary focal length.

            // Calculate the field of view
            val horizontalView = Math.toDegrees(
                2 * Math.atan2(
                    sensorSize.width.toDouble(),
                    (2 * focalLength).toDouble()
                )
            ).toFloat()
            val verticalView = Math.toDegrees(
                2 * Math.atan2(
                    sensorSize.height.toDouble(),
                    (2 * focalLength).toDouble()
                )
            ).toFloat()

            return Pair(horizontalView, verticalView)
        }

        return Pair(0f, 0f)
    }

    fun drawConeOnMap(mapView: MapView, conePoints: List<GeoPoint>) {
        // Remove the existing polygon if it's there
        currentConePolygon?.let {
            mapView.overlays.remove(it)
//                mapView.invalidate()
        }

        // Create a new polygon
        val polygon = Polygon().apply {
            points = conePoints
            fillColor = Color.argb(50, 255, 0, 0)
            strokeColor = Color.argb(100, 255, 0, 0)
            strokeWidth = 2.0f
        }

        // Add the new polygon to the map
        mapView.overlays.add(polygon)
        mapView.invalidate()

        // Store the new polygon as the current one
        currentConePolygon = polygon
    }
}

fun calculateConePoints(location: Location, azimuth: Float, fov: Float, distance: Float): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    val halfFov = fov / 2.0

    // Calculate left and right angles of the FoV
    val leftAngle = Math.toRadians((azimuth - halfFov).toDouble())
    val rightAngle = Math.toRadians((azimuth + halfFov).toDouble())

    // Add the current location as the starting point
    points.add(GeoPoint(location.latitude, location.longitude))

    // Calculate two points at 'distance' away from the current location at the left and right angles
    val earthRadius = 6371000.0 // Earth's radius in meters
    val lat = Math.toRadians(location.latitude)
    val lon = Math.toRadians(location.longitude)

    // Left point
    var latPoint = Math.asin(Math.sin(lat) * Math.cos(distance / earthRadius) +
            Math.cos(lat) * Math.sin(distance / earthRadius) * Math.cos(leftAngle))
    var lonPoint = lon + Math.atan2(Math.sin(leftAngle) * Math.sin(distance / earthRadius) * Math.cos(lat),
        Math.cos(distance / earthRadius) - Math.sin(lat) * Math.sin(latPoint))
    points.add(GeoPoint(Math.toDegrees(latPoint), Math.toDegrees(lonPoint)))

    // Right point
    latPoint = Math.asin(Math.sin(lat) * Math.cos(distance / earthRadius) +
            Math.cos(lat) * Math.sin(distance / earthRadius) * Math.cos(rightAngle))
    lonPoint = lon + Math.atan2(Math.sin(rightAngle) * Math.sin(distance / earthRadius) * Math.cos(lat),
        Math.cos(distance / earthRadius) - Math.sin(lat) * Math.sin(latPoint))
    points.add(GeoPoint(Math.toDegrees(latPoint), Math.toDegrees(lonPoint)))

    return points
}

