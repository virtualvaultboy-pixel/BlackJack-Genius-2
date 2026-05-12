package studio.deponchy.bjgenius

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * BJ Genius Live — Foreground Service v1.3.4
 *
 * Cycle de vie :
 *   ACTION_START          → cree la bulle via WindowManager + foreground notif
 *   ACTION_STOP           → retire la bulle + arrete le service
 *   ACTION_SET_DECISION   → met a jour le rectangle decision (depuis JS)
 *   ACTION_SET_MIC_STATE  → met a jour la couleur de la sous-bulle mic
 *
 * Le service tourne meme si l'app Capacitor est en background.
 *
 * v1.3.4 : code blinde, utilise les helpers statiques de BubbleOverlayView
 * pour les dimensions initiales, evite tout race condition d'init.
 */
class BJGeniusBubbleService : Service() {

    companion object {
        const val ACTION_START = "studio.deponchy.bjgenius.START_BUBBLE"
        const val ACTION_STOP = "studio.deponchy.bjgenius.STOP_BUBBLE"
        const val ACTION_SET_DECISION = "studio.deponchy.bjgenius.SET_DECISION"
        const val ACTION_SET_MIC_STATE = "studio.deponchy.bjgenius.SET_MIC_STATE"
        const val EXTRA_DECISION_TEXT = "decision_text"
        const val EXTRA_DECISION_COLOR = "decision_color"
        const val EXTRA_MIC_ACTIVE = "mic_active"

        // Broadcasts emis par le service vers le plugin Java qui les relaie a JS
        const val BROADCAST_BUBBLE_EVENT = "studio.deponchy.bjgenius.BUBBLE_EVENT"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EVENT_MIC_TAP = "mic_tap"
        const val EVENT_SCAN_TAP = "scan_tap"
        const val EVENT_CLOSE_TAP = "close_tap"
        const val EVENT_BUBBLE_TAP = "bubble_tap"

        const val CHANNEL_ID = "bjgenius_bubble_channel"
        const val NOTIFICATION_ID = 4242
        const val TAG = "BJGeniusBubble"

        private var running: Boolean = false

        @JvmStatic
        fun isRunning(): Boolean = running

        // Reference statique a l'instance du service pour permettre au plugin
        // Java d'appeler les methodes de mise a jour de la bulle sans avoir
        // a passer par un Intent (plus rapide pour les updates frequentes
        // comme la decision en mode vocal).
        private var instance: BJGeniusBubbleService? = null

        @JvmStatic
        fun setDecision(text: String, colorHex: String?) {
            val inst = instance ?: return
            inst.bubbleView?.updateDecision(text, colorHex)
        }

        @JvmStatic
        fun setMicState(active: Boolean) {
            val inst = instance ?: return
            inst.bubbleView?.updateMicState(active)
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: BubbleOverlayView? = null
    // v1.3.13 — WakeLock partiel qui garde le CPU actif pendant que la bulle
    // tourne. Sans ca, Android suspend la WebView (et donc Whisper, et le
    // listener bubbleEvent) des que l'app passe en background.
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBubble()
            ACTION_STOP -> stopBubble()
            ACTION_SET_DECISION -> {
                val text = intent.getStringExtra(EXTRA_DECISION_TEXT) ?: ""
                val color = intent.getStringExtra(EXTRA_DECISION_COLOR)
                bubbleView?.updateDecision(text, color)
            }
            ACTION_SET_MIC_STATE -> {
                val active = intent.getBooleanExtra(EXTRA_MIC_ACTIVE, false)
                bubbleView?.updateMicState(active)
            }
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private fun startBubble() {
        if (running) {
            Log.d(TAG, "Already running, ignoring start")
            return
        }

        // ── 1. Foreground (obligatoire avant toute autre operation ANR) ──
        // Sur Android 14+ (API 34) il faut declarer le type, sinon SecurityException.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    // v1.3.13 — Type combine : specialUse (bulle flottante) +
                    // microphone (autorisation capture audio en background).
                    // Doit matcher le foregroundServiceType du manifest.
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed (fatal)", e)
            stopSelf()
            return
        }

        // ── 2. Verif que le WindowManager est accessible ──
        val wm = windowManager
        if (wm == null) {
            Log.e(TAG, "WindowManager is null (fatal)")
            stopSelf()
            return
        }

        // ── 3. Construction de la View ──
        try {
            bubbleView = BubbleOverlayView(this).apply {
                onCloseRequested = {
                    emitBubbleEvent(EVENT_CLOSE_TAP)
                    stopBubble()
                }
                onBubbleTap = { emitBubbleEvent(EVENT_BUBBLE_TAP) }
                onMicTap = { emitBubbleEvent(EVENT_MIC_TAP) }
                onScanTap = { emitBubbleEvent(EVENT_SCAN_TAP) }
                this.windowManager = wm
            }
        } catch (e: Exception) {
            Log.e(TAG, "BubbleOverlayView construction failed (fatal)", e)
            stopSelf()
            return
        }

        // ── 4. Dimensions et position initiales ──
        // On utilise les helpers STATIQUES de la View pour calculer les
        // dimensions, ce qui evite tout probleme d'ordre d'initialisation.
        // L'etat initial est : collapsed, pas de decision = juste la bulle.
        val density = resources.displayMetrics.density
        val viewW = BubbleOverlayView.collapsedWidthPx(density)
        val viewH = BubbleOverlayView.collapsedHeightPx(density)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Flags critiques pour le tactile pass-through :
        //   - FLAG_NOT_FOCUSABLE     : pas de focus clavier
        //   - FLAG_NOT_TOUCH_MODAL   : touches en dehors passent dessous (KEY)
        //   - FLAG_LAYOUT_NO_LIMITS  : peut se positionner partout
        //   - FLAG_LAYOUT_IN_SCREEN  : coords absolues fiables
        val overlayFlags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            viewW, viewH,
            layoutFlag,
            overlayFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Position initiale : bulle en haut a droite, a ~32dp du bord droit.
            // viewW=68dp (collapsed sans decision) donc on positionne le coin
            // haut-gauche de la View de facon a ce que la bulle soit au bon endroit.
            val screenW = resources.displayMetrics.widthPixels
            x = screenW - viewW - (16 * density).toInt()
            y = (90 * density).toInt()
        }

        // ── 5. Attachement ──
        try {
            wm.addView(bubbleView, params)
            running = true
            Log.d(TAG, "Bubble added at x=${params.x}, y=${params.y}, w=$viewW, h=$viewH")

            // v1.3.13 — Acquerir le WakeLock partiel qui garde le CPU actif.
            // Sans ca, Android suspend la WebView Capacitor et :
            //   - Whisper arrete d'ecouter le mic
            //   - Le listener bubbleEvent ne recoit plus les taps mic/scan
            // On l'acquiert seulement apres le succes du addView pour eviter
            // de laisser un wakelock orphelin si la bulle a echoue a se creer.
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BJGenius:BubbleWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(8 * 60 * 60 * 1000L) // Max 8h en securite, releve manuellement avant
                }
                Log.d(TAG, "WakeLock acquired (CPU stays active in background)")
            } catch (e: Exception) {
                // Non-fatal : la bulle marchera mais le mic ne tiendra pas en background.
                Log.w(TAG, "WakeLock acquisition failed (non-fatal)", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "addView failed (fatal)", e)
            bubbleView = null
            stopSelf()
        }
    }

    /**
     * Emet un broadcast local quand l'user tape sur une zone de la bulle.
     * Le plugin Java attrape ce broadcast et le relaie a JS via notifyListeners.
     */
    private fun emitBubbleEvent(eventType: String) {
        val intent = Intent(BROADCAST_BUBBLE_EVENT).apply {
            putExtra(EXTRA_EVENT_TYPE, eventType)
            setPackage(packageName)
        }
        try {
            sendBroadcast(intent)
            Log.d(TAG, "Bubble event: $eventType")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast bubble event", e)
        }
    }

    private fun stopBubble() {
        Log.d(TAG, "Stopping bubble")
        // v1.3.13 — Release du WakeLock pour laisser Android suspendre normalement
        // l'app. Important pour la batterie : sans ca, le CPU resterait reveille
        // meme apres la fermeture de la bulle.
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed", e)
        }
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed (already removed?)", e)
        }
        bubbleView = null
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        instance = null
        // v1.3.13 — Safety net : release du WakeLock meme si stopBubble n'a pas
        // ete appele proprement (cas ou Android tue le service brutalement).
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (_: Exception) {}
        // stopBubble est idempotent et safe meme si deja appele
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        bubbleView = null
        running = false
        super.onDestroy()
    }

    /** Notification permanente obligatoire pour les foreground services. */
    private fun buildNotification(): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent().apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openAppPending = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags)

        val stopIntent = Intent(this, BJGeniusBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BJ Genius actif")
            .setContentText("La bulle est visible — tap pour ouvrir l'app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fermer", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BJ Genius Live",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification permanente quand la bulle BJ Genius est active"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
