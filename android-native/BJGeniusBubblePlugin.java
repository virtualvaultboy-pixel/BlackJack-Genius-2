package studio.deponchy.bjgenius;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Plugin Capacitor BJ Genius Bubble.
 *
 * v1.3 — Ajouts :
 *  - setDecision({text, color}) : met a jour le rectangle decision affiche
 *    au-dessus de la bulle.
 *  - setMicState({active}) : change la couleur de la sous-bulle mic
 *    (rouge si actif).
 *  - Listener interne sur BROADCAST_BUBBLE_EVENT : quand l'user tape sur
 *    une sous-bulle (mic, scan, close), le service emet un broadcast,
 *    le plugin l'attrape ici et le relaie a JS via notifyListeners().
 *
 * Usage JS :
 *   const { BJGeniusBubble } = window.Capacitor.Plugins;
 *   await BJGeniusBubble.start();
 *   BJGeniusBubble.addListener('bubbleEvent', ({type}) => {
 *     if (type === 'mic_tap') toggleMic();
 *   });
 *   await BJGeniusBubble.setDecision({ text: 'TIRER', color: '#22c55e' });
 *   await BJGeniusBubble.setMicState({ active: true });
 */
@CapacitorPlugin(name = "BJGeniusBubble")
public class BJGeniusBubblePlugin extends Plugin {

    private static final String TAG = "BJGeniusBubble";

    // v1.3 — Receveur de broadcasts emis par le service quand l'user tape
    // sur une sous-bulle. On le memorise pour pouvoir l'unregister proprement.
    private BroadcastReceiver bubbleEventReceiver;

    @Override
    public void load() {
        super.load();
        // v1.3 — Enregistrement du receveur de broadcasts au chargement
        // du plugin (= demarrage de l'app). Le filter limite au seul package
        // pour eviter d'ecouter d'autres apps malveillantes.
        bubbleEventReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, final Intent intent) {
                final String type = intent.getStringExtra(BJGeniusBubbleService.EXTRA_EVENT_TYPE);
                if (type == null) return;
                // v1.3.5 — On poste sur le main thread via le bridge Capacitor
                // pour eviter tout risque de notifyListeners depuis un mauvais
                // contexte (la WebView doit etre touchee uniquement depuis UI thread).
                try {
                    getBridge().getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                JSObject data = new JSObject();
                                data.put("type", type);
                                notifyListeners("bubbleEvent", data);
                                Log.d(TAG, "Bubble event relayed to JS: " + type);
                            } catch (Exception e) {
                                Log.w(TAG, "notifyListeners failed", e);
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "Cannot post bubble event to main thread", e);
                }
            }
        };
        IntentFilter filter = new IntentFilter(BJGeniusBubbleService.BROADCAST_BUBBLE_EVENT);
        // Android 14+ requiert un flag explicite pour les receivers non-exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(bubbleEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(bubbleEventReceiver, filter);
        }
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        // v1.3 — Cleanup du receiver pour eviter les fuites memoire.
        if (bubbleEventReceiver != null) {
            try {
                getContext().unregisterReceiver(bubbleEventReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Receiver unregister failed", e);
            }
            bubbleEventReceiver = null;
        }
    }

    @PluginMethod
    public void checkOverlayPermission(PluginCall call) {
        boolean granted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            granted = Settings.canDrawOverlays(getContext());
        }
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestOverlayPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName())
                );
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                JSObject ret = new JSObject();
                ret.put("opened", true);
                call.resolve(ret);
            } catch (Exception e) {
                Log.e(TAG, "Failed to open overlay settings", e);
                call.reject("Cannot open overlay settings: " + e.getMessage());
            }
        } else {
            JSObject ret = new JSObject();
            ret.put("opened", false);
            ret.put("reason", "Permission auto-granted on Android < M");
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(getContext())) {
            call.reject("OVERLAY_PERMISSION_DENIED");
            return;
        }
        try {
            Intent intent = new Intent(getContext(), BJGeniusBubbleService.class);
            intent.setAction(BJGeniusBubbleService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
            JSObject ret = new JSObject();
            ret.put("started", true);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start bubble service", e);
            call.reject("START_FAILED: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            Intent intent = new Intent(getContext(), BJGeniusBubbleService.class);
            intent.setAction(BJGeniusBubbleService.ACTION_STOP);
            getContext().startService(intent);
            JSObject ret = new JSObject();
            ret.put("stopped", true);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop bubble service", e);
            call.reject("STOP_FAILED: " + e.getMessage());
        }
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", BJGeniusBubbleService.isRunning());
        call.resolve(ret);
    }

    /**
     * v1.3.11 — Ramene l'app BJ Genius au premier plan. Utile pour que JS
     * puisse demander au natif de redonner le focus a la WebView quand un
     * tuto premiere-utilisation doit s'afficher (mic ou scan).
     *
     * Comportement : si l'app est deja au premier plan, no-op. Si elle est
     * en background, elle est ramenee au top via le launch intent standard.
     */
    @PluginMethod
    public void bringToFront(PluginCall call) {
        try {
            android.content.Intent intent = getContext().getPackageManager()
                .getLaunchIntentForPackage(getContext().getPackageName());
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                getContext().startActivity(intent);
            }
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            Log.w(TAG, "bringToFront failed", e);
            call.reject("BRING_TO_FRONT_FAILED: " + e.getMessage());
        }
    }

    /**
     * v1.3 — Met a jour le rectangle decision affiche au-dessus de la bulle.
     * Appele depuis JS chaque fois que la decision change (suite a une carte
     * dictee, un tap sur une carte dans l'app, etc.).
     *
     * v1.3.6 — CRITIQUE : delegation au main thread car updateViewLayout()
     * doit etre appele sur le UI thread, et Capacitor execute les PluginMethod
     * sur un worker thread.
     *
     * Params attendus : { text: "TIRER", color: "#22c55e" }
     */
    @PluginMethod
    public void setDecision(PluginCall call) {
        final String text = call.getString("text", "");
        final String color = call.getString("color"); // null si absent
        try {
            getBridge().getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BJGeniusBubbleService.setDecision(text == null ? "" : text, color);
                    } catch (Exception e) {
                        Log.w(TAG, "setDecision on UI thread failed", e);
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Cannot post setDecision to UI thread", e);
        }
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    /**
     * v1.3 — Met a jour la couleur de la sous-bulle mic.
     * v1.3.6 — Idem setDecision : sur main thread.
     */
    @PluginMethod
    public void setMicState(PluginCall call) {
        final boolean active = Boolean.TRUE.equals(call.getBoolean("active", false));
        try {
            getBridge().getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BJGeniusBubbleService.setMicState(active);
                    } catch (Exception e) {
                        Log.w(TAG, "setMicState on UI thread failed", e);
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Cannot post setMicState to UI thread", e);
        }
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }
}
