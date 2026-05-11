# BJ Genius Live — Sources Android natives

Ce dossier contient les fichiers Kotlin et Java de la bulle flottante
**BJ Genius Live** (v1.2+).

Le workflow `build-apk.yml` les copie automatiquement vers le bon emplacement
du projet Android au moment du build :

```
android-native/*.kt  → android/app/src/main/java/studio/deponchy/bjgenius/
android-native/*.java → android/app/src/main/java/studio/deponchy/bjgenius/
```

## Fichiers

| Fichier | Rôle |
|---|---|
| `BJGeniusBubbleService.kt` | Foreground service Android qui maintient la bulle visible |
| `BubbleOverlayView.kt` | Vue Kotlin de la bulle draggable (cercle doré "BJ") |
| `BJGeniusBubblePlugin.java` | Plugin Capacitor exposant start/stop/checkPermission au JS |

## Package

`studio.deponchy.bjgenius` — doit être identique au `applicationId` de
`android/app/build.gradle` et au `appId` de `capacitor.config.json`.

## Modification

Tout changement de ces fichiers doit déclencher un rebuild du workflow.
Ces fichiers ne sont PAS modifiés en local par Capacitor sync — ils sont
toujours réécrits depuis ce dossier source au build.

## Tests

Pour tester localement (PC avec Android SDK) :
1. `cap sync android`
2. `cp android-native/*.{kt,java} android/app/src/main/java/studio/deponchy/bjgenius/`
3. `cd android && ./gradlew assembleDebug`

Pour Aurélien (sans PC) : push sur le repo → onglet Actions → download APK.
