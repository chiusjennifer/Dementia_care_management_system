package com.surendramaran.yolov8tflite

import android.os.Handler
import android.os.HandlerThread
import java.util.LinkedList

class RenderThread: Thread() {
    private var isRunning = false
    private val taskQueue = LinkedList<Runnable>()

    override fun run() {
        while (isRunning) {
            val task = synchronized(taskQueue) {
                if (taskQueue.isNotEmpty()) taskQueue.removeFirst() else null
            }
            task?.run()
        }
    }

    fun startThread() {
        if (!isRunning) {
            isRunning = true
            start()
        }
    }

    fun stopThread() {
        isRunning = false
        // 通知線程結束
        interrupt()
    }

    fun postTask(task: Runnable) {
        synchronized(taskQueue) {
            taskQueue.add(task)
        }
    }
}