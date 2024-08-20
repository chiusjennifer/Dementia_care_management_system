package com.surendramaran.yolov8tflite

import android.content.Context
import android.content.SharedPreferences

class PreferencesHelper(context: Context) {

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("MyRecord", Context.MODE_PRIVATE)

    fun saveString(key: String, value: String){
        val editor = sharedPreferences.edit()
        editor.putString(key, value)
        editor.apply()
    }

    fun getStrong(key: String, defaultValue: String):String?{
        return sharedPreferences.getString(key, defaultValue)
    }

}