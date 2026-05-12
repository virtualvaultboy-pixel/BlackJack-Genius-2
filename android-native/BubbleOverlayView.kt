package studio.deponchy.bjgenius

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bulle flottante BJ Genius — v1.3
 *
 * Layout :
 *
 *        ┌───────────┐
 *        │  TIRER    │   ← Rectangle decision (au-dessus)
 *        └───────────┘
 *            ⭕              ← Bulle centrale BJ
 *   🎤 ───────────── 📷       ← Sous-bulles (expanded uniquement)
 *            ✕                ← Close (en bas, expanded uniquement)
 *
 * Etats :
 *   - Collapsed : bulle centrale + rectangle decision si actif
 *   - Expanded  : sous-bulles visibles
 *   - Drag      : suit le doigt, snap au bord au relachement
 */
@SuppressLint("ViewConstructor")
class BubbleOverlayView(context: Context) : View(context) {

    // ── Callbacks externes ──────────────────────────────────────────
    var windowManager: WindowManager? = null
    var onBubbleTap: (() -> Unit)? = null
    var onMicTap: (() -> Unit)? = null
    var onScanTap: (() -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null

    // ── Etat interne ────────────────────────────────────────────────
    private var expanded = false
    private var decisionText = ""
    private var decisionColor = Color.parseColor("#c9a84c")
    private var micActive = false

    // ── Geometrie ───────────────────────────────────────────────────
    private val density = resources.displayMetrics.density
    private val bubbleSize = (56 * density).toInt()
    private val subBubbleSize = (42 * density).toInt()
    private val expandRadius = 70 * density
    private val decisionHeight = (32 * density).toInt()
    private val decisionMinWidth = (110 * density).toInt()
    private val decisionGap = (8 * density).toInt()

    // ── Peintures ───────────────────────────────────────────────────
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#c9a84c"); style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60000000"); style = Paint.Style.FILL
    }
    private val subBubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1a3322"); style = Paint.Style.FILL
    }
    private val subBubbleActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ef4444"); style = Paint.Style.FILL
    }
    private val subBubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#c9a84c"); style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1a1000")
        textSize = 16 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val subIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18 * density
        textAlign = Paint.Align.CENTER
    }
    private val closeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#dc2626"); style = Paint.Style.FILL
    }
    private val closeXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2.5f * density; strokeCap = Paint.Cap.ROUND
    }
    private val decisionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#c9a84c"); style = Paint.Style.FILL
    }
    private val decisionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1a1000")
        textSize = 14 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // ── Drag state ──────────────────────────────────────────────────
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasDragged = false
    private var touchDownTime = 0L
    private val touchSlop = 12 * density
    private val tapMaxDuration = 250L

    private enum class HitZone { NONE, BUBBLE, MIC, SCAN, CLOSE, DECISION }
    private var hitZone = HitZone.NONE

    // ── Dimensions de la View (fixes, pour ne pas redimensionner sans cesse)
    // La View englobe : rectangle decision (haut) + bulle (centre) +
    // sous-bulles laterales + close (bas).
    private val viewWidth: Int =
        (expandRadius * 2 + subBubbleSize + 12 * density).toInt()
    private val viewHeight: Int =
        (decisionHeight + decisionGap + bubbleSize +
         expandRadius.toInt() + subBubbleSize + (12 * density).toInt())

    // Coordonnees pre-calculees (s'adaptent a la taille fixe de la View)
    private val centerX get() = viewWidth / 2f
    private val centerY get() = decisionHeight + decisionGap + bubbleSize / 2f
    private val micCenterX get() = centerX - expandRadius
    private val micCenterY get() = centerY
    private val scanCenterX get() = centerX + expandRadius
    private val scanCenterY get() = centerY
    private val closeCenterX get() = centerX
    private val closeCenterY get() = centerY + expandRadius

    init {
        layoutParams = WindowManager.LayoutParams(viewWidth, viewHeight)
    }

    // ── API publique pour mise a jour depuis le Service ─────────────
    fun updateDecision(text: String, colorHex: String?) {
        decisionText = text
        if (!colorHex.isNullOrEmpty()) {
            try { decisionColor = Color.parseColor(colorHex) } catch (_: Exception) {}
        }
        decisionBgPaint.color = decisionColor
        invalidate()
    }

    fun updateMicState(active: Boolean) {
        micActive = active
        invalidate()
    }

    fun setExpanded(v: Boolean) {
        if (expanded != v) { expanded = v; invalidate() }
    }

    fun toggleExpanded() = setExpanded(!expanded)

    // ── Dessin ──────────────────────────────────────────────────────
    private fun decisionBoxWidth(): Float {
        val measured = if (decisionText.isNotEmpty())
            decisionTextPaint.measureText(decisionText) + 24 * density
        else decisionMinWidth.toFloat()
        return max(decisionMinWidth.toFloat(), measured)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) Rectangle decision (au-dessus)
        if (decisionText.isNotEmpty()) {
            val w = decisionBoxWidth()
            val rect = RectF(
                centerX - w / 2f, 0f,
                centerX + w / 2f, decisionHeight.toFloat()
            )
            val shadowRect = RectF(rect).apply { offset(1.5f * density, 2 * density) }
            canvas.drawRoundRect(shadowRect, 10 * density, 10 * density, shadowPaint)
            canvas.drawRoundRect(rect, 10 * density, 10 * density, decisionBgPaint)
            val ty = rect.centerY() -
                (decisionTextPaint.descent() + decisionTextPaint.ascent()) / 2
            canvas.drawText(decisionText, rect.centerX(), ty, decisionTextPaint)
        }

        // 2) Sous-bulles si expanded
        if (expanded) {
            drawSubBubble(canvas, micCenterX, micCenterY, "\uD83C\uDFA4", micActive)  // 🎤
            drawSubBubble(canvas, scanCenterX, scanCenterY, "\uD83D\uDCF7", false)    // 📷
            drawCloseButton(canvas, closeCenterX, closeCenterY)
        }

        // 3) Bulle principale (toujours visible)
        val radius = bubbleSize / 2f
        canvas.drawCircle(centerX + 2 * density, centerY + 3 * density,
                          radius + 1 * density, shadowPaint)
        canvas.drawCircle(centerX, centerY, radius, bgPaint)
        val ty = centerY - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("BJ", centerX, ty, textPaint)
    }

    private fun drawSubBubble(canvas: Canvas, cx: Float, cy: Float,
                              icon: String, active: Boolean) {
        val r = subBubbleSize / 2f
        canvas.drawCircle(cx + 1.5f * density, cy + 2 * density, r, shadowPaint)
        canvas.drawCircle(cx, cy, r,
            if (active) subBubbleActivePaint else subBubbleBgPaint)
        canvas.drawCircle(cx, cy, r, subBubbleBorderPaint)
        val ty = cy - (subIconPaint.descent() + subIconPaint.ascent()) / 2
        canvas.drawText(icon, cx, ty, subIconPaint)
    }

    private fun drawCloseButton(canvas: Canvas, cx: Float, cy: Float) {
        val r = subBubbleSize / 2f
        canvas.drawCircle(cx + 1.5f * density, cy + 2 * density, r, shadowPaint)
        canvas.drawCircle(cx, cy, r, closeBgPaint)
        val xSize = r * 0.4f
        canvas.drawLine(cx - xSize, cy - xSize, cx + xSize, cy + xSize, closeXPaint)
        canvas.drawLine(cx - xSize, cy + xSize, cx + xSize, cy - xSize, closeXPaint)
    }

    // ── Hit-test ───────────────────────────────────────────────────
    private fun hitTest(x: Float, y: Float): HitZone {
        // Rectangle decision (toujours actif s'il est visible)
        if (decisionText.isNotEmpty()) {
            val w = decisionBoxWidth()
            if (x in (centerX - w / 2f)..(centerX + w / 2f) &&
                y in 0f..decisionHeight.toFloat()) {
                return HitZone.DECISION
            }
        }
        // Bulle centrale (toujours)
        val mainR = bubbleSize / 2f
        if (distSq(x, y, centerX, centerY) <= mainR * mainR) {
            return HitZone.BUBBLE
        }
        // Sous-bulles si expanded
        if (expanded) {
            val subR = subBubbleSize / 2f
            val subR2 = subR * subR
            if (distSq(x, y, micCenterX, micCenterY) <= subR2) return HitZone.MIC
            if (distSq(x, y, scanCenterX, scanCenterY) <= subR2) return HitZone.SCAN
            if (distSq(x, y, closeCenterX, closeCenterY) <= subR2) return HitZone.CLOSE
        }
        return HitZone.NONE
    }

    private fun distSq(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2; return dx * dx + dy * dy
    }

    // ── Touch ──────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams as? WindowManager.LayoutParams ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                hitZone = hitTest(event.x, event.y)
                if (hitZone == HitZone.NONE) return false
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                hasDragged = false
                touchDownTime = System.currentTimeMillis()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (hitZone == HitZone.NONE) return false
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!hasDragged && sqrt(dx * dx + dy * dy) > touchSlop) {
                    hasDragged = true
                }
                // Drag uniquement depuis BUBBLE ou DECISION (les sous-bulles
                // sont fixes par rapport au centre, pas de drag depuis elles)
                if (hasDragged && (hitZone == HitZone.BUBBLE || hitZone == HitZone.DECISION)) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    val sw = resources.displayMetrics.widthPixels
                    val sh = resources.displayMetrics.heightPixels
                    // Garder la bulle centrale visible
                    val minX = -(viewWidth - bubbleSize) / 2
                    val maxX = sw - (viewWidth + bubbleSize) / 2
                    val minY = -decisionHeight
                    val maxY = sh - bubbleSize - 10
                    params.x = max(minX, min(maxX, params.x))
                    params.y = max(minY, min(maxY, params.y))
                    try {
                        windowManager?.updateViewLayout(this, params)
                    } catch (e: Exception) {
                        Log.w("BubbleView", "updateViewLayout failed", e)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (hitZone == HitZone.NONE) return false
                val duration = System.currentTimeMillis() - touchDownTime
                val isTap = !hasDragged && duration < tapMaxDuration
                if (isTap) {
                    when (hitZone) {
                        HitZone.BUBBLE -> {
                            toggleExpanded()
                            onBubbleTap?.invoke()
                        }
                        HitZone.MIC -> onMicTap?.invoke()
                        HitZone.SCAN -> onScanTap?.invoke()
                        HitZone.CLOSE -> onCloseRequested?.invoke()
                        HitZone.DECISION -> toggleExpanded()
                        HitZone.NONE -> {}
                    }
                } else if (hasDragged &&
                           (hitZone == HitZone.BUBBLE || hitZone == HitZone.DECISION)) {
                    snapToEdge(params)
                }
                hitZone = HitZone.NONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                hitZone = HitZone.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Snap horizontal au bord le plus proche apres un drag */
    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val currentCenterX = params.x + viewWidth / 2
        val targetX = if (currentCenterX < screenW / 2) {
            -(viewWidth - bubbleSize) / 2 + (8 * density).toInt()
        } else {
            screenW - (viewWidth + bubbleSize) / 2 - (8 * density).toInt()
        }
        val anim = android.animation.ValueAnimator.ofInt(params.x, targetX)
        anim.duration = 200
        anim.addUpdateListener { va ->
            params.x = va.animatedValue as Int
            try {
                windowManager?.updateViewLayout(this, params)
            } catch (_: Exception) {}
        }
        anim.start()
    }
}
