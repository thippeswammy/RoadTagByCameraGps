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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    private var magnetometer: Sensor? = null
    private var imuFileWriter: FileWriter? = null
    private var gpsFileWriter: FileWriter? = null
    private var sessionFolder: File? = null
    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var lastAccData: FloatArray? = null
    private var lastGyroData: FloatArray? = null
    private var lastMagData: FloatArray? = null
    private var lastImuWriteTime: Long = 0
    private var sensorStartTime: Long = 0
    private var gpsStartTime: Long = 0
    private var accEventCount: Int = 0
    private var gyroEventCount: Int = 0
    private var magEventCount: Int = 0
    private var gpsEventCount: Int = 0
    private var hasShownImuWarning: Boolean = false
    private var hasShownGpsWarning: Boolean = false
    private var firstGpsLocation: Location? = null
    private var lastGpsLocation: Location? = null
    private var lastGpsTimestamp: Long = 0
    private var lastGpsLocalX: Double = 0.0
    private var lastGpsLocalY: Double = 0.0
    private var lastGpsLocalZ: Double = 0.0

    // Sensor fusion variables
    private var orientationQuaternion = floatArrayOf(1f, 0f, 0f, 0f) // [w, x, y, z]
    private var lastImuTimestamp: Long = 0
    private var position = doubleArrayOf(0.0, 0.0, 0.0) // [x, y, z]
    private var velocity = doubleArrayOf(0.0, 0.0, 0.0) // [vx, vy, vz]
    private var lastWorldAcc = floatArrayOf(0f, 0f, 0f) // World-frame acceleration
    private val alpha = 0.98f // Complementary filter weight for gyroscope
    private val gpsAlpha = 0.1 // Complementary filter weight for GPS position

    companion object {
        private const val CAMERA_REQUEST_CODE = 1
        private const val AUDIO_REQUEST_CODE = 2
        private const val LOCATION_REQUEST_CODE = 3
        private const val STORAGE_REQUEST_CODE = 4
        private const val TAG = "MainActivity"
        private const val IMU_WRITE_INTERVAL_MS = 10 // Write every 10ms
        private const val IMU_TIMEOUT_MS = 5000 // Warn after 5s
        private const val GPS_TIMEOUT_MS = 5000 // Warn after 5s
        private const val EARTH_RADIUS_M = 6371000.0 // Earth's radius in meters
        private const val GRAVITY = 9.81f // m/s^2
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
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (accelerometer == null) {
            Log.e(TAG, "Accelerometer sensor not available")
            Toast.makeText(this, "Accelerometer not available", Toast.LENGTH_LONG).show()
        }
        if (gyroscope == null) {
            Log.e(TAG, "Gyroscope sensor not available")
            Toast.makeText(this, "Gyroscope not available", Toast.LENGTH_LONG).show()
        }
        if (magnetometer == null) {
            Log.e(TAG, "Magnetometer sensor not available")
            Toast.makeText(this, "Magnetometer not available", Toast.LENGTH_LONG).show()
        }

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
            requestCodes.add(CAMERA_REQUEST_CODE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
            requestCodes.add(AUDIO_REQUEST_CODE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            requestCodes.add(LOCATION_REQUEST_CODE)
        }
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            requestCodes.add(STORAGE_REQUEST_CODE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                requestCodes.first()
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
        // Reset fusion state
        orientationQuaternion = floatArrayOf(1f, 0f, 0f, 0f)
        position = doubleArrayOf(0.0, 0.0, 0.0)
        velocity = doubleArrayOf(0.0, 0.0, 0.0)
        lastImuTimestamp = 0
    }

    private fun stopRecording() {
        isRecording = false
        startButton.text = "Start"
        stopVideoRecording()
        stopSensorRecording()
        stopGpsRecording()
        closeFileWriters()
        Log.d(TAG, "Sensor events: Acc=$accEventCount, Gyro=$gyroEventCount, Mag=$magEventCount, GPS=$gpsEventCount")
        Toast.makeText(
            this,
            "Files saved in Documents/App_names/Session_${timestampFormat.format(Date())}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun createSessionFolder() {
        val timestamp = timestampFormat.format(Date())
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
                write("timestamp,position_x,position_y,position_z,acc_x,acc_y,acc_z,world_acc_x,world_acc_y,world_acc_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z\n")
                flush()
            }
            sensorStartTime = System.currentTimeMillis()
            accEventCount = 0
            gyroEventCount = 0
            magEventCount = 0
            hasShownImuWarning = false
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d(TAG, "Accelerometer listener registered")
            }
            if (gyroscope != null) {
                sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d(TAG, "Gyroscope listener registered")
            }
            if (magnetometer != null) {
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d(TAG, "Magnetometer listener registered")
            }
            Log.d(TAG, "Sensor recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sensor recording: ${e.message}", e)
            Toast.makeText(this, "Error starting sensor recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSensorRecording() {
        sensorManager.unregisterListener(this)
        lastAccData = null
        lastGyroData = null
        lastMagData = null
        lastImuWriteTime = 0
        Log.d(TAG, "Sensor recording stopped")
    }

    private fun startGpsRecording() {
        try {
            val gpsFile = File(sessionFolder, "gps_data.csv")
            Log.d(TAG, "GPS file path: ${gpsFile.absolutePath}")
            gpsFileWriter = FileWriter(gpsFile).apply {
                write("timestamp,latitude,longitude,altitude,speed,bearing,accuracy,local_x,local_y,local_z\n")
                flush()
            }
            gpsStartTime = System.currentTimeMillis()
            gpsEventCount = 0
            hasShownGpsWarning = false
            firstGpsLocation = null
            lastGpsLocation = null
            lastGpsTimestamp = 0
            val locationRequest = LocationRequest.create().apply {
                interval = 250 // 250ms for smoother paths
                fastestInterval = 100
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
                when (it.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccData = it.values.clone()
                        accEventCount++
                        Log.d(TAG, "Accelerometer event: ${lastAccData!![0]},${lastAccData!![1]},${lastAccData!![2]}")
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        lastGyroData = it.values.clone()
                        gyroEventCount++
                        Log.d(TAG, "Gyroscope event: ${lastGyroData!![0]},${lastGyroData!![1]},${lastGyroData!![2]}")
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        lastMagData = it.values.clone()
                        magEventCount++
                        Log.d(TAG, "Magnetometer event: ${lastMagData!![0]},${lastMagData!![1]},${lastMagData!![2]}")
                    }
                    else -> return
                }

                if (!hasShownImuWarning && isRecording && timestamp - sensorStartTime > IMU_TIMEOUT_MS &&
                    accEventCount == 0 && gyroEventCount == 0 && magEventCount == 0) {
                    Log.e(TAG, "No IMU data received after ${IMU_TIMEOUT_MS}ms")
                    Toast.makeText(this, "No IMU data detected. Check sensors.", Toast.LENGTH_LONG).show()
                    hasShownImuWarning = true
                }

                if (timestamp - lastImuWriteTime >= IMU_WRITE_INTERVAL_MS && lastAccData != null && lastGyroData != null) {
                    // Update orientation
                    updateOrientation(timestamp)

                    // Transform accelerometer to world frame
                    val worldAcc = transformToWorldFrame(lastAccData!!)

                    // Update position via IMU
                    updatePosition(timestamp, worldAcc)

                    // Write data
                    val accX = lastAccData!![0]
                    val accY = lastAccData!![1]
                    val accZ = lastAccData!![2]
                    val gyroX = lastGyroData!![0]
                    val gyroY = lastGyroData!![1]
                    val gyroZ = lastGyroData!![2]
                    val magX = lastMagData?.get(0) ?: 0f
                    val magY = lastMagData?.get(1) ?: 0f
                    val magZ = lastMagData?.get(2) ?: 0f
                    val data = "$timestamp,${position[0]},${position[1]},${position[2]},$accX,$accY,$accZ," +
                            "${worldAcc[0]},${worldAcc[1]},${worldAcc[2]},$gyroX,$gyroY,$gyroZ,$magX,$magY,$magZ"
                    imuFileWriter?.write("$data\n")
                    imuFileWriter?.flush()
                    lastImuWriteTime = timestamp
                    Log.d(TAG, "IMU data written: $data")
                }else{
                    Log.d(TAG, "IMU data not written")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing IMU data: ${e.message}", e)
            }
        }
    }

    private fun updateOrientation(timestamp: Long) {
        if (lastImuTimestamp == 0L) {
            lastImuTimestamp = timestamp
            return
        }

        val dt = (timestamp - lastImuTimestamp) / 1000.0f // Seconds
        lastImuTimestamp = timestamp

        // Gyroscope integration
        val gyroX = lastGyroData!![0]
        val gyroY = lastGyroData!![1]
        val gyroZ = lastGyroData!![2]
        val gyroNorm = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
        if (gyroNorm > 0.01) { // Avoid division by zero
            val angle = gyroNorm * dt
            val axisX = gyroX / gyroNorm
            val axisY = gyroY / gyroNorm
            val axisZ = gyroZ / gyroNorm
            val s = sin(angle / 2)
            val deltaQ = floatArrayOf(
                cos(angle / 2),
                axisX * s,
                axisY * s,
                axisZ * s
            )
            orientationQuaternion = quaternionMultiply(orientationQuaternion, deltaQ)
            normalizeQuaternion(orientationQuaternion)
        }

        // Accelerometer and magnetometer correction
        if (lastMagData != null && lastAccData != null) {
            // Compute gravity direction from accelerometer
            val accNorm = sqrt(lastAccData!![0] * lastAccData!![0] + lastAccData!![1] * lastAccData!![1] + lastAccData!![2] * lastAccData!![2])
            if (accNorm > 0.1) {
                val gravity = floatArrayOf(
                    lastAccData!![0] / accNorm,
                    lastAccData!![1] / accNorm,
                    lastAccData!![2] / accNorm
                )
                // Compute magnetic north
                val magNorm = sqrt(lastMagData!![0] * lastMagData!![0] + lastMagData!![1] * lastMagData!![1] + lastMagData!![2] * lastMagData!![2])
                if (magNorm > 0.1) {
                    val mag = floatArrayOf(
                        lastMagData!![0] / magNorm,
                        lastMagData!![1] / magNorm,
                        lastMagData!![2] / magNorm
                    )
                    // Compute expected gravity and magnetic field in world frame
                    val expectedGravity = floatArrayOf(0f, 0f, -1f)
                    val expectedMag = floatArrayOf(0f, 1f, 0f) // Approximate magnetic north
                    // Compute error quaternions
                    val gravityError = crossProduct(rotateVector(orientationQuaternion, expectedGravity), gravity)
                    val magError = crossProduct(rotateVector(orientationQuaternion, expectedMag), mag)
                    val error = floatArrayOf(
                        gravityError[0] + magError[0],
                        gravityError[1] + magError[1],
                        gravityError[2] + magError[2]
                    )
                    val errorNorm = sqrt(error[0] * error[0] + error[1] * error[1] + error[2] * error[2])
                    if (errorNorm > 0.01) {
                        val correctionQ = floatArrayOf(
                            1f,
                            error[0] / errorNorm * sin((1 - alpha) * errorNorm / 2),
                            error[1] / errorNorm * sin((1 - alpha) * errorNorm / 2),
                            error[2] / errorNorm * sin((1 - alpha) * errorNorm / 2)
                        )
                        orientationQuaternion = quaternionMultiply(orientationQuaternion, correctionQ)
                        normalizeQuaternion(orientationQuaternion)
                    }
                }
            }
        }
    }

    private fun transformToWorldFrame(acc: FloatArray): FloatArray {
        // Remove gravity and rotate to world frame
        val accNorm = sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2])
        val gravityCorrected = if (accNorm > 0.1) {
            floatArrayOf(
                acc[0] - GRAVITY * lastAccData!![0] / accNorm,
                acc[1] - GRAVITY * lastAccData!![1] / accNorm,
                acc[2] - GRAVITY * lastAccData!![2] / accNorm
            )
        } else {
            acc
        }
        return rotateVector(orientationQuaternion, gravityCorrected)
    }

    private fun updatePosition(timestamp: Long, worldAcc: FloatArray) {
        if (lastImuTimestamp == 0L) return

        val dt = (timestamp - lastImuTimestamp) / 1000.0 // Seconds
        // Update velocity
        velocity[0] += worldAcc[0] * dt
        velocity[1] += worldAcc[1] * dt
        velocity[2] += worldAcc[2] * dt
        // Update position
        position[0] += velocity[0] * dt + 0.5 * worldAcc[0] * dt * dt
        position[1] += velocity[1] * dt + 0.5 * worldAcc[1] * dt * dt
        position[2] += velocity[2] * dt + 0.5 * worldAcc[2] * dt * dt

        lastWorldAcc = worldAcc
    }

    private fun saveGpsData(location: Location) {
        try {
            if (location.latitude == 0.0 && location.longitude == 0.0) {
                Log.w(TAG, "Invalid GPS location: $location")
                return
            }

            if (!hasShownGpsWarning && isRecording && System.currentTimeMillis() - gpsStartTime > GPS_TIMEOUT_MS &&
                gpsEventCount == 0) {
                Log.e(TAG, "No GPS data received after ${GPS_TIMEOUT_MS}ms")
                Toast.makeText(this, "No GPS data detected. Check location services.", Toast.LENGTH_LONG).show()
                hasShownGpsWarning = true
            }

            gpsEventCount++
            val timestamp = System.currentTimeMillis()
            val speed = if (location.hasSpeed()) location.speed else -1.0f
            val bearing = if (location.hasBearing()) location.bearing else -1.0f
            val accuracy = if (location.hasAccuracy()) location.accuracy else -1.0f

            if (firstGpsLocation == null) {
                firstGpsLocation = location
                Log.d(TAG, "First GPS fix: lat=${location.latitude}, lon=${location.longitude}, alt=${location.altitude}")
            }

            val (localX, localY, localZ) = latLonToLocalXYZ(location, firstGpsLocation!!)
            // Fuse GPS with IMU position
            position[0] = gpsAlpha * localX + (1 - gpsAlpha) * position[0]
            position[1] = gpsAlpha * localY + (1 - gpsAlpha) * position[1]
            position[2] = gpsAlpha * localZ + (1 - gpsAlpha) * position[2]
            // Reset velocity to reduce drift
            velocity[0] *= (1 - gpsAlpha)
            velocity[1] *= (1 - gpsAlpha)
            velocity[2] *= (1 - gpsAlpha)

            val data = "$timestamp,${location.latitude},${location.longitude},${location.altitude}," +
                    "$speed,$bearing,$accuracy,$localX,$localY,$localZ"
            gpsFileWriter?.write("$data\n")
            gpsFileWriter?.flush()
            Log.d(TAG, "GPS data written: $data")

            lastGpsLocation = location
            lastGpsTimestamp = timestamp
            lastGpsLocalX = localX
            lastGpsLocalY = localY
            lastGpsLocalZ = localZ
        } catch (e: Exception) {
            Log.e(TAG, "Error writing GPS data: ${e.message}", e)
        }
    }

    private fun latLonToLocalXYZ(location: Location, reference: Location): Triple<Double, Double, Double> {
        val latRad = Math.toRadians(location.latitude)
        val refLatRad = Math.toRadians(reference.latitude)
        val lonRad = Math.toRadians(location.longitude)
        val refLonRad = Math.toRadians(reference.longitude)

        val x = EARTH_RADIUS_M * (lonRad - refLonRad) * cos(refLatRad) // East-West
        val y = EARTH_RADIUS_M * (latRad - refLatRad) // North-South
        val z = location.altitude - reference.altitude // Up-Down

        return Triple(x, y, z)
    }

    // Quaternion utilities
    private fun quaternionMultiply(q1: FloatArray, q2: FloatArray): FloatArray {
        val w1 = q1[0]; val x1 = q1[1]; val y1 = q1[2]; val z1 = q1[3]
        val w2 = q2[0]; val x2 = q2[1]; val y2 = q2[2]; val z2 = q2[3]
        return floatArrayOf(
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2
        )
    }

    private fun normalizeQuaternion(q: FloatArray) {
        val norm = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (norm > 0) {
            q[0] /= norm
            q[1] /= norm
            q[2] /= norm
            q[3] /= norm
        }
    }

    private fun rotateVector(q: FloatArray, v: FloatArray): FloatArray {
        val qConj = floatArrayOf(q[0], -q[1], -q[2], -q[3])
        val vQuat = floatArrayOf(0f, v[0], v[1], v[2])
        val temp = quaternionMultiply(q, vQuat)
        val result = quaternionMultiply(temp, qConj)
        return floatArrayOf(result[1], result[2], result[3])
    }

    private fun crossProduct(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
        cameraExecutor.shutdown()
        Log.d(TAG, "onDestroy called")
    }
}