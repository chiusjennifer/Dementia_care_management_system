package com.surendramaran.yolov8tflite

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class IdentityActivity : AppCompatActivity() {
    private lateinit var adapter: ArrayAdapter<String>
    private val lunch = arrayListOf("Jennifer", "Kevin", "Nana", "Alice")
    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_identity)
        val spinnerIdentity: Spinner = findViewById(R.id.spinner)
        val button: Button = findViewById(R.id.button)
        val buttonAddItem: ImageButton = findViewById(R.id.buttonAddItem)
        // 創建 ArrayAdapter，將資料綁定到 Spinner
        adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, lunch)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerIdentity.adapter = adapter
        // 當選擇某項時，顯示 Toast
        spinnerIdentity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long)=  // 在这里处理选中项的逻辑
                Toast.makeText(this@IdentityActivity, "你選擇的是: ${lunch[pos]}", Toast.LENGTH_LONG).show()

            override fun onNothingSelected(parent: AdapterView<*>?) {

            }
        }
        // 新增選項按鈕點擊事件，彈出輸入框
        buttonAddItem.setOnClickListener {
            showAddItemDialog()
        }
        // 前往主頁面的按鈕
        button.setOnClickListener {
            val selectedItem = lunch[spinnerIdentity.selectedItemPosition] // 取得目前選擇的項目
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("selected_item", selectedItem) // 傳遞選擇的項目
            startActivity(intent)
            finish()
        }
        // 為 spinner 的每個選項設置長按事件來刪除
        spinnerIdentity.setOnLongClickListener {
            val selectedPosition = spinnerIdentity.selectedItemPosition
            if (selectedPosition >= 0) {
                showDeleteItemDialog(selectedPosition)
            }
            true
        }
    }
    private fun showAddItemDialog() {
        // 建立 AlertDialog
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_item, null)
        val editTextNewItem = dialogView.findViewById<EditText>(R.id.editTextNewItem)

        AlertDialog.Builder(this)
            .setTitle("添加新選項")
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val newItem = editTextNewItem.text.toString()
                if (newItem.isNotBlank()) {
                    lunch.add(newItem) // 將新項目加入列表
                    adapter.notifyDataSetChanged() // 通知 adapter 更新
                } else {
                    Toast.makeText(this, "請輸入有效的選項", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    // 顯示刪除選項的確認對話框
    private fun showDeleteItemDialog(position: Int) {
        AlertDialog.Builder(this)
            .setTitle("刪除選項")
            .setMessage("確定要刪除 \"${lunch[position]}\" 嗎？")
            .setPositiveButton("刪除") { _, _ ->
                lunch.removeAt(position) // 刪除選項
                adapter.notifyDataSetChanged() // 通知 adapter 更新
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
