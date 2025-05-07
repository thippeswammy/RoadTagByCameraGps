package com.example.RoadTagByCameraGps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var orientationView: OrientationView? = null
    private var gpsTextView: TextView? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var isRecording = false
    private var sessionFolder: File? = null
    private var imuFile: File? = null
    private var rawSensorFile: File? = null
    private var imuWriter: FileWriter? = null
    private var rawSensorWriter: FileWriter? = null
    private var lastUpdateTime: Long = 0
    private var positionX = 0.0
    private var positionY = 0.0
    private var velocityX = 0.0
    private var velocityY = 0.0
    private var lastAccelX = 0.0
    private var lastAccelY = 0.0
    private var lastAccelZ = 0.0

    // Orientation variables for sensor fusion
    private var roll = 0f
    private var pitch = 0f
    private var yaw = 0f
    private var lastGyroTime: Long = 0
    private val alpha = 0.98f // Complementary filter constant
    private val magneticDeclination = 0f // Adjust based on location if needed (degrees)

    // Lists to store raw data for post-processing
    private val accelDataList = mutableListOf<SensorData>()
    private val gyroDataList = mutableListOf<SensorData>()
    private val magDataList = mutableListOf<SensorData>()

    // GPS last known location for drift correction
    private var lastKnownLocation: Location? = null

    private val TAG = "MainActivity"

    data class SensorData(val timestamp: Long, val x: Float, val y: Float, val z: Float)
    data class OrientationData(val timestamp: Long, val roll: Float, val pitch: Float, val yaw: Float)
    data class PositionData(val timestamp: Long, val x: Double, val y: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        orientationView = findViewById(R.id.orientationView)
        gpsTextView = findViewById(R.id.gpsTextView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        // Initialize sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Initialize location manager for GPS
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        requestLocationPermissions()

        startButton?.setOnClickListener {
            startRecording()
        }

        stopButton?.setOnClickListener {
            stopRecording()
        }
    }

    private fun requestLocationPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_LONG).show()
            gpsTextView?.text = "GPS: Permission denied"
        }
    }

    private fun startLocationUpdates() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
                Log.d(TAG, "GPS updates started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GPS updates: ${e.message}", e)
            gpsTextView?.text = "GPS: Error starting updates"
        }
    }

    override fun onLocationChanged(location: Location) {
        lastKnownLocation = location
        gpsTextView?.text = "GPS: (${location.latitude}, ${location.longitude})"
        Log.d(TAG, "GPS Location: (${location.latitude}, ${location.longitude})")
    }

    private fun startRecording() {
        if (isRecording) {
            Toast.makeText(this, "Already recording", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        startButton?.isEnabled = false
        stopButton?.isEnabled = true
        positionX = 0.0
        positionY = 0.0
        velocityX = 0.0
        velocityY = 0.0
        roll = 0f
        pitch = 0f
        yaw = 0f
        lastUpdateTime = System.currentTimeMillis()
        lastGyroTime = lastUpdateTime
        accelDataList.clear()
        gyroDataList.clear()
        magDataList.clear()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sessionDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Session_$timestamp")
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            Log.e(TAG, "Failed to create session folder: ${sessionDir.absolutePath}")
            Toast.makeText(this, "Failed to create session folder", Toast.LENGTH_LONG).show()
            isRecording = false
            startButton?.isEnabled = true
            stopButton?.isEnabled = false
            return
        }
        sessionFolder = sessionDir
        Log.d(TAG, "Session folder created: ${sessionDir.absolutePath}")

        imuFile = File(sessionDir, "imu_data.csv")
        try {
            imuWriter = FileWriter(imuFile)
            imuWriter?.append("timestamp,position_x,position_y,position_z\n")
            Log.d(TAG, "IMU file initialized: ${imuFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing IMU file: ${e.message}", e)
            Toast.makeText(this, "Error initializing IMU file", Toast.LENGTH_LONG).show()
            isRecording = false
            startButton?.isEnabled = true
            stopButton?.isEnabled = false
            return
        }

        rawSensorFile = File(sessionDir, "raw_sensor_data.csv")
        try {
            rawSensorWriter = FileWriter(rawSensorFile)
            rawSensorWriter?.append("timestamp,sensor_type,value_x,value_y,value_z\n")
            Log.d(TAG, "Raw sensor file initialized: ${rawSensorFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing raw sensor file: ${e.message}", e)
            Toast.makeText(this, "Error initializing raw sensor file", Toast.LENGTH_LONG).show()
            isRecording = false
            startButton?.isEnabled = true
            stopButton?.isEnabled = false
            return
        }

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        } ?: Log.e(TAG, "Accelerometer sensor not available")

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        } ?: Log.e(TAG, "Gyroscope sensor not available")

        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        } ?: Log.e(TAG, "Magnetometer sensor not available")

        Log.d(TAG, "Recording started")
    }

    private fun stopRecording() {
        if (!isRecording) {
            Toast.makeText(this, "Not recording", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = false
        startButton?.isEnabled = true
        stopButton?.isEnabled = false
        sensorManager.unregisterListener(this)

        try {
            rawSensorWriter?.flush()
            rawSensorWriter?.close()
            Log.d(TAG, "Raw sensor data saved: ${rawSensorFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving raw sensor data: ${e.message}", e)
            Toast.makeText(this, "Error saving raw sensor data", Toast.LENGTH_LONG).show()
        }

        try {
            imuWriter?.flush()
            imuWriter?.close()
            Log.d(TAG, "Preliminary IMU data saved: ${imuFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving preliminary IMU data: ${e.message}", e)
            Toast.makeText(this, "Error saving preliminary IMU data", Toast.LENGTH_LONG).show()
        }

        correctOrientationAndTrajectory()
        orientationView?.setOrientation(0f, 0f, 0f) // Reset animation

        val intent = Intent(this, TrajectoryActivity::class.java).apply {
            putExtra("SESSION_FOLDER", sessionFolder?.absolutePath)
        }
        startActivity(intent)
        Log.d(TAG, "Recording stopped, launching TrajectoryActivity")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val currentTime = System.currentTimeMillis()
        val dt = (currentTime - lastUpdateTime) / 1000.0
        lastUpdateTime = currentTime

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (isRecording) {
            val sensorType = when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> "ACCEL"
                Sensor.TYPE_GYROSCOPE -> "GYRO"
                Sensor.TYPE_MAGNETIC_FIELD -> "MAG"
                else -> return
            }
            try {
                rawSensorWriter?.append("$currentTime,$sensorType,$x,$y,$z\n")
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> accelDataList.add(SensorData(currentTime, x, y, z))
                    Sensor.TYPE_GYROSCOPE -> gyroDataList.add(SensorData(currentTime, x, y, z))
                    Sensor.TYPE_MAGNETIC_FIELD -> magDataList.add(SensorData(currentTime, x, y, z))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing raw sensor data: ${e.message}", e)
            }
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccelX = x.toDouble()
                lastAccelY = y.toDouble()
                lastAccelZ = z.toDouble()

                val accelRoll = atan2(lastAccelY, sqrt(lastAccelX * lastAccelX + lastAccelZ * lastAccelZ)).toFloat()
                val accelPitch = atan2(-lastAccelX, sqrt(lastAccelY * lastAccelY + lastAccelZ * lastAccelZ)).toFloat()
                roll = alpha * roll + (1 - alpha) * accelRoll
                pitch = alpha * pitch + (1 - alpha) * accelPitch
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gyroDt = (currentTime - lastGyroTime) / 1000.0f
                lastGyroTime = currentTime

                val gyroX = x
                val gyroY = y
                val gyroZ = z

                roll += gyroX * gyroDt
                pitch += gyroY * gyroDt
                yaw += gyroZ * gyroDt
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val magX = x
                val magY = y
                val magZ = z

                val magXh = magX * cos(pitch) + magY * sin(roll) * sin(pitch) + magZ * cos(roll) * sin(pitch)
                val magYh = magY * cos(roll) - magZ * sin(roll)

                var magYaw = atan2(-magYh, magXh).toFloat()
                magYaw += magneticDeclination * (Math.PI / 180).toFloat()
                if (magYaw < 0) magYaw += 2 * Math.PI.toFloat()
                if (magYaw > 2 * Math.PI) magYaw -= 2 * Math.PI.toFloat()

                yaw = alpha * yaw + (1 - alpha) * magYaw
            }
        }

        // Update orientation animation
        orientationView?.setOrientation(roll, pitch, yaw)

        if (isRecording && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val accelNorth = (lastAccelX * cos(yaw) * cos(pitch) +
                    lastAccelY * (cos(yaw) * sin(pitch) * sin(roll) - sin(yaw) * cos(roll)) +
                    lastAccelZ * (cos(yaw) * sin(pitch) * cos(roll) + sin(yaw) * sin(roll))).toDouble()
            val accelEast = (lastAccelX * sin(yaw) * cos(pitch) +
                    lastAccelY * (sin(yaw) * sin(pitch) * sin(roll) + cos(yaw) * cos(roll)) +
                    lastAccelZ * (sin(yaw) * sin(pitch) * cos(roll) - cos(yaw) * sin(roll))).toDouble()
            val accelDown = (lastAccelX * (-sin(pitch)) +
                    lastAccelY * (cos(pitch) * sin(roll)) +
                    lastAccelZ * (cos(pitch) * cos(roll))).toDouble()

            val gravity = 9.81
            val correctedAccelNorth = accelNorth
            val correctedAccelEast = accelEast
            val correctedAccelDown = accelDown + gravity

            // GPS drift correction
            val gpsCorrectedNorth = lastKnownLocation?.let {
                val gpsNorth = it.latitude // Simplified; convert to meters in real app
                correctedAccelNorth + (gpsNorth - positionX) * 0.01
            } ?: correctedAccelNorth
            val gpsCorrectedEast = lastKnownLocation?.let {
                val gpsEast = it.longitude
                correctedAccelEast + (gpsEast - positionY) * 0.01
            } ?: correctedAccelEast

            velocityX += gpsCorrectedNorth * dt
            velocityY += gpsCorrectedEast * dt
            positionX += velocityX * dt
            positionY += velocityY * dt

            try {
                imuWriter?.append("${currentTime},${positionX},${positionY},0.0\n")
                Log.d(TAG, "Preliminary Position: ($positionX, $positionY)")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing preliminary IMU data: ${e.message}", e)
            }
        }
    }

    private fun correctOrientationAndTrajectory() {
        Log.d(TAG, "Starting post-recording orientation correction")

        val correctedOrientations = recomputeOrientation()
        val correctedPositions = recomputeTrajectory(correctedOrientations)

        try {
            imuWriter = FileWriter(imuFile)
            imuWriter?.append("timestamp,position_x,position_y,position_z\n")
            correctedPositions.forEach { (timestamp, x, y) ->
                imuWriter?.append("$timestamp,$x,$y,0.0\n")
                Log.d(TAG, "Corrected Position: ($x, $y) at $timestamp")
            }
            imuWriter?.flush()
            imuWriter?.close()
            Log.d(TAG, "Corrected IMU data saved: ${imuFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving corrected IMU data: ${e.message}", e)
            Toast.makeText(this, "Error saving corrected IMU data", Toast.LENGTH_LONG).show()
        }
    }

    private fun recomputeOrientation(): List<OrientationData> {
        val orientations = mutableListOf<OrientationData>()
        var correctedRoll = 0f
        var correctedPitch = 0f
        var correctedYaw = 0f
        var lastTime: Long = accelDataList.firstOrNull()?.timestamp ?: 0L

        val firstMag = magDataList.firstOrNull()
        val lastMag = magDataList.lastOrNull()
        var totalYawDrift = 0f
        if (firstMag != null && lastMag != null) {
            val firstYaw = computeYawFromMagnetometer(firstMag, correctedRoll, correctedPitch)
            val lastYaw = computeYawFromMagnetometer(lastMag, correctedRoll, correctedPitch)
            totalYawDrift = lastYaw - firstYaw
            Log.d(TAG, "Total yaw drift: $totalYawDrift radians")
        }
        val driftRate = if (gyroDataList.isNotEmpty()) totalYawDrift / gyroDataList.size else 0f

        var gyroIndex = 0
        var magIndex = 0
        for (accelData in accelDataList) {
            val currentTime = accelData.timestamp
            val dt = (currentTime - lastTime) / 1000.0f
            lastTime = currentTime

            val accelRoll = atan2(accelData.y, sqrt(accelData.x * accelData.x + accelData.z * accelData.z)).toFloat()
            val accelPitch = atan2(-accelData.x, sqrt(accelData.y * accelData.y + accelData.z * accelData.z)).toFloat()
            correctedRoll = alpha * correctedRoll + (1 - alpha) * accelRoll
            correctedPitch = alpha * correctedPitch + (1 - alpha) * accelPitch

            while (gyroIndex < gyroDataList.size && gyroDataList[gyroIndex].timestamp <= currentTime) {
                val gyroData = gyroDataList[gyroIndex]
                val gyroDt = if (gyroIndex > 0) {
                    (gyroData.timestamp - gyroDataList[gyroIndex - 1].timestamp) / 1000.0f
                } else dt
                correctedRoll += gyroData.x * gyroDt
                correctedPitch += gyroData.y * gyroDt
                correctedYaw += (gyroData.z - driftRate) * gyroDt
                gyroIndex++
            }

            while (magIndex < magDataList.size && magDataList[magIndex].timestamp <= currentTime) {
                val magData = magDataList[magIndex]
                val magYaw = computeYawFromMagnetometer(magData, correctedRoll, correctedPitch)
                correctedYaw = alpha * correctedYaw + (1 - alpha) * magYaw
                magIndex++
            }

            orientations.add(OrientationData(currentTime, correctedRoll, correctedPitch, correctedYaw))
        }
        Log.d(TAG, "Recomputed ${orientations.size} orientation points")
        return orientations
    }

    private fun computeYawFromMagnetometer(magData: SensorData, roll: Float, pitch: Float): Float {
        val magXh = magData.x * cos(pitch) + magData.y * sin(roll) * sin(pitch) + magData.z * cos(roll) * sin(pitch)
        val magYh = magData.y * cos(roll) - magData.z * sin(roll)
        var magYaw = atan2(-magYh, magXh).toFloat()
        magYaw += magneticDeclination * (Math.PI / 180).toFloat()
        if (magYaw < 0) magYaw += 2 * Math.PI.toFloat()
        if (magYaw > 2 * Math.PI) magYaw -= 2 * Math.PI.toFloat()
        return magYaw
    }

    private fun recomputeTrajectory(orientations: List<OrientationData>): List<PositionData> {
        val positions = mutableListOf<PositionData>()
        var posX = 0.0
        var posY = 0.0
        var velX = 0.0
        var velY = 0.0
        var lastTime: Long = accelDataList.firstOrNull()?.timestamp ?: 0L
        var orientationIndex = 0

        for (accelData in accelDataList) {
            val currentTime = accelData.timestamp
            val dt = (currentTime - lastTime) / 1000.0
            lastTime = currentTime

            while (orientationIndex < orientations.size - 1 && orientations[orientationIndex + 1].timestamp <= currentTime) {
                orientationIndex++
            }
            val orientation = orientations[orientationIndex]
            val roll = orientation.roll
            val pitch = orientation.pitch
            val yaw = orientation.yaw

            val accelNorth = (accelData.x * cos(yaw) * cos(pitch) +
                    accelData.y * (cos(yaw) * sin(pitch) * sin(roll) - sin(yaw) * cos(roll)) +
                    accelData.z * (cos(yaw) * sin(pitch) * cos(roll) + sin(yaw) * sin(roll))).toDouble()
            val accelEast = (accelData.x * sin(yaw) * cos(pitch) +
                    accelData.y * (sin(yaw) * sin(pitch) * sin(roll) + cos(yaw) * cos(roll)) +
                    accelData.z * (sin(yaw) * sin(pitch) * cos(roll) - cos(yaw) * sin(roll))).toDouble()
            val accelDown = (accelData.x * (-sin(pitch)) +
                    accelData.y * (cos(pitch) * sin(roll)) +
                    accelData.z * (cos(pitch) * cos(roll))).toDouble()

            val gravity = 9.81
            val correctedAccelNorth = accelNorth
            val correctedAccelEast = accelEast
            val correctedAccelDown = accelDown + gravity

            velX += correctedAccelNorth * dt
            velY += correctedAccelEast * dt
            posX += velX * dt
            posY += velY * dt

            positions.add(PositionData(currentTime, posX, posY))
        }
        Log.d(TAG, "Recomputed ${positions.size} position points")
        return positions
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.type}, accuracy: $accuracy")
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) {
            stopRecording()
        }
        locationManager.removeUpdates(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        Log.d(TAG, "onDestroy called")
    }
}

class OrientationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var roll = 0f
    private var pitch = 0f
    private var yaw = 0f

    private val phonePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val outlinePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val xAxisPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val yAxisPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val zAxisPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        textSize = 30f
        isAntiAlias = true
    }

    // 3D vertices of the phone (a rectangular prism)
    private val phoneWidth = 80f
    private val phoneHeight = 120f
    private val phoneDepth = 20f
    private val vertices = arrayOf(
        floatArrayOf(-phoneWidth / 2, -phoneHeight / 2, -phoneDepth / 2), // 0: Bottom-left-front
        floatArrayOf(phoneWidth / 2, -phoneHeight / 2, -phoneDepth / 2),  // 1: Bottom-right-front
        floatArrayOf(phoneWidth / 2, phoneHeight / 2, -phoneDepth / 2),   // 2: Top-right-front
        floatArrayOf(-phoneWidth / 2, phoneHeight / 2, -phoneDepth / 2),  // 3: Top-left-front
        floatArrayOf(-phoneWidth / 2, -phoneHeight / 2, phoneDepth / 2),  // 4: Bottom-left-back
        floatArrayOf(phoneWidth / 2, -phoneHeight / 2, phoneDepth / 2),   // 5: Bottom-right-back
        floatArrayOf(phoneWidth / 2, phoneHeight / 2, phoneDepth / 2),    // 6: Top-right-back
        floatArrayOf(-phoneWidth / 2, phoneHeight / 2, phoneDepth / 2)    // 7: Top-left-back
    )

    // Axis lengths
    private val axisLength = 100f
    private val axisVertices = arrayOf(
        floatArrayOf(0f, 0f, 0f), floatArrayOf(axisLength, 0f, 0f),  // X-axis
        floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, axisLength, 0f),  // Y-axis
        floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, axisLength)   // Z-axis
    )

    fun setOrientation(roll: Float, pitch: Float, yaw: Float) {
        this.roll = roll
        this.pitch = pitch
        this.yaw = yaw
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        canvas.save()
        canvas.translate(centerX, centerY)

        // Rotate and project the phone vertices
        val projectedPhoneVertices = vertices.map { vertex ->
            rotateAndProject(vertex[0], vertex[1], vertex[2], roll, pitch, yaw)
        }.toTypedArray()

        // Rotate and project the axis vertices
        val projectedAxisVertices = axisVertices.map { vertex ->
            rotateAndProject(vertex[0], vertex[1], vertex[2], roll, pitch, yaw)
        }.toTypedArray()

        // Draw the axes
        drawAxis(canvas, projectedAxisVertices, 0, 1, xAxisPaint, "X")
        drawAxis(canvas, projectedAxisVertices, 2, 3, yAxisPaint, "Y")
        drawAxis(canvas, projectedAxisVertices, 4, 5, zAxisPaint, "Z")

        // Draw the phone (connect the vertices to form a rectangular prism)
        drawPhone(canvas, projectedPhoneVertices)

        canvas.restore()

        // Draw orientation values
        canvas.drawText("Roll: %.2f°".format(roll * 180 / Math.PI), 10f, 30f, textPaint)
        canvas.drawText("Pitch: %.2f°".format(pitch * 180 / Math.PI), 10f, 60f, textPaint)
        canvas.drawText("Yaw: %.2f°".format(yaw * 180 / Math.PI), 10f, 90f, textPaint)
    }

    private fun rotateAndProject(x: Float, y: Float, z: Float, roll: Float, pitch: Float, yaw: Float): FloatArray {
        // Rotate around Y (roll), X (pitch), and Z (yaw)
        // Roll (around Y-axis)
        var rx = x * cos(roll) + z * sin(roll)
        var ry = y
        var rz = -x * sin(roll) + z * cos(roll)

        // Pitch (around X-axis)
        var rxx = rx
        var ryy = ry * cos(pitch) - rz * sin(pitch)
        var rzz = ry * sin(pitch) + rz * cos(pitch)

        // Yaw (around Z-axis)
        var rxxx = rxx * cos(yaw) - ryy * sin(yaw)
        var ryyy = rxx * sin(yaw) + ryy * cos(yaw)
        var rzzz = rzz

        // Simple perspective projection
        val focalLength = 300f
        val scale = focalLength / (focalLength + rzzz)
        val px = rxxx * scale
        val py = ryyy * scale

        return floatArrayOf(px, py, rzzz) // Include z for depth sorting
    }

    private fun drawAxis(canvas: Canvas, vertices: Array<FloatArray>, startIdx: Int, endIdx: Int, paint: Paint, label: String) {
        val start = vertices[startIdx]
        val end = vertices[endIdx]
        canvas.drawLine(start[0], start[1], end[0], end[1], paint)
        // Draw label near the end of the axis
        canvas.drawText(label, end[0] + 10f, end[1], textPaint)
    }

    private fun drawPhone(canvas: Canvas, vertices: Array<FloatArray>) {
        // Define edges of the prism (connecting vertices)
        val edges = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0), // Front face
            intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 4), // Back face
            intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7)  // Connecting edges
        )

        // Sort faces by average Z to handle occlusion (simple painter's algorithm)
        val faces = arrayOf(
            intArrayOf(0, 1, 2, 3), // Front
            intArrayOf(4, 5, 6, 7), // Back
            intArrayOf(0, 1, 5, 4), // Bottom
            intArrayOf(2, 3, 7, 6), // Top
            intArrayOf(0, 3, 7, 4), // Left
            intArrayOf(1, 2, 6, 5)  // Right
        )

        val faceDepths = faces.map { face ->
            val avgZ = face.map { vertices[it][2] }.average().toFloat()
            Pair(face, avgZ)
        }.sortedByDescending { it.second } // Sort by depth (back to front)

        // Draw faces
        for ((face, _) in faceDepths) {
            val path = Path()
            path.moveTo(vertices[face[0]][0], vertices[face[0]][1])
            for (i in 1 until face.size) {
                path.lineTo(vertices[face[i]][0], vertices[face[i]][1])
            }
            path.close()
            canvas.drawPath(path, phonePaint)
            canvas.drawPath(path, outlinePaint)
        }

        // Draw edges for clarity
        for (edge in edges) {
            val start = vertices[edge[0]]
            val end = vertices[edge[1]]
            canvas.drawLine(start[0], start[1], end[0], end[1], outlinePaint)
        }
    }
}