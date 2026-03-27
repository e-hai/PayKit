package com.kit.pay.sample

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.viewModels

class MainActivity : AppCompatActivity() {

    private val mainViewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 初始化支付 SDK（会在初始化回调中自动处理后续操作）
        mainViewModel.init()
        
        // 加载商品列表
        mainViewModel.actionLoadSubProductList()
        
        // 注意：无需手动调用 actionRecoverUnfinishedOrders()
        // 它会在 SDK 初始化成功后自动调用
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理资源，防止内存泄露
        // 如果 Activity 被销毁（如屏幕旋转、用户退出），调用 cleanup
        if (isFinishing) {
            mainViewModel.cleanup()
        }
    }
}