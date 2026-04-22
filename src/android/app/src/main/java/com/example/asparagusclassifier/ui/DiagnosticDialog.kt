package com.example.asparagusclassifier.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import com.example.asparagusclassifier.algorithm.AlgorithmResult

/**
 * 芦笋分选诊断对话框
 * 负责展示直线度分析图表、3D 位姿数据以及拉直切片对比图
 */
object DiagnosticDialog {

    fun show(context: Context, result: AlgorithmResult) {
        val strips = result.diagStrips ?: return
        if (strips.isEmpty()) return

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_NoActionBar_Fullscreen).create()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(32, 64, 32, 32)
        }

        // --- 标题栏 ---
        val header = RelativeLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 48) }
        }
        val title = TextView(context).apply {
            text = "直线度深度诊断 (拉直对比图)"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        }
        val close = Button(context).apply {
            text = "关闭诊断"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        val lpClose = RelativeLayout.LayoutParams(-2, -2).apply { addRule(RelativeLayout.ALIGN_PARENT_END) }
        header.addView(close, lpClose)
        root.addView(header)

        // --- 滚动内容区 ---
        val analysisScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val analysisContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 40)
        }

        // Section 1: 直线度分析
        addSectionHeader(context, analysisContent, "一、直线度深度分析 (Straightness)", Color.CYAN)
        val straightnessText = TextView(context).apply {
            val overall = String.format("%.2f", result.straightnessOverall)
            val head = String.format("%.2f", result.straightnessHead)
            val tail = String.format("%.2f", result.straightnessTail)
            text = "• 整体 RMSE: ${overall} mm\n• 头部 RMSE: ${head} mm\n• 尾部 RMSE: ${tail} mm"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(20, 0, 0, 20)
        }
        analysisContent.addView(straightnessText)

        // Section 2: 3D 位姿分析
        addSectionHeader(context, analysisContent, "二、3D 空间位姿报告 (Pose 3D)", Color.GREEN)
        val poseText = TextView(context).apply {
            val cam = result.cameraPosWorld?.let { String.format("X:%.1f, Y:%.1f, Z:%.1f", it[0], it[1], it[2]) } ?: "未知"
            val head3d = result.headPosWorld?.let { String.format("X:%.1f, Y:%.1f, Z:%.1f", it[0], it[1], it[2]) } ?: "未知"
            val tail3d = result.tailPosWorld?.let { String.format("X:%.1f, Y:%.1f, Z:%.1f", it[0], it[1], it[2]) } ?: "未知"
            
            text = "• 相机位置: ($cam) mm\n• 芦笋头部: ($head3d) mm\n• 芦笋尾部: ($tail3d) mm\n" +
                   "• 镜头高度: ${String.format("%.1f", result.poseDistanceMm)} mm\n" +
                   "• 相机倾角: ${String.format("%.1f", result.tiltAngle)}°"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(20, 0, 0, 30)
        }
        analysisContent.addView(poseText)

        // Section 3: 图形对比
        addSectionHeader(context, analysisContent, "三、局部拉直切片对比", Color.YELLOW)

        val imageScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val imageContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        
        val labels = listOf("头段", "整体", "尾段")
        strips.forEachIndexed { index, bitmap ->
            val itemWrapper = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 0, 10, 0)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val img = ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(350, -2)
            }
            val label = TextView(context).apply {
                text = labels.getOrElse(index) { "区域$index" }
                setTextColor(Color.GRAY)
                textSize = 12f
            }
            itemWrapper.addView(img)
            itemWrapper.addView(label)
            imageContainer.addView(itemWrapper)
        }
        imageScroll.addView(imageContainer)
        analysisContent.addView(imageScroll)
        
        analysisScroll.addView(analysisContent)
        root.addView(analysisScroll)
        
        dialog.setContentView(root)
        dialog.show()
    }

    private fun addSectionHeader(context: Context, parent: LinearLayout, titleText: String, color: Int) {
        val header = TextView(context).apply {
            text = titleText
            setTextColor(color)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 10)
        }
        parent.addView(header)
    }
}
