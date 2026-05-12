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
        const val CHANNEL_ID = "bjgenius_bubble_channel"
        const val NOTIFICATION_ID = 4242
        const val TAG = "BJGeniusBubble"

        // v1.2 — Etat interne du service. Modifie uniquement par le service
        // lui-meme via startBubble()/stopBubble(). Lu de l'exterieur via la
        // fonction statique isRunning() ci-dessous.
        // Pas de @JvmStatic ici : la variable est privee, donc inutile d'exposer
        // au monde Java (et @JvmStatic sur une private var emet un warning).
        private var running: Boolean = false

        /**
         * Indique si le service tourne. Lu par le plugin Java pour eviter
         * les double-starts.
         *
         * v1.2 — Fonction explicite plutot que propriete pour eviter les pieges
         * de naming Kotlin/Java sur les booleens prefixes par 'is'.
         */
        @JvmStatic
        fun isRunning(): Boolean = running
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: BubbleOverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBubble()
            ACTION_STOP -> stopBubble()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        // START_NOT_STICKY : si le system kill le service (rare), on ne le
        // restart pas automatiquement, car il faut un trigger user.
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
                onCloseRequested = {
                    stopBubble()
                }
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
                // Position initiale : coin haut-droit, marges 16dp converti
                val density = resources.displayMetrics.density
                x = (resources.displayMetrics.widthPixels - 64 * density).toInt()
                y = (120 * density).toInt()
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
}        const val CHANNEL_ID = "bjgenius_bubble_channel"
        const val NOTIFICATION_ID = 4242
        const val TAG = "BJGeniusBubble"

        // v1.2 — Etat interne du service. Modifie uniquement par le service
        // lui-meme via startBubble()/stopBubble(). Lu de l'exterieur via la
        // fonction statique isRunning() ci-dessous.
        // Pas de @JvmStatic ici : la variable est privee, donc inutile d'exposer
        // au monde Java (et @JvmStatic sur une private var emet un warning).
        private var running: Boolean = false

        /**
         * Indique si le service tourne. Lu par le plugin Java pour eviter
         * les double-starts.
         *
         * v1.2 — Fonction explicite plutot que propriete pour eviter les pieges
         * de naming Kotlin/Java sur les booleens prefixes par 'is'.
         */
        @JvmStatic
        fun isRunning(): Boolean = running
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: BubbleOverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBubble()
            ACTION_STOP -> stopBubble()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        // START_NOT_STICKY : si le system kill le service (rare), on ne le
        // restart pas automatiquement, car il faut un trigger user.
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
                onCloseRequested = {
                    stopBubble()
                }
            }

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Position initiale : coin haut-droit, marges 16dp converti
                val density = resources.displayMetrics.density
                x = (resources.displayMetrics.widthPixels - 64 * density).toInt()
                y = (120 * density).toInt()
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
