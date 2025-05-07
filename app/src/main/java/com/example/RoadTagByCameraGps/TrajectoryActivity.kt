package com.example.RoadTagByCameraGps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import kotlin.math.atan2


class TrajectoryActivity : AppCompatActivity() {

    private var trajectoryView: TrajectoryView? = null
    private var backButton: Button? = null
    private val TAG = "TrajectoryActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")

        try {
            setContentView(R.layout.activity_trajectory)
            Log.d(TAG, "setContentView completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set content view: ${e.message}", e)
            Toast.makeText(this, "Error loading layout: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            trajectoryView = findViewById(R.id.trajectoryView)
            backButton = findViewById(R.id.backButton)
            Log.d(TAG, "Views initialized: trajectoryView=$trajectoryView, backButton=$backButton")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize views: ${e.message}", e)
            Toast.makeText(this, "Error initializing views: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (trajectoryView == null || backButton == null) {
            Log.e(TAG, "TrajectoryView or BackButton is null")
            Toast.makeText(this, "UI components not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        backButton?.setOnClickListener {
            Log.d(TAG, "Back button clicked")
            finish()
        }

        val sessionFolderPath = intent.getStringExtra("SESSION_FOLDER")
        if (sessionFolderPath == null) {
            Log.e(TAG, "No session folder provided in Intent")
            trajectoryView?.setErrorMessage("No session folder provided")
            Toast.makeText(this, "No session folder provided", Toast.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "Session folder path: $sessionFolderPath")
        val imuFile = File(sessionFolderPath, "imu_data.csv")
        if (!imuFile.exists()) {
            Log.e(TAG, "IMU file not found: ${imuFile.absolutePath}")
            trajectoryView?.setErrorMessage("IMU file not found: ${imuFile.absolutePath}")
            Toast.makeText(this, "IMU file not found", Toast.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "IMU file exists: ${imuFile.absolutePath}")
        val trajectoryData = loadTrajectoryData(imuFile)
        Log.d(TAG, "Loaded ${trajectoryData.size} trajectory points")
        if (trajectoryData.isEmpty()) {
            Log.w(TAG, "No valid trajectory data in IMU file")
            trajectoryView?.setErrorMessage("No valid trajectory data in IMU file")
            Toast.makeText(this, "No valid trajectory data", Toast.LENGTH_LONG).show()
        } else {
            trajectoryView?.setTrajectoryData(trajectoryData)
        }
    }

    private fun loadTrajectoryData(imuFile: File): List<TrajectoryPoint> {
        val trajectoryData = mutableListOf<TrajectoryPoint>()
        try {
            BufferedReader(FileReader(imuFile)).use { reader ->
                val header = reader.readLine()
                Log.d(TAG, "CSV Header: $header")
                if (header != "timestamp,position_x,position_y,position_z") {
                    Log.w(TAG, "Unexpected header format: $header")
                }
                var lineNumber = 1
                reader.forEachLine { line ->
                    lineNumber++
                    Log.v(TAG, "Parsing line $lineNumber: $line")
                    val parts = line.split(",")
                    if (parts.size >= 4) {
                        try {
                            val timestamp = parts[0].toLongOrNull()
                            val x = parts[1].toFloatOrNull()
                            val y = parts[2].toFloatOrNull()
                            if (timestamp != null && x != null && y != null) {
                                trajectoryData.add(TrajectoryPoint(timestamp, x, y))
                                Log.v(TAG, "Parsed point: timestamp=$timestamp, x=$x, y=$y")
                            } else {
                                Log.w(TAG, "Invalid data at line $lineNumber: $line")
                            }
                        } catch (e: NumberFormatException) {
                            Log.w(
                                TAG,
                                "Parsing error at line $lineNumber: $line, Error: ${e.message}"
                            )
                        }
                    } else {
                        Log.w(TAG, "Insufficient columns at line $lineNumber: $line")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading IMU file: ${e.message}", e)
            trajectoryView?.setErrorMessage("Error reading IMU file: ${e.message}")
            Toast.makeText(this, "Error reading IMU file: ${e.message}", Toast.LENGTH_LONG).show()
        }
        return trajectoryData
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
    }

    data class TrajectoryPoint(
        val timestamp: Long,
        val x: Float,
        val y: Float
    )
}

class TrajectoryView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var trajectoryData: List<TrajectoryActivity.TrajectoryPoint> = emptyList()
    private var errorMessage: String? = null
    private val pathPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val arrowPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        textSize = 30f
    }
    private val errorPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        textSize = 40f
        isAntiAlias = true
    }
    private val arrowSize = 20f
    private var minX = 0f
    private var maxX = 10f
    private var minY = 0f
    private var maxY = 10f

    fun setTrajectoryData(data: List<TrajectoryActivity.TrajectoryPoint>) {
        trajectoryData = data
        errorMessage = null
        if (data.isNotEmpty()) {
            minX = data.minOf { it.x }
            maxX = data.maxOf { it.x }
            minY = data.minOf { it.y }
            maxY = data.maxOf { it.y }
            if (maxX - minX < 1f) {
                maxX = minX + 10f
            }
            if (maxY - minY < 1f) {
                maxY = minY + 10f
            }
            val xPadding = (maxX - minX) * 0.1f
            val yPadding = (maxY - minY) * 0.1f
            minX -= xPadding
            maxX += xPadding
            minY -= yPadding
            maxY += yPadding
        }
        invalidate()
    }

    fun setErrorMessage(message: String) {
        errorMessage = message
        trajectoryData = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (errorMessage != null) {
            canvas.drawText(
                errorMessage!!,
                width / 2f - errorPaint.measureText(errorMessage!!) / 2,
                height / 2f,
                errorPaint
            )
            return
        }

        if (trajectoryData.isEmpty()) {
            canvas.drawText(
                "No trajectory data",
                width / 2f - errorPaint.measureText("No trajectory data") / 2,
                height / 2f,
                errorPaint
            )
            return
        }

        val widthF = width.toFloat()
        val heightF = height.toFloat()
        val xRange = maxX - minX
        val yRange = maxY - minY

        val originX = ((0f - minX) / xRange * widthF).coerceIn(0f, widthF)
        val originY = ((0f - minY) / yRange * heightF).coerceIn(0f, heightF)
        canvas.drawLine(originX, 0f, originX, heightF, axisPaint)
        canvas.drawLine(0f, originY, widthF, originY, axisPaint)
        canvas.drawText("East (m)", widthF - 100f, originY - 10f, axisPaint)
        canvas.drawText("North (m)", originX + 10f, 30f, axisPaint)

        val path = Path()
        var firstPoint = true
        trajectoryData.forEachIndexed { index, point ->
            val canvasX = ((point.x - minX) / xRange * widthF).coerceIn(0f, widthF)
            val canvasY = ((point.y - minY) / yRange * heightF).coerceIn(0f, heightF)
            if (firstPoint) {
                path.moveTo(canvasX, canvasY)
                firstPoint = false
            } else {
                path.lineTo(canvasX, canvasY)
            }

            if (index < trajectoryData.size - 1) {
                val nextPoint = trajectoryData[index + 1]
                val nextCanvasX = ((nextPoint.x - minX) / xRange * widthF).coerceIn(0f, widthF)
                val nextCanvasY = ((nextPoint.y - minY) / yRange * heightF).coerceIn(0f, heightF)
                val dx = nextCanvasX - canvasX
                val dy = nextCanvasY - canvasY
                val angle = atan2(dy, dx)
                drawArrow(canvas, canvasX, canvasY, angle)
            }
        }
        canvas.drawPath(path, pathPaint)
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, angle: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        val arrowPath = Path().apply {
            moveTo(0f, 0f)
            lineTo(-arrowSize, arrowSize / 2)
            lineTo(-arrowSize, -arrowSize / 2)
            close()
        }
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
    }
}