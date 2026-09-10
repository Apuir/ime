package com.ninthsoft.ime.input.panel.toolbar

import android.content.Context
import android.graphics.drawable.Drawable
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.input.panel.PanelAction

/**
 * [ToolbarRenderer] 所需的图标资源集合。
 * 将这些 Drawable 收敛到一个资源类，避免 [ToolbarRenderer] 构造函数传递大量分散参数。
 */
data class ToolbarRendererResources(
    val menu: Drawable?,
    val arrow: Drawable?,
    val clipboard: Drawable?,
    val undo: Drawable?,
    val redo: Drawable?,
    val palette: Drawable?,
    val cursorMove: Drawable?,
    val expand: Drawable?,
    val clear: Drawable?,
    /** 工具栏中间那排可自定义的工具。为空时表示用户没有放置任何工具。 */
    val centerButtons: List<ToolbarButtonSpec> = emptyList(),
)

/** 工具栏中间区域的一个工具按钮（图标 + 动作）。 */
data class ToolbarButtonSpec(
    val drawable: Drawable?,
    val action: PanelAction,
)

/** 按用户配置读取工具栏中间那排工具，并加载对应图标。 */
fun configuredToolbarButtons(context: Context): List<ToolbarButtonSpec> {
    return KeyboardManager.Keyboard.ToolbarTools.getKeys(context)
        .mapNotNull { ToolbarTool.byKey(it) }
        .map { tool ->
            ToolbarButtonSpec(
                drawable = context.getDrawable(tool.iconRes),
                action = tool.action,
            )
        }
}
