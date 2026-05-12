package studio.deponchy.bjgenius

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bulle flottante BJ Genius — vue qui dessine un cercle dore avec le logo "BJ"
 * et un petit X pour fermer. Draggable au doigt.
 *
 * v1.2 : version minimale (cercle nu). Les futures versions ajoutent l'expand,
 * la decision affichee, le mic en background, etc.
 */
@SuppressLint("ViewConstructor")
class BubbleOverlayView(context: Context) : View(context) {

    var windowManager: WindowManager? = null
    var onCloseRequested: (() -> Unit)? = null

    // ── Geometrie ──────────────────────────────────────────────────
    private val density = resources.displayMetrics.density
    private val bubbleSize = (56 * density).toInt() // 56dp = taille FAB Material
    private val closeButtonRadius = 9 * density
    private val closeButtonOffset = 16 * density // distance du centre vers haut-droit

    // ── Peintures ──────────────────────────────────────────────────
    // Fond dore (style BJ Genius)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#c9a84c")
        style = Paint.Style.FILL
    }
    // Ombre du cercle pour effet "flottant" — sera dessinee comme cercle gris translucide
    // derriere le cercle dore. Plus simple que setShadowLayer qui necessite setLayerType
    // SOFTWARE et pose parfois des soucis en overlay window.
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60000000")
        style = Paint.Style.FILL
    }
    // Texte "BJ" centre dans la bulle
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1a1000")
        textSize = 16 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    // Bouton fermer (cercle rouge avec X blanc)
    private val closeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ef4444")
        style = Paint.Style.FILL
    }
    private val closeXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2 * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // ── Drag state ─────────────────────────────────────────────────
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasDragged = false
    private var touchDownTime = 0L
    private val touchSlop = 10 * density // mouvement min pour considerer drag
    private val tapMaxDuration = 200L // ms max pour un tap simple

    init {
        // Taille fixe : carre qui contient la bulle + zone pour le bouton fermer
        val totalSize = bubbleSize + (closeButtonOffset * 2).toInt()
        layoutParams = WindowManager.LayoutParams(totalSize, totalSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = bubbleSize / 2f

        // Cercle d'ombre (decale en bas-droite, plus grand, translucide)
        canvas.drawCircle(cx + 2 * density, cy + 3 * density, radius + 1 * density, shadowPaint)
        // Cercle principal
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Texte "BJ" au centre (avec leger offset pour centrage visuel)
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("BJ", cx, textY, textPaint)

        // Bouton fermer (haut-droite du cercle)
        val closeX = cx + closeButtonOffset
        val closeY = cy - closeButtonOffset
        canvas.drawCircle(closeX, closeY, closeButtonRadius, closeBgPaint)
        val xSize = closeButtonRadius * 0.45f
        canvas.drawLine(closeX - xSize, closeY - xSize, closeX + xSize, closeY + xSize, closeXPaint)
        canvas.drawLine(closeX - xSize, closeY + xSize, closeX + xSize, closeY - xSize, closeXPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams as? WindowManager.LayoutParams ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // v1.2.7 — FIX CRITIQUE : hit-test precis avant de consommer.
                // Avant, on retournait true des le DOWN sans verifier ou le
                // doigt etait pose. Resultat : la zone carree transparente
                // autour du cercle absorbait toutes les touches et bloquait
                // completement l'app du dessous (BJ Genius, casinos, etc.).
                // Maintenant : on ne consomme que si le doigt touche soit le
                // cercle bulle, soit le bouton fermer. Sinon return false ->
                // la touche traverse l'overlay vers la fenetre du dessous.
                val cx = width / 2f
                val cy = height / 2f
                val radius = bubbleSize / 2f
                val closeX = cx + closeButtonOffset
                val closeY = cy - closeButtonOffset

                // Distance au centre du cercle bulle
                val distBubble2 = (event.x - cx) * (event.x - cx) +
                                  (event.y - cy) * (event.y - cy)
                val touchOnBubble = distBubble2 <= radius * radius

                // Distance au centre du bouton fermer
                val distClose2 = (event.x - closeX) * (event.x - closeX) +
                                 (event.y - closeY) * (event.y - closeY)
                val touchOnClose = distClose2 <= (closeButtonRadius * 1.4f) * (closeButtonRadius * 1.4f)

                if (!touchOnBubble && !touchOnClose) {
                    // Touche en dehors des zones interactives : on ne consomme
                    // PAS, la touche passe a la fenetre du dessous.
                    return false
                }

                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                hasDragged = false
                touchDownTime = System.currentTimeMillis()

                if (touchOnClose) {
                    // On retient ce flag et on declenche le close au UP si pas drag
                    tag = "close_pressed"
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!hasDragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    hasDragged = true
                    tag = null // annule le close_pressed si on draggue
                }
                if (hasDragged) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    // Constrain a l'ecran (laisse depasser un peu pour qu'on
                    // puisse toujours rattraper la bulle)
                    val screenW = resources.displayMetrics.widthPixels
                    val screenH = resources.displayMetrics.heightPixels
                    val minMargin = -bubbleSize / 3
                    params.x = max(minMargin, min(screenW - width + bubbleSize / 3, params.x))
                    params.y = max(0, min(screenH - height, params.y))
                    try {
                        windowManager?.updateViewLayout(this, params)
                    } catch (e: Exception) {
                        Log.w("BubbleView", "updateViewLayout failed", e)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - touchDownTime
                if (!hasDragged && duration < tapMaxDuration) {
                    if (tag == "close_pressed") {
                        Log.d("BubbleView", "Close tapped")
                        onCloseRequested?.invoke()
                    } else {
                        // v1.2 : tap sur le centre = rien pour l'instant
                        // (sera expand/collapse en v1.3)
                        Log.d("BubbleView", "Bubble tapped (no action yet in v1.2)")
                    }
                } else if (hasDragged) {
                    // Snap au bord le plus proche (gauche/droite)
                    snapToEdge(params)
                }
                tag = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Snap horizontal au bord le plus proche apres un drag */
    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val centerX = params.x + width / 2
        val targetX = if (centerX < screenW / 2) {
            -(width - bubbleSize) / 2 + (8 * density).toInt() // colle a gauche
        } else {
            screenW - width + (width - bubbleSize) / 2 - (8 * density).toInt() // colle a droite
        }
        // Animation simple via ValueAnimator
        val start = params.x
        val anim = android.animation.ValueAnimator.ofInt(start, targetX)
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
