package io.github.revenge.xposed.modules

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.children
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.modules.bridge.BridgeModule
import io.github.revenge.xposed.px

object BubbleModule : Module() {
    private var configureAccessoriesMarginHook: XC_MethodHook.Unhook? = null
    private var configureAuthorHook: XC_MethodHook.Unhook? = null

    private val DEFAULT_AVATAR_CURVE_RADIUS = 12.px.toFloat()
    private val DEFAULT_BUBBLE_CURVE_RADIUS = 12.px.toFloat()
    private val DEFAULT_BUBBLE_COLOR = 0x66000000
    private val PADDING_SMALL = 6.px
    private val PADDING_MEDIUM = 8.px
    private val PADDING_LARGE = 12.px

    private var avatarCurveRadius = DEFAULT_AVATAR_CURVE_RADIUS
    private var bubbleCurveRadius = DEFAULT_BUBBLE_CURVE_RADIUS
    private var chatBubbleColor = DEFAULT_BUBBLE_COLOR

    override fun onLoad(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.discord") return

        BridgeModule.registerMethod("bubbles.hook") {
            hookBubbles()
            null
        }

        BridgeModule.registerMethod("bubbles.unhook") {
            unhookBubbles()
            null
        }

        BridgeModule.registerMethod("bubbles.configure") {
            val avatarRadius = it.getOrNull(0) as? Number
            val bubbleRadius = it.getOrNull(1) as? Number
            val bubbleColor = it.getOrNull(2) as? Number

            configure(avatarRadius?.toFloat(), bubbleRadius?.toFloat(), bubbleColor?.toInt())
            null
        }

        BridgeModule.registerMethod("bubbles.revenge") {
            val method = it.getOrNull(0) as? String
            val args = it.getOrNull(1) as? Array<*>

            XposedBridge.log("[BubbleModule] Revenge called: $method with ${args?.size ?: 0} args")
            null
        }

        BridgeModule.registerMethod("bubbles.registerCallback") {
            null
        }

        val messageViewClassName = "com.discord.chat.presentation.message.MessageView"
        val messageViewClass = XposedHelpers.findClassIfExists(messageViewClassName, param.classLoader)

        if (messageViewClass == null) {
            XposedBridge.log("[BubbleModule] MessageView class not found")
            return
        }

        try {
            configureAccessoriesMarginHook = XposedHelpers.findAndHookMethod(
                messageViewClassName,
                param.classLoader,
                "configureAccessoriesMargin",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val binding = XposedHelpers.getObjectField(param.thisObject, "binding")
                        val accessoriesView = binding?.javaClass?.getField("accessoriesView")?.get(binding) as? ViewGroup
                        accessoriesView?.let { adjustMarginsForAccessories(it) }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[BubbleModule] Failed to hook configureAccessoriesMargin: ${e.message}")
        }

        try {
            configureAuthorHook = XposedHelpers.findAndHookMethod(
                messageViewClassName,
                param.classLoader,
                "configureAuthor",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        XposedBridge.log("[BubbleModule] configureAuthor called")
                        val view = param.thisObject as ViewGroup
                        applyRoundedSquareProfilePicture(view)
                        applyBubbleChat(view)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[BubbleModule] Failed to hook configureAuthor: ${e.message}")
        }
    }

    private fun adjustMarginsForAccessories(view: ViewGroup) {
        val marginLayoutParams = view.layoutParams as MarginLayoutParams
        val topMargin = marginLayoutParams.topMargin

        marginLayoutParams.setMargins(marginLayoutParams.leftMargin, 0, marginLayoutParams.rightMargin, marginLayoutParams.bottomMargin)
        view.layoutParams = marginLayoutParams

        view.setPadding(view.paddingLeft, topMargin + view.paddingTop, view.paddingRight, view.paddingBottom)
    }

    private fun applyRoundedSquareProfilePicture(viewGroup: ViewGroup) {
        viewGroup.children.filterIsInstance<ImageView>().firstOrNull()?.apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View?, outline: Outline?) {
                    outline?.setRoundRect(0, 0, view!!.width, view.height, avatarCurveRadius)
                }
            }
        }
    }

    private fun applyBubbleChat(viewGroup: ViewGroup) {
        val linearLayout = viewGroup.children.filterIsInstance<LinearLayout>().firstOrNull { v -> v.children.any { c -> c.javaClass.simpleName == "ConstraintLayout" } } as? ViewGroup ?: return
        applyBubbleBackground(viewGroup, linearLayout)
    }

    private fun applyBubbleBackground(viewGroup: ViewGroup, linearLayout: ViewGroup) {
        val messageHeader = linearLayout.children.firstOrNull { c -> c.javaClass.simpleName == "ConstraintLayout" } as? ViewGroup ?: return
        val headerVisible = messageHeader.children.firstOrNull()?.visibility != View.GONE

        if (headerVisible) {
            linearLayout.setBubbleBackground(0, start = true, end = false)
            linearLayout.setPadding(PADDING_LARGE, PADDING_MEDIUM, 0, 0)
            linearLayout.translationX = -PADDING_SMALL.toFloat()
        } else {
            linearLayout.setPadding(0, 0, 0, 0)
        }

        viewGroup.children.firstOrNull { i -> i.javaClass.simpleName == "MessageAccessoriesView" }?.let { accessoriesView ->
            setAccessoryBubbleBackground(accessoriesView as ViewGroup, !headerVisible)
        }
    }

    private fun setAccessoryBubbleBackground(accessoriesView: ViewGroup, start: Boolean) {
        val messageAccessoriesDecoration = accessoriesView.javaClass.getDeclaredField("messageAccessoriesDecoration").apply { isAccessible = true }.get(accessoriesView)
        val leftMarginPx = messageAccessoriesDecoration.javaClass.getDeclaredField("leftMarginPx").apply { isAccessible = true }.get(messageAccessoriesDecoration) as Int

        accessoriesView.setBubbleBackground(leftMarginPx, start, true)
        accessoriesView.setPadding(PADDING_LARGE, if (start) PADDING_MEDIUM else 0, PADDING_SMALL, PADDING_MEDIUM)
        accessoriesView.translationX = -PADDING_SMALL.toFloat()
    }

    private fun ViewGroup.setBubbleBackground(leftMargin: Int, start: Boolean, end: Boolean) {
        val bubble = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(chatBubbleColor)
            cornerRadii = FloatArray(8) { i ->
                when {
                    start && end -> bubbleCurveRadius
                    start && i < 4 -> bubbleCurveRadius
                    !start && i >= 4 -> bubbleCurveRadius
                    else -> 0f
                }
            }
        }
        background = InsetDrawable(bubble, leftMargin, 0, PADDING_SMALL, 0)
    }

    fun hookBubbles() {
    }

    fun unhookBubbles() {
        configureAccessoriesMarginHook?.unhook()
        configureAccessoriesMarginHook = null
        configureAuthorHook?.unhook()
        configureAuthorHook = null
    }

    fun configure(avatarRadius: Float? = null, bubbleRadius: Float? = null, bubbleColor: Int? = null) {
        avatarRadius?.let { avatarCurveRadius = it }
        bubbleRadius?.let { bubbleCurveRadius = it }
        bubbleColor?.let { chatBubbleColor = it }
    }
}
