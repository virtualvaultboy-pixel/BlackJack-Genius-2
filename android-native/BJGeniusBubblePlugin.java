package studio.deponchy.bjgenius;

import android.content.Intent;
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
 * Plugin Capacitor : expose au JS les methodes start/stop/check de la bulle
 * flottante BJ Genius Live.
 *
 * Usage JS :
 *   const { BJGeniusBubble } = window.Capacitor.Plugins;
 *   const perm = await BJGeniusBubble.checkOverlayPermission();
 *   if (!perm.granted) await BJGeniusBubble.requestOverlayPermission();
 *   await BJGeniusBubble.start();
 *   await BJGeniusBubble.stop();
 */
@CapacitorPlugin(name = "BJGeniusBubble")
public class BJGeniusBubblePlugin extends Plugin {

    private static final String TAG = "BJGeniusBubble";

    /**
     * Verifie si la permission "Display over other apps" est accordee.
     * Sur Android < M (rare aujourd'hui), c'est toujours accorde via le manifest.
     */
    @PluginMethod
    public void checkOverlayPermission(PluginCall call) {
        boolean granted = true; // par defaut pre-Android M
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            granted = Settings.canDrawOverlays(getContext());
        }
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }

    /**
     * Ouvre les parametres systeme pour que l'user accorde la permission.
     * L'app revient en foreground quand l'user appuie sur le back system.
     * Le JS doit re-check avec checkOverlayPermission() au resume.
     */
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

    /**
     * Lance la bulle flottante. Echoue si la permission overlay n'est pas
     * accordee — le JS doit avoir verifie au prealable via checkOverlayPermission.
     */
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

    /**
     * Arrete la bulle et le service foreground.
     */
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

    /**
     * Renvoie l'etat actuel (bulle active ou non).
     *
     * v1.2 — Acces a la propriete Kotlin via le getter genere. Pour une
     * propriete Kotlin booleenne nommee `isRunning` avec `@JvmStatic` dans
     * un companion object, le getter Java genere s'appelle **isRunning()**
     * (PAS getIsRunning() — Kotlin omet le prefixe `get` pour les booleens
     * deja prefixes par `is`).
     */
    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", BJGeniusBubbleService.isRunning());
        call.resolve(ret);
    }
}
