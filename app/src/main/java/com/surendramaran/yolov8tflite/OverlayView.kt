package com.surendramaran.yolov8tflite

import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import kotlin.math.max
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val Context.dataStore:DataStore<Preferences> by preferencesDataStore(name = "app_preferences")
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private val currentDateTime = getCurrentDateTime()
    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var detectPaint = Paint()
    private val renderThread = RenderThread()
    private var bounds = Rect()
    private val LAST_DETECTED_TIME_KEY = stringPreferencesKey("last_detected_time")
    private val choreographer = Choreographer.getInstance()
    private var lastLogTime = 0L  // Variable to store the last log time in milliseconds
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // 在這裡處理繪製工作
            postInvalidate()  // 通知 UI 更新
            // 繼續下一幀的繪製回調
            choreographer.postFrameCallback(this)
        }
    }
    init {
        initPaints()
        renderThread.startThread() // 改為 startThread() 以避免重複啟動
        // 開始幀回調
        choreographer.postFrameCallback(frameCallback)
    }
    fun clear() {
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        detectPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        //文字底色
        textBackgroundPaint.color = Color.GREEN
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f
        //文字顏色
        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f
        //辨識框顏色
        boxPaint.color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
        //偵測框顏色
        detectPaint.strokeWidth = 3f
        detectPaint.color = Color.RED
        detectPaint.style = Paint.Style.STROKE


    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val detectRect = RectF(200f, 600f, 900f, 900f)
        canvas.drawRect(detectRect,detectPaint)

        results.forEach { boundingBox ->
            val left = boundingBox.x1 * width
            val top = boundingBox.y1 * height
            val right = boundingBox.x2 * width
            val bottom = boundingBox.y2 * height

            val boxRect = RectF(left, top, right, bottom)
            if (RectF.intersects(boxRect, detectRect)&& boundingBox.clsName == "person") {
                canvas.drawRect(boxRect, boxPaint)
                // 提前計算文字尺寸
                val drawableText = boundingBox.clsName
                val textWidth = textPaint.measureText(drawableText)
                val textHeight = textPaint.textSize

                canvas.drawRect(
                    left,
                    top,
                    left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                    top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                    textBackgroundPaint
                )
                canvas.drawText(drawableText, left, top + textHeight, textPaint)
                // Check the current time and log if more than 1 second has passed
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastLogTime >= 10000) {  // 1000 milliseconds = 1 second
                    val currentDateTime = getCurrentDateTime() // Fetch the current date and time
                    Log.d(TAG, "person exist $currentDateTime")
                    // Save to DataStore
                    saveDetectedTimeToDataStore(context, currentDateTime)
                    lastLogTime = currentTime  // Update the last log time

                }
            }

        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }
    @SuppressLint("SimpleDateFormat")
    fun getCurrentDateTime():String{
        val currentDate = Date()
        val dateFormat = SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(currentDate)
    }
    override fun onDetachedFromWindow(){
        super.onDetachedFromWindow()
        //停止 RenderThread
        renderThread.stopThread()
    }
    fun updateResults(boundingBoxes: List<BoundingBox>){
        //將繪製任務提交給 RenderThread
        renderThread.postTask(Runnable {
            setResults(boundingBoxes)
            postInvalidate() //通知主線程更新畫面
        })
    }

    private fun saveDetectedTimeToDataStore(context: Context, dateTime: String) {
        CoroutineScope(Dispatchers.IO).launch {
            context.dataStore.edit { preferences ->
                preferences[LAST_DETECTED_TIME_KEY] = dateTime
            }
        }
    }
    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}