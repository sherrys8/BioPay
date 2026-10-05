/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.payment

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.util.dp
import io.github.kiriashi.biopay.runtime.AppRuntime

/**
 * Authentication waits for one explicit tap so the payment screen stays switchable.
 * The affordances live in the window overlay and never modify the host view hierarchy.
 */
internal object ContinuePrompt {
    private var active: Prompt? = null

    fun show(
        state: AppRuntime, keyboard: ViewGroup, sessionId: Long, abandoned: Boolean,
        onContinue: () -> Unit, onManualEntry: () -> Unit
    ): Boolean {
        // A callback replayed by an authentication attempt that was already finished must not
        // clear the affordance a newer session just drew.
        active?.takeIf { it.sessionId <= sessionId }?.close()
        val host = keyboard.rootView as? ViewGroup ?: return false
        val prompt = Prompt(state, host, keyboard, sessionId, abandoned, onContinue, onManualEntry) { closed ->
            if (active === closed) active = null
        }
        active = prompt
        prompt.attach()
        ModuleLog.d { "continue prompt shown: app=${state.adapter.app}, session=$sessionId, abandoned=$abandoned" }
        return true
    }

    /** Mirrors a tap on the primary affordance; used by the volume key shortcut. */
    fun triggerNow(): Boolean = active?.fireContinue() == true

    fun dismiss() {
        active?.close()
        active = null
    }

    fun blocksTouch(root: View, event: MotionEvent): Boolean = active?.touches(root, event) == true

    private class Prompt(
        private val state: AppRuntime,
        private val host: ViewGroup,
        private val keyboard: ViewGroup,
        val sessionId: Long,
        private val abandoned: Boolean,
        private val onContinue: () -> Unit,
        private val onManualEntry: () -> Unit,
        private val onClose: (Prompt) -> Unit
    ) : Drawable(), ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

        private enum class Region { NONE, PRIMARY, SECONDARY }

        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(31, 31, 31) }
        private val primaryLabel = labelPaint(16f)
        private val secondaryLabel = labelPaint(13f)
        private val primary = RectF()
        private val secondary = RectF()
        private val drawnPrimary = RectF()
        private val drawnSecondary = RectF()
        private val keyboardRect = Rect()
        private val location = IntArray(2)
        private val observer = host.viewTreeObserver
        private val primaryRadius = host.dp(BUTTON_HEIGHT_DP / 2).toFloat()
        private val secondaryRadius = host.dp(ENTRY_HEIGHT_DP / 2).toFloat()
        private var closed = false
        private var pressed = Region.NONE

        private fun labelPaint(sizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sizeSp, host.resources.displayMetrics
            )
        }

        fun attach() {
            host.overlay.add(this)
            observer.addOnPreDrawListener(this)
            host.addOnAttachStateChangeListener(this)
            keyboard.addOnAttachStateChangeListener(this)
        }

        fun fireContinue(): Boolean = fire(onContinue)

        private fun fire(action: () -> Unit): Boolean {
            if (closed || !isCurrent()) return false
            close()
            action()
            return true
        }

        fun close() {
            if (closed) return
            closed = true
            pressed = Region.NONE
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            host.overlay.remove(this)
            host.removeOnAttachStateChangeListener(this)
            keyboard.removeOnAttachStateChangeListener(this)
            primary.setEmpty()
            secondary.setEmpty()
            onClose(this)
        }

        fun touches(root: View, event: MotionEvent): Boolean {
            if (closed || root !== host.rootView || !isCurrent()) return false
            host.getLocationOnScreen(location)
            val action = event.actionMasked
            val inside = regionAt(event.rawX - location[0], event.rawY - location[1])
            if (action == MotionEvent.ACTION_DOWN) pressed = inside
            val consume = pressed != Region.NONE
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                val started = pressed
                pressed = Region.NONE
                if (action == MotionEvent.ACTION_UP && started == inside && inside != Region.NONE) {
                    fire(if (inside == Region.SECONDARY) onManualEntry else onContinue)
                }
            }
            return consume
        }

        /** Only the two affordances are consumed; every other touch reaches the host app. */
        private fun regionAt(x: Float, y: Float): Region = when {
            abandoned && secondary.contains(x, y) -> Region.SECONDARY
            primary.contains(x, y) -> Region.PRIMARY
            else -> Region.NONE
        }

        override fun onPreDraw(): Boolean {
            if (closed) return true
            if (!isCurrent() || !host.isAttachedToWindow || !keyboard.isAttachedToWindow) {
                close()
                return true
            }
            updateBounds()
            if (drawnPrimary != primary || drawnSecondary != secondary) {
                drawnPrimary.set(primary)
                drawnSecondary.set(secondary)
                invalidateSelf()
            }
            return true
        }

        private fun isCurrent(): Boolean = !state.isClosed &&
            state.session.isCurrentSession(sessionId) &&
            state.session.getCurrentKeyboardView() === keyboard

        private fun updateBounds() {
            setBounds(0, 0, host.width, host.height)
            primary.setEmpty()
            secondary.setEmpty()
            host.getLocationOnScreen(location)
            val margin = host.dp(MARGIN_DP).toFloat()
            val height = host.dp(BUTTON_HEIGHT_DP).toFloat()
            val width = host.width - margin * 2f
            if (width <= 0f || host.height <= 0) return
            val keypadTop = if (keyboard.getGlobalVisibleRect(keyboardRect)) {
                keyboardRect.top - location[1]
            } else {
                host.height
            }
            val aboveKeypad = keypadTop - host.dp(GAP_DP) - height
            val top = if (aboveKeypad >= margin) {
                aboveKeypad
            } else {
                // Sheets whose keypad is the whole window leave no band above it.
                (keypadTop - height).coerceAtLeast(margin) / 2f
            }
            primary.set(margin, top, margin + width, top + height)
            if (!abandoned) return
            val entryHeight = host.dp(ENTRY_HEIGHT_DP).toFloat()
            val entryTop = host.dp(ENTRY_TOP_DP).toFloat()
            val right = host.width - margin
            val left = (right - secondaryLabel.measureText(MANUAL_LABEL) -
                host.dp(ENTRY_PADDING_X_DP) * 2f).coerceAtLeast(margin)
            secondary.set(left, entryTop, right, entryTop + entryHeight)
        }

        override fun draw(canvas: Canvas) {
            if (!primary.isEmpty) drawButton(canvas, primary, CONTINUE_LABEL, primaryLabel, primaryRadius)
            if (abandoned && !secondary.isEmpty) {
                drawButton(canvas, secondary, MANUAL_LABEL, secondaryLabel, secondaryRadius)
            }
        }

        private fun drawButton(
            canvas: Canvas, box: RectF, text: String, label: Paint, radius: Float
        ) {
            canvas.drawRoundRect(box, radius, radius, background)
            val centerY = (box.top + box.bottom) / 2f
            canvas.drawText(text, box.centerX(), centerY - (label.ascent() + label.descent()) / 2f, label)
        }

        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) = close()

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private companion object {
            const val CONTINUE_LABEL = "继续验证"
            const val MANUAL_LABEL = "输入密码"
            const val MARGIN_DP = 24
            const val BUTTON_HEIGHT_DP = 44
            const val GAP_DP = 12
            const val ENTRY_TOP_DP = 10
            const val ENTRY_HEIGHT_DP = 32
            const val ENTRY_PADDING_X_DP = 14
        }
    }
}
