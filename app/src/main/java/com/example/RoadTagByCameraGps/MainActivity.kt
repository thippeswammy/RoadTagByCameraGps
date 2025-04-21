package com.example.RoadTagByCameraGps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var startButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var isRecording = false
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var imuFileWriter: FileWriter? = null
    private var gpsFileWriter: FileWriter? = null
    private var sessionFolder: File? = null
    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    companion object {
        private const val CAMERA_REQUEST_CODE = 1
        private const val AUDIO_REQUEST_CODE = 2
        private const val LOCATION_REQUEST_CODE = 3
        private const val STORAGE_REQUEST_CODE = 4
        private const val TAG = "MainActivity"
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                saveGpsData(location)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")
        try {
            setContentView(R.layout.activity_main)
            Log.d(TAG, "setContentView completed")
            previewView = findViewById(R.id.previewView)
            startButton = findViewById(R.id.startButton)
            Log.d(TAG, "Views initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            Toast.makeText(this, "Error initializing UI: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        startButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        val requestCodes = mutableListOf<Int>()

        // Camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
            requestCodes.add(CAMERA_REQUEST_CODE)
        }

        // Audio permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
            requestCodes.add(AUDIO_REQUEST_CODE)
        }

        // Location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            requestCodes.add(LOCATION_REQUEST_CODE)
        }

        // Storage permission for Android 9 (API 28) and below
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            requestCodes.add(STORAGE_REQUEST_CODE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                requestCodes.first() // Use the first request code for simplicity
            )
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val allPermissionsGranted = permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            startCamera()
        } else {
            val permanentlyDenied = permissions.any { permission ->
                !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
            }
            if (permanentlyDenied) {
                Toast.makeText(
                    this,
                    "Some permissions are permanently denied. Please enable them in Settings.",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Please grant all required permissions.", Toast.LENGTH_LONG).show()
                checkPermissions()
            }
        }
    }

    private fun startCamera() {
        Log.d(TAG, "startCamera called")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                Log.d(TAG, "Camera bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed: ${e.message}", e)
                Toast.makeText(this, "Camera binding failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        isRecording = true
        startButton.text = "Stop"
        createSessionFolder()
        startVideoRecording()
        startSensorRecording()
        startGpsRecording()
    }

    private fun stopRecording() {
        isRecording = false
        startButton.text = "Start"
        stopVideoRecording()
        stopSensorRecording()
        stopGpsRecording()
        closeFileWriters()
        Toast.makeText(
            this,
            "Files saved in Documents/App_names/Session_${timestampFormat.format(Date())}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun createSessionFolder() {
        val timestamp = timestampFormat.format(Date())
        // Save to public storage: /storage/emulated/0/Documents/App_names/Session_<timestamp>
        sessionFolder = File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "App_names"),
            "Session_$timestamp"
        )
        try {
            sessionFolder?.mkdirs()
            Log.d(TAG, "Session folder created: ${sessionFolder?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session folder: ${e.message}", e)
            Toast.makeText(this, "Error creating session folder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVideoRecording() {
        val timestamp = timestampFormat.format(Date())
        val videoFile = File(sessionFolder, "video_$timestamp.mp4")
        Log.d(TAG, "Video file path: ${videoFile.absolutePath}")
        val outputOptions = androidx.camera.video.FileOutputOptions.Builder(videoFile).build()

        videoCapture?.output?.prepareRecording(this, outputOptions)?.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        Log.e(TAG, "Video recording error: ${event.cause?.message}")
                        Toast.makeText(this, "Video error: ${event.cause?.message}", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d(TAG, "Video saved: ${videoFile.absolutePath}")
                        Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show()
                    }
                    activeRecording = null
                }
            }
        }?.let { recording ->
            activeRecording = recording
        }
    }

    private fun stopVideoRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun startSensorRecording() {
        try {
            val imuFile = File(sessionFolder, "imu_data.csv")
            Log.d(TAG, "IMU file path: ${imuFile.absolutePath}")
            imuFileWriter = FileWriter(imuFile).apply {
                write("timestamp,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z\n")
                flush()
            }
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d(TAG, "Sensor recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sensor recording: ${e.message}", e)
            Toast.makeText(this, "Error starting sensor recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSensorRecording() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Sensor recording stopped")
    }

    private fun startGpsRecording() {
        try {
            val gpsFile = File(sessionFolder, "gps_data.csv")
            Log.d(TAG, "GPS file path: ${gpsFile.absolutePath}")
            gpsFileWriter = FileWriter(gpsFile).apply {
                write("timestamp,latitude,longitude,altitude\n")
                flush()
            }
            val locationRequest = LocationRequest.create().apply {
                interval = 1000
                fastestInterval = 500
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                Log.d(TAG, "GPS recording started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS recording: ${e.message}", e)
            Toast.makeText(this, "Error starting GPS recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopGpsRecording() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "GPS recording stopped")
    }

    private fun closeFileWriters() {
        try {
            imuFileWriter?.close()
            gpsFileWriter?.close()
            imuFileWriter = null
            gpsFileWriter = null
            Log.d(TAG, "File writers closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing file writers: ${e.message}", e)
            Toast.makeText(this, "Error closing files", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            try {
                val timestamp = System.currentTimeMillis()
                val values = it.values
                val data = when (it.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> "$timestamp,${values[0]},${values[1]},${values[2]},,,"
                    Sensor.TYPE_GYROSCOPE -> "$timestamp,,,,${values[0]},${values[1]},${values[2]}"
                    else -> return
                }
                imuFileWriter?.write("$data\n")
                imuFileWriter?.flush()
                Log.d(TAG, "Sensor data written: $data")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing sensor data: ${e.message}", e)
            }
        }
    }

    private fun saveGpsData(location: Location) {
        try {
            if (location.latitude == 0.0 && location.longitude == 0.0) {
                Log.w(TAG, "GPS location invalid: ${location}")
                return
            }

            val timestamp = System.currentTimeMillis()
            val data = "$timestamp,${location.latitude},${location.longitude},${location.altitude}"
            gpsFileWriter?.write("$data\n")
            gpsFileWriter?.flush()
            Log.d(TAG, "GPS data written: $data")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing GPS data: ${e.message}", e)
        }
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
        cameraExecutor.shutdown()
        Log.d(TAG, "onDestroy called")
    }
}