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
 * Bulle flottante BJ Genius — v1.3.2
 *
 * Refonte majeure :
 *  - View REDIMENSIONNABLE selon l'etat. Quand collapsed (juste la bulle + le
 *    rectangle decision), la View ne fait que la taille minimale necessaire,
 *    ce qui evite les "zones mortes" tactiles qui consomment les events
 *    autour de la bulle, et permet a l'app du dessous (notamment au swipe
 *    gauche de BJ Genius) de fonctionner normalement.
 *  - SOUS-BULLES ADAPTATIVES : leur position depend de la position de la BJ
 *    sur l'ecran. Si BJ est dans la moitie droite, les sous-bulles s'ouvrent
 *    vers la gauche. Si BJ est en bas, le close va au-dessus.
 *  - PRESERVATION DE LA POSITION VISUELLE de la bulle BJ entre les etats
 *    (collapsed <-> expanded) : on ajuste params.x et params.y de la fenetre
 *    pour que le centre du cercle BJ ne bouge pas a l'ecran.
 */
@SuppressLint("ViewConstructor")
class BubbleOverlayView(context: Context) : View(context) {

    // ── Callbacks externes ──────────────────────────────────────────
    var windowManager: WindowManager? = null
    var onBubbleTap: (() -> Unit)? = null
    var onMicTap: (() -> Unit)? = null
    var onScanTap: (() -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null

    // ── Etat ────────────────────────────────────────────────────────
    private var expanded = false
    private var decisionText = ""
    private var decisionColor = Color.parseColor("#c9a84c")
    private var micActive = false

    // ── Geometrie ───────────────────────────────────────────────────
    private val density = resources.displayMetrics.density
    private val bubbleSize = (56 * density).toInt()
    private val subBubbleSize = (42 * density).toInt()
    // Distance entre centre bulle et centre sous-bulle
    private val expandDist = 70 * density
    private val decisionHeight = (32 * density).toInt()
    private val decisionMinWidth = (110 * density).toInt()
    private val decisionGap = (8 * density).toInt()
    // Marge de securite autour des elements pour la View
    private val padding = (6 * density).toInt()

    // ── Adaptation a la position de la BJ sur l'ecran ───────────────
    // Mis a jour a chaque expand. Determine la direction des sous-bulles.
    private var subBubblesLeft = false   // sous-bulles a gauche de BJ ?
    private var closeAbove = false       // close au-dessus de BJ ?

    // ── Centres calcules dans recomputeLayout() ─────────────────────
    private var bubbleCx = 0f
    private var bubbleCy = 0f
    private var micCx = 0f; private var micCy = 0f
    private var scanCx = 0f; private var scanCy = 0f
    private var closeCx = 0f; private var closeCy = 0f
    private var decisionTopY = 0f

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

    // ── Dimensions courantes de la View (varient selon collapsed/expanded)
    private var currentViewW = 0
    private var currentViewH = 0

    init {
        // Layout initial : juste la bulle (collapsed sans decision)
        recomputeLayout()
    }

    // ── API publique ────────────────────────────────────────────────
    fun updateDecision(text: String, colorHex: String?) {
        decisionText = text
        if (!colorHex.isNullOrEmpty()) {
            try { decisionColor = Color.parseColor(colorHex) } catch (_: Exception) {}
        }
        decisionBgPaint.color = decisionColor
        // Le rectangle decision affecte la taille de la View, donc recompute.
        applyResize()
    }

    fun updateMicState(active: Boolean) {
        micActive = active
        invalidate()
    }

    fun setExpanded(v: Boolean) {
        if (expanded != v) {
            expanded = v
            applyResize()
        }
    }

    fun toggleExpanded() = setExpanded(!expanded)

    // ── Recalcul des dimensions et coordonnees ──────────────────────
    /**
     * Recalcule les dimensions de la View et la position des elements dedans
     * en fonction de l'etat actuel (collapsed / expanded) et de la position
     * sur l'ecran (pour les sous-bulles adaptatives).
     *
     * Ne touche PAS au layoutParams ni au WindowManager. Pour appliquer
     * effectivement le changement de taille, appeler applyResize() qui se
     * charge de recompute + updateViewLayout (+ ajustement de la position
     * de la fenetre pour preserver la position visuelle de BJ).
     */
    private fun recomputeLayout() {
        val mainR = bubbleSize / 2
        val subR = subBubbleSize / 2
        val hasDecision = decisionText.isNotEmpty()

        // Determine la direction des sous-bulles selon la position de la BJ
        // sur l'ecran. On le fait au moment du recompute pour que le passage
        // collapsed -> expanded adapte la direction au cote ou se trouve la BJ.
        val lp = layoutParams as? WindowManager.LayoutParams
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        if (lp != null && currentViewW > 0) {
            val bjScreenX = lp.x + bubbleCx.toInt()
            val bjScreenY = lp.y + bubbleCy.toInt()
            subBubblesLeft = bjScreenX > screenW / 2
            // Close au-dessus si la BJ est dans le bas de l'ecran (< 1/3 du bas)
            closeAbove = bjScreenY > screenH * 2 / 3
        } else {
            // Premier calcul : on suppose en haut a droite (position initiale)
            subBubblesLeft = true
            closeAbove = false
        }

        if (!expanded) {
            // ─── COLLAPSED : juste la bulle + decision si present ─────
            // Largeur = max(bulle, decision si visible)
            val w = if (hasDecision) {
                max(bubbleSize, decisionBoxWidth().toInt()) + padding * 2
            } else {
                bubbleSize + padding * 2
            }
            // Hauteur = decision + gap + bulle (si decision) sinon juste bulle
            val h = if (hasDecision) {
                decisionHeight + decisionGap + bubbleSize + padding * 2
            } else {
                bubbleSize + padding * 2
            }
            currentViewW = w
            currentViewH = h
            // Centre de la bulle = centre horizontal, sous le rectangle decision
            bubbleCx = w / 2f
            bubbleCy = if (hasDecision) {
                (padding + decisionHeight + decisionGap + mainR).toFloat()
            } else {
                (padding + mainR).toFloat()
            }
            decisionTopY = padding.toFloat()
        } else {
            // ─── EXPANDED : bulle + sous-bulles + decision (si present) ─
            // On a 2 sous-bulles laterales (mic, scan) et 1 close au-dessus
            // ou en dessous selon closeAbove.
            // La direction "lateral" (mic & scan) depend de subBubblesLeft.
            //
            // Pour calculer l'emprise :
            //   - cote lateral : il y a TOUJOURS 1 sous-bulle d'un cote et 1
            //     de l'autre (mic & scan sont opposees). Donc la largeur est
            //     2 * expandDist + subBubbleSize + paddings.
            //   - cote vertical : si closeAbove, on a le close au-dessus, donc
            //     hauteur = expandDist + subR + decisionHeight + gap + mainR
            //              + mainR + padding
            //     Si !closeAbove (close en bas, defaut) : hauteur = decision
            //     + gap + mainR + mainR + expandDist + subR + padding
            val w = (expandDist * 2 + subBubbleSize + padding * 2).toInt()

            // Calcul hauteur selon decision presente + close above/below
            val topSpace = if (hasDecision) decisionHeight + decisionGap else 0
            val bubbleY = (topSpace + padding + mainR).toFloat()
            // Y du close (si above : au-dessus de la bulle ; si below : en dessous)
            val h: Int
            if (closeAbove) {
                // close au-dessus de la bulle => il faut de la place au-dessus
                // pour le close. Donc on monte bubbleY de expandDist.
                // Mais on a aussi le rectangle decision en haut potentiellement,
                // qui ne doit pas etre occulte par le close. On laisse le close
                // entre la decision et la bulle.
                // Pour simplifier : on garde close EN DESSOUS dans ce cas
                // (closeAbove sera vrai uniquement si la bulle est tout en bas).
                h = (topSpace + padding + bubbleSize + expandDist + subR + padding).toInt()
                bubbleCy = bubbleY
                closeCx = w / 2f
                closeCy = bubbleY - expandDist // au dessus de la bulle
                if (closeCy - subR < topSpace + padding) {
                    // Pas la place au-dessus, force en bas
                    closeCy = bubbleY + expandDist
                }
            } else {
                h = (topSpace + padding + bubbleSize + expandDist + subR + padding).toInt()
                bubbleCy = bubbleY
                closeCx = w / 2f
                closeCy = bubbleY + expandDist // en dessous
            }
            currentViewW = w
            currentViewH = h
            bubbleCx = w / 2f
            // Mic et scan : un a gauche, un a droite. subBubblesLeft decide
            // QUEL ICONE va a gauche. Par convention :
            //   - Si la bulle est dans la moitie droite (subBubblesLeft=true),
            //     l'icone "principale" mic va a gauche (vers le centre de l'ecran)
            //     pour etre plus accessible au pouce.
            //   - Sinon mic a droite.
            if (subBubblesLeft) {
                micCx = bubbleCx - expandDist
                scanCx = bubbleCx + expandDist
            } else {
                micCx = bubbleCx + expandDist
                scanCx = bubbleCx - expandDist
            }
            micCy = bubbleCy
            scanCy = bubbleCy
            decisionTopY = padding.toFloat()
        }
    }

    /**
     * Applique le nouveau layout : recompute + redimensionne la fenetre +
     * preserve la position visuelle de la bulle BJ a l'ecran.
     */
    private fun applyResize() {
        val lp = layoutParams as? WindowManager.LayoutParams
        // Sauvegarde la position visuelle (screen-coords) du centre BJ AVANT recompute
        val oldBubbleScreenX = if (lp != null && currentViewW > 0)
            lp.x + bubbleCx.toInt() else Int.MIN_VALUE
        val oldBubbleScreenY = if (lp != null && currentViewW > 0)
            lp.y + bubbleCy.toInt() else Int.MIN_VALUE

        recomputeLayout()

        if (lp != null) {
            // Si la View etait deja attachee, on ajuste params pour que le
            // centre de la BJ reste au meme endroit visuellement.
            if (oldBubbleScreenX != Int.MIN_VALUE) {
                lp.x = oldBubbleScreenX - bubbleCx.toInt()
                lp.y = oldBubbleScreenY - bubbleCy.toInt()
                // Clamping ecran pour eviter que la BJ sorte de l'ecran
                val sw = resources.displayMetrics.widthPixels
                val sh = resources.displayMetrics.heightPixels
                val minX = -bubbleCx.toInt() + (4 * density).toInt()
                val maxX = sw - bubbleCx.toInt() - (4 * density).toInt() - bubbleSize / 2
                val minY = -bubbleCy.toInt() + (4 * density).toInt()
                val maxY = sh - bubbleCy.toInt() - (4 * density).toInt() - bubbleSize / 2
                lp.x = max(minX, min(maxX, lp.x))
                lp.y = max(minY, min(maxY, lp.y))
            }
            lp.width = currentViewW
            lp.height = currentViewH
            try {
                windowManager?.updateViewLayout(this, lp)
            } catch (e: Exception) {
                Log.w("BubbleView", "applyResize updateViewLayout failed", e)
            }
        } else {
            // Premier appel, la View n'est pas encore attachee. On cree des params.
            layoutParams = WindowManager.LayoutParams(currentViewW, currentViewH)
        }
        invalidate()
    }

    private fun decisionBoxWidth(): Float {
        val measured = if (decisionText.isNotEmpty())
            decisionTextPaint.measureText(decisionText) + 24 * density
        else decisionMinWidth.toFloat()
        return max(decisionMinWidth.toFloat(), measured)
    }

    // ── Dessin ──────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 1) Rectangle decision (s'il y a un texte)
        if (decisionText.isNotEmpty()) {
            val w = decisionBoxWidth()
            val rect = RectF(
                bubbleCx - w / 2f, decisionTopY,
                bubbleCx + w / 2f, decisionTopY + decisionHeight
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
            drawSubBubble(canvas, micCx, micCy, "\uD83C\uDFA4", micActive)
            drawSubBubble(canvas, scanCx, scanCy, "\uD83D\uDCF7", false)
            drawCloseButton(canvas, closeCx, closeCy)
        }
        // 3) Bulle principale (toujours)
        val r = bubbleSize / 2f
        canvas.drawCircle(bubbleCx + 2 * density, bubbleCy + 3 * density,
                          r + 1 * density, shadowPaint)
        canvas.drawCircle(bubbleCx, bubbleCy, r, bgPaint)
        val ty = bubbleCy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("BJ", bubbleCx, ty, textPaint)
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

    // ── Hit-test : utilise les centres a jour ───────────────────────
    private fun hitTest(x: Float, y: Float): HitZone {
        if (decisionText.isNotEmpty()) {
            val w = decisionBoxWidth()
            if (x in (bubbleCx - w / 2f)..(bubbleCx + w / 2f) &&
                y in decisionTopY..(decisionTopY + decisionHeight)) {
                return HitZone.DECISION
            }
        }
        val mainR = bubbleSize / 2f
        if (distSq(x, y, bubbleCx, bubbleCy) <= mainR * mainR) return HitZone.BUBBLE
        if (expanded) {
            val subR = subBubbleSize / 2f
            val subR2 = subR * subR
            if (distSq(x, y, micCx, micCy) <= subR2) return HitZone.MIC
            if (distSq(x, y, scanCx, scanCy) <= subR2) return HitZone.SCAN
            if (distSq(x, y, closeCx, closeCy) <= subR2) return HitZone.CLOSE
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
                // Si on ne touche aucune zone interactive, on laisse passer
                // l'evenement vers la fenetre dessous (FLAG_NOT_TOUCH_MODAL fait
                // le reste). Important : en collapsed la View est minuscule donc
                // cette situation est rare ; en expanded la View est plus grande
                // mais le hit-test discrimine bien.
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
                if (hasDragged && (hitZone == HitZone.BUBBLE || hitZone == HitZone.DECISION)) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    val sw = resources.displayMetrics.widthPixels
                    val sh = resources.displayMetrics.heightPixels
                    // Clamp : la BJ doit rester visible a l'ecran
                    val minX = -bubbleCx.toInt() + (4 * density).toInt()
                    val maxX = sw - bubbleCx.toInt() - (4 * density).toInt() - bubbleSize / 2
                    val minY = -bubbleCy.toInt() + (4 * density).toInt()
                    val maxY = sh - bubbleCy.toInt() - (4 * density).toInt() - bubbleSize / 2
                    params.x = max(minX, min(maxX, params.x))
                    params.y = max(minY, min(maxY, params.y))
                    try {
                        windowManager?.updateViewLayout(this, params)
                    } catch (e: Exception) {
                        Log.w("BubbleView", "drag updateViewLayout failed", e)
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
        val currentBubbleScreenX = params.x + bubbleCx.toInt()
        val targetBubbleScreenX = if (currentBubbleScreenX < screenW / 2) {
            (bubbleSize / 2 + 8 * density).toInt()
        } else {
            screenW - bubbleSize / 2 - (8 * density).toInt()
        }
        val targetParamsX = targetBubbleScreenX - bubbleCx.toInt()
        val anim = android.animation.ValueAnimator.ofInt(params.x, targetParamsX)
        anim.duration = 200
        anim.addUpdateListener { va ->
            params.x = va.animatedValue as Int
            try { windowManager?.updateViewLayout(this, params) } catch (_: Exception) {}
        }
        anim.start()
    }
}
