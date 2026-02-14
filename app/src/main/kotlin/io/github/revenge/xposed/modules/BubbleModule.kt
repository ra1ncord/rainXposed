package io.github.revenge.xposed.modules

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
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

object BubbleModule : Module() {
    lateinit var packageParam: XC_LoadPackage.LoadPackageParam

    private var configureAccessoriesMarginHook: XC_MethodHook.Unhook? = null
    private var configureAuthorHook: XC_MethodHook.Unhook? = null

    private var avatarCurveRadius = DEFAULT_AVATAR_CURVE_RADIUS
    private var bubbleCurveRadius = DEFAULT_BUBBLE_CURVE_RADIUS
    private var chatBubbleColor = DEFAULT_BUBBLE_COLOR
    
    private var moduleContext: Context? = null

    private const val DEFAULT_AVATAR_CURVE_RADIUS = 12f
    private const val DEFAULT_BUBBLE_CURVE_RADIUS = 12f
    private const val DEFAULT_BUBBLE_COLOR = 0x66000000

    private fun dpToPx(dp: Int): Int {
        val ctx = moduleContext ?: return dp
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            ctx.resources.displayMetrics
        ).toInt()
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        this.packageParam = packageParam
        hookMessageView()
    }

    override fun onContext(context: Context) {
        this.moduleContext = context
    }

    fun hookBubbles() {
        hookMessageView()
    }

    fun unhookBubbles() {
        configureAccessoriesMarginHook?.unhook()
        configureAccessoriesMarginHook = null

        configureAuthorHook?.unhook()
        configureAuthorHook = null
    }

    fun configure(
        avatar: Float?,
        bubble: Float?,
        bubbleColor: Int?
    ) {
        avatarCurveRadius = avatar?.let { dpToPx(it.toInt()).toFloat() } ?: avatarCurveRadius
        bubbleCurveRadius = bubble?.let { dpToPx(it.toInt()).toFloat() } ?: bubbleCurveRadius
        chatBubbleColor = bubbleColor ?: chatBubbleColor
    }

    private fun hookMessageView() {
        try {
            val MessageViewClass = packageParam.classLoader.loadClass("com.discord.chat.presentation.message.MessageView")
            
            val configureAccessoriesMarginMethod = MessageViewClass.methods.firstOrNull { it.name == "configureAccessoriesMargin" }
            if (configureAccessoriesMarginMethod != null) {
                configureAccessoriesMarginHook = XposedBridge.hookMethod(configureAccessoriesMarginMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val instance = param.thisObject
                            val bindingField = XposedHelpers.findField(MessageViewClass, "binding")
                            bindingField.isAccessible = true
                            val binding = bindingField.get(instance)
                            val accessoriesViewField = XposedHelpers.findField(binding.javaClass, "accessoriesView")
                            accessoriesViewField.isAccessible = true
                            val accessoriesView = accessoriesViewField.get(binding) as ViewGroup
                            adjustMarginsForAccessories(accessoriesView)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                })
            }

            val configureAuthorMethod = MessageViewClass.methods.firstOrNull { it.name == "configureAuthor" }
            if (configureAuthorMethod != null) {
                configureAuthorHook = XposedBridge.hookMethod(configureAuthorMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as ViewGroup
                            applyRoundedSquareProfilePicture(view)
                            applyBubbleChat(view)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun adjustMarginsForAccessories(view: ViewGroup) {
        val marginLayoutParams = view.layoutParams as MarginLayoutParams
        val topMargin = marginLayoutParams.topMargin

        marginLayoutParams.setMargins(
            marginLayoutParams.leftMargin,
            0,
            marginLayoutParams.rightMargin,
            marginLayoutParams.bottomMargin
        )
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
        val linearLayout = viewGroup.children.filterIsInstance<LinearLayout>()
            .firstOrNull { v -> v.children.any { c -> c.javaClass.simpleName == "ConstraintLayout" } } as? ViewGroup
            ?: return
        applyBubbleBackground(viewGroup, linearLayout)
    }

    private fun applyBubbleBackground(viewGroup: ViewGroup, linearLayout: ViewGroup) {
        val messageHeader =
            linearLayout.children.firstOrNull { c -> c.javaClass.simpleName == "ConstraintLayout" } as? ViewGroup
                ?: return
        val headerVisible = messageHeader.children.firstOrNull()?.visibility != View.GONE

        val paddingSmall = dpToPx(6)
        val paddingMedium = dpToPx(8)
        val paddingLarge = dpToPx(12)

        if (headerVisible) {
            linearLayout.setBubbleBackground(0, start = true, end = false, paddingSmall, paddingMedium, paddingLarge)
            linearLayout.setPadding(paddingLarge, paddingMedium, 0, 0)
            linearLayout.translationX = -paddingSmall.toFloat()
        } else {
            linearLayout.setPadding(0, 0, 0, 0)
        }

        viewGroup.children.firstOrNull { i -> i.javaClass.simpleName == "MessageAccessoriesView" }
            ?.let { accessoriesView ->
                setAccessoryBubbleBackground(accessoriesView as ViewGroup, !headerVisible, paddingSmall, paddingMedium, paddingLarge)
            }
    }

    private fun setAccessoryBubbleBackground(accessoriesView: ViewGroup, start: Boolean, paddingSmall: Int, paddingMedium: Int, paddingLarge: Int) {
        val messageAccessoriesDecorationField = XposedHelpers.findField(accessoriesView.javaClass, "messageAccessoriesDecoration")
        messageAccessoriesDecorationField.isAccessible = true
        val messageAccessoriesDecoration = messageAccessoriesDecorationField.get(accessoriesView)
        
        val leftMarginPxField = XposedHelpers.findField(messageAccessoriesDecoration.javaClass, "leftMarginPx")
        leftMarginPxField.isAccessible = true
        val leftMarginPx = leftMarginPxField.get(messageAccessoriesDecoration) as Int

        accessoriesView.setBubbleBackground(leftMarginPx, start, true, paddingSmall, paddingMedium, paddingLarge)
        accessoriesView.setPadding(paddingLarge, if (start) paddingMedium else 0, paddingSmall, paddingMedium)
        accessoriesView.translationX = -paddingSmall.toFloat()
    }

    private fun ViewGroup.setBubbleBackground(leftMargin: Int, start: Boolean, end: Boolean, paddingSmall: Int, paddingMedium: Int, paddingLarge: Int) {
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
        background = InsetDrawable(bubble, leftMargin, 0, paddingSmall, 0)
    }
}
