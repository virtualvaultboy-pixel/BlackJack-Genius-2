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
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * BJ Genius Live — Foreground Service
 *
 * Maintient la bulle flottante par-dessus toutes les apps tant que le service
 * tourne. Notification permanente obligatoire (Android impose).
 *
 * Cycle de vie :
 *   ACTION_START → cree la bulle via WindowManager + foreground notification
 *   ACTION_STOP  → retire la bulle + arrete le service
 *
 * Le service tourne meme si l'app Capacitor est en background. La bulle reste
 * visible jusqu'a ce que l'user appuie sur le bouton fermer de la bulle, ou
 * coupe le micro depuis l'app, ou desinstalle/redemarre.
 */
class BJGeniusBubbleService : Service() {

    companion object {
        const val ACTION_START = "studio.deponchy.bjgenius.START_BUBBLE"
        const val ACTION_STOP = "studio.deponchy.bjgenius.STOP_BUBBLE"
        // v1.3 — Actions pour mise a jour live de la bulle depuis JS
        const val ACTION_SET_DECISION = "studio.deponchy.bjgenius.SET_DECISION"
        const val ACTION_SET_MIC_STATE = "studio.deponchy.bjgenius.SET_MIC_STATE"
        const val EXTRA_DECISION_TEXT = "decision_text"
        const val EXTRA_DECISION_COLOR = "decision_color"
        const val EXTRA_MIC_ACTIVE = "mic_active"
        // v1.3 — Actions diffusees du service VERS l'app (broadcast local)
        // pour informer JS des taps de l'user sur les sous-bulles.
        const val BROADCAST_BUBBLE_EVENT = "studio.deponchy.bjgenius.BUBBLE_EVENT"
        const val EXTRA_EVENT_TYPE = "event_type"
        // Types d'evenements emis vers JS
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

        // v1.3 — Reference statique a l'instance du service pour que le plugin
        // Java puisse appeler les methodes de mise a jour de la bulle. Geree
        // par onCreate / onDestroy pour eviter les fuites memoire.
        // Pas de @JvmStatic ici : la variable est privee, donc inutile et
        // generait un warning Kotlin si on l'ajoutait.
        private var instance: BJGeniusBubbleService? = null

        /** Met a jour le texte et la couleur du rectangle decision. Appele depuis JS. */
        @JvmStatic
        fun setDecision(text: String, colorHex: String?) {
            val inst = instance ?: return
            inst.bubbleView?.updateDecision(text, colorHex)
        }

        /** Met a jour l'etat actif du mic (couleur sous-bulle). Appele depuis JS. */
        @JvmStatic
        fun setMicState(active: Boolean) {
            val inst = instance ?: return
            inst.bubbleView?.updateMicState(active)
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: BubbleOverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        // v1.3 — Bind instance pour permettre au plugin Java d'appeler les
        // methodes de mise a jour de la bulle.
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
        // Doit etre appele AVANT toute action longue, sinon ANR.
        // v1.2 — Sur Android 14 (API 34+), il faut declarer le foregroundServiceType
        // a l'appel runtime (en plus du manifest), sinon SecurityException.
        // L'overload a 3 args n'existe que sur API 29+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Exception) {
                // Fallback : si l'API systeme n'accepte pas specialUse (cas rare),
                // on retombe sur l'overload standard.
                Log.w(TAG, "startForeground with specialUse failed, fallback", e)
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        try {
            bubbleView = BubbleOverlayView(this).apply {
                // v1.3 — Callbacks de la bulle vers le service / JS.
                // onCloseRequested ferme localement, les autres taps emettent
                // un broadcast que le plugin Java relaie a JS via notifyListeners.
                onCloseRequested = {
                    emitBubbleEvent(EVENT_CLOSE_TAP)
                    stopBubble()
                }
                onBubbleTap = { emitBubbleEvent(EVENT_BUBBLE_TAP) }
                onMicTap = { emitBubbleEvent(EVENT_MIC_TAP) }
                onScanTap = { emitBubbleEvent(EVENT_SCAN_TAP) }
            }

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // v1.2.7 — FIX CRITIQUE : flags d'overlay corriges.
            // Avant : FLAG_NOT_FOCUSABLE seul -> la bulle capturait les touches
            // dans toute sa bounding-box (carre transparent autour du cercle),
            // ce qui rendait l'app du dessous totalement inutilisable.
            // Maintenant on combine 4 flags pour le bon comportement :
            //  - FLAG_NOT_FOCUSABLE      : on ne prend pas le focus clavier
            //  - FLAG_NOT_TOUCH_MODAL    : les touches en DEHORS de notre vue
            //                              passent a la fenetre du dessous (KEY!)
            //  - FLAG_LAYOUT_NO_LIMITS   : on peut se positionner partout, meme
            //                              sous la barre status / au-dela des
            //                              bords visibles. Evite que les marges
            //                              systeme nous decalent.
            //  - FLAG_LAYOUT_IN_SCREEN   : coordonnees absolues fiables sur
            //                              tous SDK (sinon decalage statusbar
            //                              sur certains Samsung).
            val overlayFlags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                overlayFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Position initiale : haut-droite, mais on positionne en tenant
                // compte de la nouvelle taille de la View (qui est plus grande
                // que la bulle seule a cause des sous-bulles potentielles).
                // On veut que la bulle CENTRALE soit visible vers la droite.
                val density = resources.displayMetrics.density
                val viewW = bubbleView?.width ?: (160 * density).toInt()
                val viewH = bubbleView?.height ?: (200 * density).toInt()
                // viewW peut etre 0 a ce stade (la View n'a pas fait son measure),
                // on utilise une estimation. Le snap au bord au 1er drag corrigera.
                val estimatedViewW = (224 * density).toInt() // ~ taille calculee dans la View
                x = (resources.displayMetrics.widthPixels - estimatedViewW + 28 * density).toInt()
                y = (90 * density).toInt()
            }

            bubbleView?.layoutParams = params
            bubbleView?.windowManager = windowManager
            windowManager?.addView(bubbleView, params)

            running = true
            Log.d(TAG, "Bubble added to WindowManager at x=${params.x}, y=${params.y}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start bubble", e)
            stopSelf()
        }
    }

    /**
     * v1.3 — Diffuse un evenement local indiquant qu'une zone de la bulle
     * a ete tapee. Le plugin Java ecoute ce broadcast et le relaie a JS
     * via notifyListeners() pour qu'il puisse executer toggleMic, etc.
     */
    private fun emitBubbleEvent(eventType: String) {
        val intent = Intent(BROADCAST_BUBBLE_EVENT).apply {
            putExtra(EXTRA_EVENT_TYPE, eventType)
            setPackage(packageName) // limite au seul package, pas de broadcast global
        }
        try {
            sendBroadcast(intent)
            Log.d(TAG, "Bubble event broadcast: $eventType")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast bubble event", e)
        }
    }

    private fun stopBubble() {
        Log.d(TAG, "Stopping bubble")
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Remove view failed (already removed?)", e)
        }
        bubbleView = null
        running = false
        // v1.2 — Compat tous SDK : STOP_FOREGROUND_REMOVE n'existe que sur API 24+,
        // sinon on utilise la version booleene historique.
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
        // v1.3 — Cleanup de la reference statique pour eviter les fuites memoire
        // et permettre au prochain demarrage du service de prendre une nouvelle instance.
        instance = null
        stopBubble()
        super.onDestroy()
    }

    /**
     * La notification est obligatoire pour les foreground services depuis
     * Android 8 (Oreo, API 26). Tap sur la notif = ouvre l'app principale.
     */
    private fun buildNotification(): Notification {
        // v1.2 — Intent pour ramener l'app au premier plan au tap sur la notif.
        // Securite : si getLaunchIntentForPackage retourne null (cas rare), on
        // fallback sur un Intent vide vers le package, qui ouvrira l'app via
        // l'activity declaree comme MAIN/LAUNCHER dans le manifest.
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

        // Intent "Arreter" pour permettre a l'user de fermer depuis la notif
        val stopIntent = Intent(this, BJGeniusBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BJ Genius actif")
            .setContentText("La bulle est visible — tap pour ouvrir l'app")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // sera remplace plus tard par l'icone app
            .setContentIntent(openAppPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fermer", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Pas de son/vibration
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BJ Genius Live",
                NotificationManager.IMPORTANCE_LOW // Silencieux
            ).apply {
                description = "Notification permanente quand la bulle BJ Genius est active"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
