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
 * Bulle flottante BJ Genius — v1.3.4
 *
 * REFONTE PROPRE :
 *  - Le constructeur ne fait RIEN qui puisse exception (pas d'appel a
 *    recomputeLayout dans init).
 *  - Les dimensions et coordonnees sont calculees de maniere lazy : la
 *    premiere fois c'est `ensureLayout()` qui s'en charge, soit lors du
 *    premier draw, soit lors du premier touch.
 *  - applyResize() ne touche au WindowManager QUE si la View est deja
 *    attachee (windowManager non null ET layoutParams non null).
 *  - Les constantes geometriques (DIMENS_*) sont expose statiquement
 *    pour que le service puisse les utiliser pour calculer les dimensions
 *    AVANT que la View soit instanciee, ce qui evite tout probleme
 *    d'ordre d'initialisation.
 */
@SuppressLint("ViewConstructor")
class BubbleOverlayView(context: Context) : View(context) {

    companion object {
        // Dimensions en dp (multipliees par density pour avoir des pixels)
        const val BUBBLE_DP = 56
        const val SUB_BUBBLE_DP = 42
        const val EXPAND_DIST_DP = 64
        const val DECISION_HEIGHT_DP = 32
        const val DECISION_MIN_WIDTH_DP = 110
        const val DECISION_GAP_DP = 8
        const val PADDING_DP = 6

        /** Calcule la largeur d'une vue COLLAPSED (sans expanded, sans decision)
         *  en pixels, utilisable AVANT que la View ne soit instanciee. */
        @JvmStatic
        fun collapsedWidthPx(density: Float): Int =
            ((BUBBLE_DP + PADDING_DP * 2) * density).toInt()

        @JvmStatic
        fun collapsedHeightPx(density: Float): Int =
            ((BUBBLE_DP + PADDING_DP * 2) * density).toInt()
    }

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

    // ── Geometrie en pixels (calcule a partir de la density) ────────
    private val density = resources.displayMetrics.density
    private val bubbleSize = (BUBBLE_DP * density).toInt()
    private val subBubbleSize = (SUB_BUBBLE_DP * density).toInt()
    private val expandDist = EXPAND_DIST_DP * density
    private val decisionHeight = (DECISION_HEIGHT_DP * density).toInt()
    private val decisionMinWidth = (DECISION_MIN_WIDTH_DP * density).toInt()
    private val decisionGap = (DECISION_GAP_DP * density).toInt()
    private val padding = (PADDING_DP * density).toInt()

    // ── Adaptation a la position de la BJ sur l'ecran ───────────────
    private var subBubblesLeft = true
    private var closeAbove = false

    // ── Centres calcules par recomputeLayout ────────────────────────
    // Init a des valeurs par defaut sensees au cas ou recomputeLayout
    // n'aurait pas encore tourne (mais on l'appelle dans onAttachedToWindow).
    private var bubbleCx = (bubbleSize / 2f + padding)
    private var bubbleCy = (bubbleSize / 2f + padding)
    private var micCx = 0f; private var micCy = 0f
    private var scanCx = 0f; private var scanCy = 0f
    private var closeCx = 0f; private var closeCy = 0f
    private var decisionTopY = 0f

    // Dimensions courantes (mises a jour par recomputeLayout)
    private var currentViewW = bubbleSize + padding * 2
    private var currentViewH = bubbleSize + padding * 2

    // Flag pour eviter le 1er recompute pre-attachement (qui n'a pas de
    // donnees screen utiles)
    private var hasLaidOutOnce = false

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

    // v1.3.10 — Reference a l'animation de resize en cours pour pouvoir
    // l'annuler si un autre resize est demande avant la fin (par exemple
    // un updateDecision qui arrive pendant une animation d'expand).
    private var resizeAnimator: android.animation.ValueAnimator? = null

    // ── Note importante : pas de bloc init {} faisant des calculs ───
    // Les valeurs par defaut de currentViewW/H et bubbleCx/Cy sont deja
    // correctes pour l'etat collapsed-sans-decision.

    // ── API publique ────────────────────────────────────────────────
    fun updateDecision(text: String, colorHex: String?) {
        decisionText = text
        if (!colorHex.isNullOrEmpty()) {
            try { decisionColor = Color.parseColor(colorHex) } catch (_: Exception) {}
        }
        decisionBgPaint.color = decisionColor
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

    // ── Recompute layout (calcule les dimensions et coordonnees) ────
    private fun recomputeLayout() {
        val mainR = bubbleSize / 2
        val subR = subBubbleSize / 2
        val hasDecision = decisionText.isNotEmpty()

        // Determine la direction des sous-bulles selon la position de la BJ
        val lp = layoutParams as? WindowManager.LayoutParams
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        if (lp != null && hasLaidOutOnce) {
            val bjScreenX = lp.x + bubbleCx.toInt()
            val bjScreenY = lp.y + bubbleCy.toInt()
            subBubblesLeft = bjScreenX > screenW / 2
            closeAbove = bjScreenY > screenH * 2 / 3
        }
        // Sinon on garde les valeurs courantes (subBubblesLeft=true par defaut
        // pour la position initiale en haut-droite).

        if (!expanded) {
            // ─── COLLAPSED ───────────────────────────────────────────
            val w = if (hasDecision) {
                max(bubbleSize, decisionBoxWidth().toInt()) + padding * 2
            } else {
                bubbleSize + padding * 2
            }
            val h = if (hasDecision) {
                decisionHeight + decisionGap + bubbleSize + padding * 2
            } else {
                bubbleSize + padding * 2
            }
            currentViewW = w
            currentViewH = h
            bubbleCx = w / 2f
            bubbleCy = if (hasDecision) {
                (padding + decisionHeight + decisionGap + mainR).toFloat()
            } else {
                (padding + mainR).toFloat()
            }
            decisionTopY = padding.toFloat()
        } else {
            // ─── EXPANDED : sous-bulles en ARC autour de BJ ──────────
            // Les 3 sous-bulles (mic, scan, close) sont toutes positionnees
            // DU MEME COTE que opposé à la position de BJ sur l'ecran.
            // - Si BJ est dans la moitie droite : sous-bulles a GAUCHE
            //   en arc de cercle (mic au milieu, scan au-dessus, close au-dessous)
            // - Si BJ est a gauche : sous-bulles a droite
            // Cela evite qu'une sous-bulle sorte de l'ecran.
            //
            // Layout en arc de 120 degres (de -60 a +60 par rapport a la
            // direction opposee a la BJ) :
            //
            //   scan     ↖ (-60 deg)
            //     \
            //   mic  ←--- BJ          (cote gauche = vers gauche)
            //     /
            //   close    ↙ (+60 deg)
            //
            val arcRadius = expandDist
            // v1.3.7 — Angle d'arc reduit a 50deg (au lieu de 60) pour un layout
            // plus serre et visuellement plus harmonieux. Les 3 sous-bulles
            // restent bien distinctes mais ne s'eloignent pas trop verticalement.
            val angleSpread = Math.toRadians(50.0)
            val baseAngle = if (subBubblesLeft) Math.PI else 0.0
            // 3 angles : -spread, 0, +spread
            val angleMic = baseAngle
            val angleScan = baseAngle - angleSpread
            val angleClose = baseAngle + angleSpread

            // Calcul de la bounding box (largeur et hauteur en pixels)
            // necessaire pour contenir BJ + sous-bulles + decision en haut.
            // Pour des angles symetriques en haut/bas, le max vertical depasse
            // de l'arc d'environ arcRadius * sin(spread) + subR.
            //
            // Bounding box autour de la bulle centrale (en pixels relatifs) :
            //   x_min/max = +/- (cos(spread)*arcRadius + subR) si side opposé
            //   y_min/max = +/- (sin(spread)*arcRadius + subR)
            val cosSpread = Math.cos(angleSpread).toFloat()
            val sinSpread = Math.sin(angleSpread).toFloat()

            // Largeur : il faut contenir BJ (bubbleSize) plus l'arc d'un seul cote.
            // L'arc s'etend au max de cos(spread) * arcRadius + subR vers le cote opposé.
            val arcExtent = (arcRadius * cosSpread + subR).toInt()
            var w = bubbleSize + arcExtent + padding * 2
            // Si on a une decision, sa largeur peut depasser la View. On etend
            // la View pour la contenir.
            if (hasDecision) {
                val decW = decisionBoxWidth().toInt() + padding * 2
                w = max(w, decW)
            }

            // Hauteur : il faut contenir l'arc complet en vertical (-spread a +spread)
            // soit 2 * sin(spread) * arcRadius + 2*subR (subR de chaque cote)
            // + decision en haut si present.
            val topSpace = if (hasDecision) decisionHeight + decisionGap else 0
            val arcHalfHeight = (arcRadius * sinSpread + subR).toInt()
            val h = topSpace + max(bubbleSize, arcHalfHeight * 2) + padding * 2

            currentViewW = w
            currentViewH = h

            // Position de BJ dans la View :
            // - Horizontalement : si sous-bulles a GAUCHE, BJ est a DROITE de la View
            //   (donc bubbleCx = w - padding - mainR)
            //   Si sous-bulles a DROITE, BJ est a gauche.
            // - Verticalement : centre vertical, sous l'eventuel rectangle decision
            bubbleCx = if (subBubblesLeft) {
                (w - padding - mainR).toFloat()
            } else {
                (padding + mainR).toFloat()
            }
            bubbleCy = (topSpace + padding + max(bubbleSize, arcHalfHeight * 2) / 2f)

            // Sous-bulles positionnees autour de BJ via angle
            micCx = bubbleCx + (arcRadius * Math.cos(angleMic)).toFloat()
            micCy = bubbleCy + (arcRadius * Math.sin(angleMic)).toFloat()
            scanCx = bubbleCx + (arcRadius * Math.cos(angleScan)).toFloat()
            scanCy = bubbleCy + (arcRadius * Math.sin(angleScan)).toFloat()
            closeCx = bubbleCx + (arcRadius * Math.cos(angleClose)).toFloat()
            closeCy = bubbleCy + (arcRadius * Math.sin(angleClose)).toFloat()

            decisionTopY = padding.toFloat()
        }
        hasLaidOutOnce = true
    }

    /**
     * Applique le nouveau layout au WindowManager si on est attache.
     *
     * v1.3.10 — Transition animee entre l'ancienne et la nouvelle taille
     * de la View pour eliminer le "surbond" visuel a l'expand/collapse.
     * L'animation interpole width/height/x/y simultanement pendant 160ms,
     * en preservant la position visuelle du centre de la BJ.
     */
    private fun applyResize() {
        val lp = layoutParams as? WindowManager.LayoutParams
        val canAnimate = lp != null && windowManager != null && hasLaidOutOnce

        if (!canAnimate) {
            // Premier resize (pas encore attache) : on applique direct sans animer.
            recomputeLayout()
            if (lp != null && windowManager != null) {
                lp.width = currentViewW
                lp.height = currentViewH
                try {
                    windowManager?.updateViewLayout(this, lp)
                } catch (e: Exception) {
                    Log.w("BubbleView", "applyResize initial updateViewLayout failed", e)
                }
            }
            invalidate()
            return
        }

        // Sauvegarde l'etat AVANT recompute pour calcul des deltas
        val oldW = lp!!.width
        val oldH = lp.height
        val oldX = lp.x
        val oldY = lp.y
        val oldBubbleScreenX = lp.x + bubbleCx.toInt()
        val oldBubbleScreenY = lp.y + bubbleCy.toInt()

        recomputeLayout()

        // Nouvelles cibles : taille = currentViewW/H, position telle que le centre
        // de la BJ reste exactement au meme endroit visuellement.
        val targetW = currentViewW
        val targetH = currentViewH
        var targetX = oldBubbleScreenX - bubbleCx.toInt()
        var targetY = oldBubbleScreenY - bubbleCy.toInt()
        // Clamp pour eviter sortie d'ecran
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val minX = -bubbleCx.toInt() + (4 * density).toInt()
        val maxX = sw - bubbleCx.toInt() - (4 * density).toInt() - bubbleSize / 2
        val minY = -bubbleCy.toInt() + (4 * density).toInt()
        val maxY = sh - bubbleCy.toInt() - (4 * density).toInt() - bubbleSize / 2
        targetX = max(minX, min(maxX, targetX))
        targetY = max(minY, min(maxY, targetY))

        // Si la difference est tres petite (cas decision text qui change mais pas
        // l'etat expand), on applique direct sans animer pour eviter une animation
        // imperceptible mais qui pourrait spammer le WindowManager.
        val negligible = Math.abs(targetW - oldW) < 4 && Math.abs(targetH - oldH) < 4
        if (negligible) {
            lp.width = targetW
            lp.height = targetH
            lp.x = targetX
            lp.y = targetY
            try {
                windowManager?.updateViewLayout(this, lp)
            } catch (e: Exception) {
                Log.w("BubbleView", "applyResize negligible updateViewLayout failed", e)
            }
            invalidate()
            return
        }

        // Annule une animation precedente si elle est encore en cours
        resizeAnimator?.cancel()
        // Animation : interpolation lineaire sur 160ms
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        }
        resizeAnimator = anim
        anim.addUpdateListener { va ->
            val t = va.animatedValue as Float
            try {
                lp.width = (oldW + (targetW - oldW) * t).toInt()
                lp.height = (oldH + (targetH - oldH) * t).toInt()
                lp.x = (oldX + (targetX - oldX) * t).toInt()
                lp.y = (oldY + (targetY - oldY) * t).toInt()
                windowManager?.updateViewLayout(this, lp)
            } catch (e: Exception) {
                Log.w("BubbleView", "applyResize anim updateViewLayout failed", e)
                anim.cancel()
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                resizeAnimator = null
            }
        })
        anim.start()
        invalidate()
    }

    /** X du centre du rectangle decision. En collapsed, centre sur bubbleCx
     *  (qui est au centre de la View). En expanded, BJ est decalee a un cote
     *  donc on centre plutot le rectangle decision sur le milieu de la View
     *  pour qu'il ne sorte pas. */
    private fun decisionCenterX(): Float =
        if (expanded) currentViewW / 2f else bubbleCx

    private fun decisionBoxWidth(): Float {
        val measured = if (decisionText.isNotEmpty())
            decisionTextPaint.measureText(decisionText) + 24 * density
        else decisionMinWidth.toFloat()
        return max(decisionMinWidth.toFloat(), measured)
    }

    // ── Cycle de vie : on declenche le recompute quand on est attache ───
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Recompute initial maintenant qu'on a un layoutParams et qu'on est
        // visible. Aucun risque d'exception ici.
        recomputeLayout()
    }

    // ── Dessin ──────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 1) Rectangle decision
        if (decisionText.isNotEmpty()) {
            val w = decisionBoxWidth()
            val dcx = decisionCenterX()
            val rect = RectF(
                dcx - w / 2f, decisionTopY,
                dcx + w / 2f, decisionTopY + decisionHeight
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

    // ── Hit-test ───────────────────────────────────────────────────
    private fun hitTest(x: Float, y: Float): HitZone {
        if (decisionText.isNotEmpty()) {
            val w = decisionBoxWidth()
            val dcx = decisionCenterX()
            if (x in (dcx - w / 2f)..(dcx + w / 2f) &&
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
