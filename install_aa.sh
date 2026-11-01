#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Percorsi della macchina locale (JAVA_HOME, ANDROID_HOME, GRADLE, ADB): si possono esportare prima di lanciare
# lo script oppure scrivere in install_aa.local.sh accanto allo script (escluso dal repository).
if [ -f "$SCRIPT_DIR/install_aa.local.sh" ]; then
    # shellcheck source=/dev/null
    source "$SCRIPT_DIR/install_aa.local.sh"
fi
: "${ANDROID_HOME:?Imposta ANDROID_HOME con la cartella Android SDK oppure crea install_aa.local.sh}"
export ANDROID_HOME
[ -n "${JAVA_HOME:-}" ] && export JAVA_HOME
GRADLE="${GRADLE:-gradle}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.casadei.aavideoscreensaver"

cd "$SCRIPT_DIR"

echo "==> 0. Compilazione APK aggiornato (gradle assembleDebug)..."
"$GRADLE" assembleDebug -q

if [ ! -f "$APK" ]; then
    echo "Errore: APK non trovato in $APK. Esegui prima gradle assembleDebug."
    exit 1
fi

echo "==> Verifica dispositivo collegato via ADB..."
$ADB wait-for-device

OLD_UPDATE_TIME=$($ADB shell dumpsys package "$PKG" 2>/dev/null | grep "lastUpdateTime=" | head -n 1 | tr -d '\r' || true)

# Impronta delle risorse dell'APK installato: se cambiano (es. nuova libreria con risorse) Android Auto, rimasto
# aperto, userebbe ID di icone non più validi e andrebbe in crash (Resources$NotFoundException) finché non si riavvia.
TMP_APK=$(mktemp)
OLD_RES_HASH=""
INSTALLED_PATH=$($ADB shell pm path "$PKG" 2>/dev/null | head -n 1 | sed 's/package://' | tr -d '\r' || true)
if [ -n "$INSTALLED_PATH" ] && $ADB pull "$INSTALLED_PATH" "$TMP_APK" >/dev/null 2>&1; then
    OLD_RES_HASH=$(unzip -p "$TMP_APK" resources.arsc 2>/dev/null | sha1sum | cut -d' ' -f1)
fi
rm -f "$TMP_APK"
NEW_RES_HASH=$(unzip -p "$APK" resources.arsc 2>/dev/null | sha1sum | cut -d' ' -f1)
echo "    lastUpdateTime precedente: ${OLD_UPDATE_TIME:-nessuno}"

FNAME="aa-video-screensaver-$(date +%s).apk"
echo "==> 1. Copia APK in /sdcard/Download/$FNAME e indicizzazione MediaStore da UID 2000..."
$ADB shell input keyevent KEYCODE_WAKEUP || true
$ADB shell wm dismiss-keyguard || true
$ADB shell am force-stop com.google.android.packageinstaller
$ADB push "$APK" "/sdcard/Download/$FNAME"
$ADB shell content call --uri content://media --method scan_file --arg "/storage/emulated/0/Download/$FNAME"

ID=$($ADB shell content query --uri content://media/external/downloads --projection _id:_display_name \
  | grep "$FNAME" | head -n 1 | sed -E 's/.*_id=([0-9]+),.*/\1/' | tr -d '\r')

if [ -z "$ID" ]; then
    echo "Errore: Impossibile ottenere MediaStore _id per $FNAME"
    exit 1
fi
echo "    MediaStore _id rilevato: $ID"

echo "==> 2. Avvio InstallStart con ORIGINATING_UID=2000, NOT_UNKNOWN_SOURCE=true, INSTALLER_PACKAGE_NAME=com.android.vending..."
$ADB shell am start -a android.intent.action.VIEW \
  -d "content://media/external/downloads/$ID" \
  -t "application/vnd.android.package-archive" \
  --grant-read-uri-permission \
  --ei android.intent.extra.ORIGINATING_UID 2000 \
  --ez android.intent.extra.NOT_UNKNOWN_SOURCE true \
  --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending \
  -n com.google.android.packageinstaller/com.android.packageinstaller.InstallStart

echo "==> 3. Conferma dialogo PackageInstaller (TAB + DPAD_RIGHT + ENTER) e attesa installazione..."
# I tasti vengono inviati solo se in primo piano c'è davvero l'installer: altrimenti finirebbero
# nell'app che l'utente sta usando in quel momento.
INSTALLER_FOCUSED=""
for i in {1..10}; do
    sleep 0.5
    if $ADB shell dumpsys window 2>/dev/null | grep "mCurrentFocus" | grep -qi "packageinstaller"; then
        INSTALLER_FOCUSED="yes"
        break
    fi
done
if [ -n "$INSTALLER_FOCUSED" ]; then
    $ADB shell input keyevent KEYCODE_TAB || true
    sleep 0.3
    $ADB shell input keyevent KEYCODE_DPAD_RIGHT || true
    sleep 0.3
    $ADB shell input keyevent KEYCODE_ENTER || true
else
    echo "    ATTENZIONE: il dialogo di installazione non è in primo piano (telefono in uso?). Nessun tasto inviato."
fi

# Tocca un pulsante di Play Protect cercandolo per testo (mai per coordinate fisse: il pulsante
# "Annulla e non installare" si trova a pochi pixel dalle vecchie coordinate).
tap_play_protect_button() {
    $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
    local coords
    coords=$($ADB shell cat /sdcard/ui.xml | python3 -c '
import re, sys
xml = sys.stdin.read()
allowed = ["Analizza l\x27app", "Installa", "Installa comunque", "Installa senza analizzare", "Scan app", "Install", "Install anyway"]
for m in re.finditer(r"<node [^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml):
    text, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
    if text.replace("&apos;", "\x27") in allowed:
        print((x1 + x2) // 2, (y1 + y2) // 2, text)
        break
' || true)
    $ADB shell rm -f /sdcard/ui.xml >/dev/null 2>&1 || true
    if [ -n "$coords" ]; then
        echo "    [Play Protect] Tocco: ${coords#* * }"
        $ADB shell input tap $(echo "$coords" | awk '{print $1, $2}') || true
        return 0
    fi
    return 1
}

# L'analisi di Play Protect può richiedere decine di secondi: si attende fino a 90 s
for attempt in {1..45}; do
    sleep 2
    FOCUS=$($ADB shell dumpsys window 2>/dev/null | grep "mCurrentFocus" || true)
    if echo "$FOCUS" | grep -q "PlayProtectDialogsActivity"; then
        tap_play_protect_button || echo "    [Play Protect] Analisi in corso, attendo..."
    fi
    NEW_UPDATE_TIME=$($ADB shell dumpsys package "$PKG" 2>/dev/null | grep "lastUpdateTime=" | head -n 1 | tr -d '\r' || true)
    if [ -n "$NEW_UPDATE_TIME" ] && [ "$NEW_UPDATE_TIME" != "$OLD_UPDATE_TIME" ]; then
        echo "    Aggiornamento completato! $NEW_UPDATE_TIME"
        break
    fi
done

$ADB shell am force-stop com.google.android.packageinstaller || true
$ADB shell rm -f "/sdcard/Download/$FNAME" || true

echo "==> 4. Verifica CAR.VALIDATOR (installerPackageName, initiatingPackageName, lastUpdateTime):"
$ADB shell dumpsys package "$PKG" | grep -E "installerPackageName|initiatingPackageName|lastUpdateTime" || true

FINAL_UPDATE_TIME=$($ADB shell dumpsys package "$PKG" 2>/dev/null | grep "lastUpdateTime=" | head -n 1 | tr -d '\r' || true)
if [ "$FINAL_UPDATE_TIME" = "$OLD_UPDATE_TIME" ]; then
    echo "ERRORE: l'app NON è stata aggiornata (lastUpdateTime invariato). Sblocca il telefono, chiudi le altre app e riprova."
    exit 1
fi

if [ -n "$OLD_RES_HASH" ] && [ "$OLD_RES_HASH" = "$NEW_RES_HASH" ]; then
    echo "==> 5. Risorse invariate: Android Auto non viene riavviato (la sessione AA/DHU in corso resta attiva)"
elif [ -n "${SKIP_GEARHEAD_RESTART:-}" ]; then
    echo "==> 5. ATTENZIONE: risorse cambiate ma riavvio di Android Auto saltato (SKIP_GEARHEAD_RESTART)."
    echo "       Android Auto può andare in crash sulle icone dell'app finché non viene riavviato."
else
    echo "==> 5. Risorse cambiate: riavvio di Android Auto (Gearhead) per ricaricarle..."
    $ADB shell am force-stop com.google.android.projection.gearhead || true
fi
echo "==> Installazione completata!"
