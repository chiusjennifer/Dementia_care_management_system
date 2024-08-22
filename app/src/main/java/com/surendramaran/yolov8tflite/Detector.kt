package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val detectorListener: DetectorListener
) {

    private var interpreter: Interpreter? = null
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    // 建立影像處理器，主要進行標準化處理和類型轉換
    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION)) // 對影像進行標準化
        .add(CastOp(INPUT_IMAGE_TYPE)) // 將影像轉換為指定的資料類型
        .build()

    // 設定模型與標籤
    fun setup() {
        // 載入模型文件
        val model = FileUtil.loadMappedFile(context, modelPath)
        val options = Interpreter.Options()
        options.numThreads = 4 // 設定使用的執行緒數量
        interpreter = Interpreter(model, options)

        // 獲取模型的輸入與輸出張量形狀
        val inputShape = interpreter?.getInputTensor(0)?.shape() ?: return
        val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: return

        // 設定張量的寬度、高度、通道數和元素數
        tensorWidth = inputShape[1]
        tensorHeight = inputShape[2]
        numChannel = outputShape[1]
        numElements = outputShape[2]

        // 讀取標籤文件
        try {
            val inputStream: InputStream = context.assets.open(labelPath)
            val reader = BufferedReader(InputStreamReader(inputStream))

            var line: String? = reader.readLine()
            while (line != null && line != "") {
                labels.add(line)
                line = reader.readLine()
            }

            reader.close()
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // 清除解譯器資源
    fun clear() {
        interpreter?.close()
        interpreter = null
    }

    // 執行影像偵測
    fun detect(frame: Bitmap) {
        interpreter ?: return
        if (tensorWidth == 0) return
        if (tensorHeight == 0) return
        if (numChannel == 0) return
        if (numElements == 0) return

        // 計算推理時間
        var inferenceTime = SystemClock.uptimeMillis()

        // 將影像縮放至模型所需的大小
        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

        // 將影像載入到 TensorImage 並進行處理
        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(resizedBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        // 創建輸出緩衝區並執行模型推理
        val output =
            TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter?.run(imageBuffer, output.buffer)

        // 解析模型輸出的最佳邊界框
        val bestBoxes = bestBox(output.floatArray)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        // 如果沒有偵測到物件，回調 onEmptyDetect
        if (bestBoxes == null) {
            detectorListener.onEmptyDetect()
            return
        }

        // 如果有偵測到物件，回調 onDetect
        detectorListener.onDetect(bestBoxes, inferenceTime)
    }

    // 找到最優的邊界框
    private fun bestBox(array: FloatArray): List<BoundingBox>? {

        val boundingBoxes = mutableListOf<BoundingBox>()

        // 遍歷所有元素以找到最佳邊界框
        for (c in 0 until numElements) {
            var maxConf = -1.0f
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            while (j < numChannel) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            // 如果置信度大於設定的閾值，則將其添加到邊界框列表中
            if (maxConf > CONFIDENCE_THRESHOLD) {
                val clsName = labels[maxIdx]
                val cx = array[c] // 中心 x 座標
                val cy = array[c + numElements] // 中心 y 座標
                val w = array[c + numElements * 2] // 寬度
                val h = array[c + numElements * 3] // 高度
                val x1 = cx - (w / 2F)
                val y1 = cy - (h / 2F)
                val x2 = cx + (w / 2F)
                val y2 = cy + (h / 2F)
                if (x1 < 0F || x1 > 1F) continue
                if (y1 < 0F || y1 > 1F) continue
                if (x2 < 0F || x2 > 1F) continue
                if (y2 < 0F || y2 > 1F) continue

                //如果偵測到人物，紀錄Log
                //if (clsName == "person"){
                //    Log.d("Detector", "Person detected with confidence: $maxConf at [$x1, $y1,$x2, $y2]")
               // }

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, cls = maxIdx, clsName = clsName
                    )
                )
            }
        }

        // 如果沒有邊界框，返回 null
        if (boundingBoxes.isEmpty()) return null

        // 執行非極大值抑制來過濾重疊的邊界框
        return applyNMS(boundingBoxes)
    }

    // 非極大值抑制 (NMS) 演算法
    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        // 遍歷排序後的邊界框，選擇置信度最高的框並過濾掉重疊度高的框
        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    // 計算兩個邊界框之間的 IoU（交并比）
    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    // 偵測器回調介面，用於返回偵測結果
    interface DetectorListener {
        fun onEmptyDetect() // 當偵測結果為空時調用
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) // 當偵測到物件時調用
    }

    // 伴生對象，用於存儲一些靜態變量
    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.3F
        private const val IOU_THRESHOLD = 0.5F
    }
}
