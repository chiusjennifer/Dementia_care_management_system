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
import java.util.Locale
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.surendramaran.yolov8tflite.BoundingBox
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.RenderThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import java.util.concurrent.TimeUnit

private val Context.dataStore:DataStore<Preferences> by preferencesDataStore(name = "settings")
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private val client = OkHttpClient()
    private val token = "jzdx0zKHtE5wRCOvUtiXCM8zK77RMrozke9c72RT2KU" // 替換為你的 LINE Notify Token
    private val notifyUrl = "https://notify-api.line.me/api/notify"
    private var intentMessage: String? = null // 用來保存從 Intent 傳遞過來的訊息
    private val currentDateTime = getCurrentDateTime()
    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var detectPaint = Paint()
    private val renderThread = RenderThread()
    private var bounds = Rect()
    private val DETECTED_TIMES_KEY = stringSetPreferencesKey("detected_times")
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
    // 用來設置從 Intent 傳遞過來的訊息
    fun setIntentMessage(message: String) {
        this.intentMessage = message
    }
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val detectRect = RectF(400f, 450f, 700f, 800f)
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
                var currentTime = System.currentTimeMillis()
                if(lastLogTime==0L)
                {
                    lastLogTime = currentTime
                }

                if (currentTime - lastLogTime >= 10000) {  // 1000 milliseconds = 1 second
                    lastLogTime = currentTime  // Update the last log time
                    // Save to DataStore using a coroutine
                    CoroutineScope(Dispatchers.IO).launch {
                        appendDetectedTime() // Save current time
                        if (isDetectedForTenSeconds()){
                            handleDetection()
                            //顯示訊息
                            sendLineNotify("注意病人:$intentMessage")
                            //todo 清空datastore
                            //clearAllDetectedTimes()  // 清空 DataStore 中的检测记录
                        }
                    }
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

    // 追加新的时间戳到集合中
    suspend fun appendDetectedTime() {
        val currentTimeString = getCurrentFormattedTime()

        try {
            context.dataStore.edit { settings ->
                val existingTimes = settings[DETECTED_TIMES_KEY]?.toMutableSet() ?: mutableSetOf()
                existingTimes.add(currentTimeString)
                settings[DETECTED_TIMES_KEY] = existingTimes

                // Check if the size exceeds 2
                if (existingTimes.size > 2) {
                    // Clear all entries if more than 2
                    settings.clear()  // Clear all data
                    Log.d(TAG, "Cleared all detected times due to exceeding limit.")
                }

                Log.d(TAG, "Saved detected time: $currentTimeString")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save detected time: ${e.message}")
        }
    }
    @SuppressLint("SimpleDateFormat")
    fun getCurrentFormattedTime(): String {
        // 使用 SimpleDateFormat 將當前時間格式化為日期時間字串
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date())
    }
    private suspend fun isDetectedForTenSeconds(): Boolean {
        // 從 DataStore 中取得偵測時間戳記，並將其轉換為時間格式的 Long
        val detectionTimes = context.dataStore.data.map { settings ->
            settings[DETECTED_TIMES_KEY]?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        }.firstOrNull() ?: emptyList()

        if (detectionTimes.isEmpty()) {
            return false
        }
        val now = System.currentTimeMillis()
        val tenSecondsAgo = now - TimeUnit.SECONDS.toMillis(10)

        // 找出最近 10 秒內的偵測記錄
        val recentDetections = detectionTimes.filter { it >= tenSecondsAgo }

        val earliestTime = detectionTimes.firstOrNull() ?: return false
        return (now - earliestTime >= TimeUnit.SECONDS.toMillis(10)) && recentDetections.isNotEmpty()
    }
    private suspend fun handleDetection() {
        if (isDetectedForTenSeconds()) {
            // 發送通知之前清空資料
            sendLineNotify("注意病人:$intentMessage")
            clearAllDetectedTimes()  // 清空 DataStore 中的检测记录
        }
    }
    private fun sendLineNotify(message: String) {
        // 建立 POST 請求
        val formBody = FormBody.Builder()
            .add("message", message)
            .build()

        val request = Request.Builder()
            .url(notifyUrl)
            .addHeader("Authorization", "Bearer $token")
            .post(formBody)
            .build()

        // 發送請求
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                // 處理失敗
                Log.e(TAG, "Failed to send LINE notification: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    // 處理成功
                    println("Message sent successfully!")
                } else {
                    // 處理錯誤
                    Log.e(TAG, "Failed to send message: ${response.code}")
                }
                response.close()
            }
        })
    }
    // 清空 DataStore 中的检测记录
    private suspend fun clearAllDetectedTimes() {
        try {
            context.dataStore.edit { settings ->
                settings.clear()  // Clear all data
                Log.d(TAG, "Cleared all detected times.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear detected times: ${e.message}")
        }
    }
    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}