# AGENTS.md — AA Video Player (video a tutto schermo su Android Auto)

Conoscenze del progetto per chi ci lavora (persone o agenti): ambiente, installazione per Android Auto, prove con il Desktop Head Unit, architettura del player, limiti di Android Auto, sorgenti e gestione degli errori.

## 1. Ambiente di sviluppo

* **JDK 17**, **Android SDK** (compileSdk/targetSdk 34, minSdk 29) e **Gradle 8.9**: il repository non include il Gradle wrapper.
* Librerie principali: AGP 8.5.2, Kotlin 1.9.24, Media3 1.4.1 (ExoPlayer, HLS, UI), Car App Library 1.4.0, OkHttp 4.12, Jsoup, NewPipeExtractor v0.26.5 (da JitPack come `com.github.TeamNewPipe:NewPipeExtractor`).

### Comandi

```bash
export JAVA_HOME=/percorso/jdk-17
export ANDROID_HOME=/percorso/android-sdk      # oppure sdk.dir in local.properties
gradle assembleDebug                           # APK in app/build/outputs/apk/debug/
gradle testDebugUnitTest                       # test JVM sulle regole pure, nessun dispositivo
./install_aa.sh                                # compila e installa per Android Auto (vedi §3)
```

`install_aa.sh` legge `JAVA_HOME`, `ANDROID_HOME`, `GRADLE` e `ADB` dall'ambiente oppure da `install_aa.local.sh` (accanto allo script, escluso dal repository).

### Commit e versioni

* Le modifiche arrivano su `main` solo tramite pull request unite in **squash**; `main` non accetta push diretti, force push né cancellazioni.
* Il titolo della PR diventa il commit su `main` e segue i Conventional Commits (`feat:`, `fix:`, `perf:`, `docs:`, ...): lo verifica il workflow `.github/workflows/pr-title.yml`.
* Non ci sono release automatiche: `versionName` in `app/build.gradle.kts` si aggiorna a mano.

## 2. Struttura del codice

| Package | Contenuto |
|---|---|
| `car/` | `VideoCarAppService` e la sessione Android Auto, `VideoSurfaceScreen` (NavigationTemplate + SurfaceCallback), `CarBrowseScreens` (menu "Sfoglia") |
| `player/` | `VideoPlayerController` (ExoPlayer unico condiviso tra telefono e auto), `CarVideoOutput` (uscita video su VirtualDisplay), `ScreensaverRepository` (playlist e posizioni salvate), regole pure: `ResumePolicy`, `PlaybackPolicy`, `ErrorRecoveryPolicy`, `PlaylistOps`, `ScalePolicy` |
| `extractor/` | YouTube, StreamingCommunity (catalogo ed estrazione), Streamed.pk, `HeadlessStreamSniffer` (WebView invisibile) |
| `phone/` | `MainActivity` (playlist, impostazioni, condivisione) e il browser StreamingCommunity sul telefono |
| `model/` | Modelli: elementi della playlist, stato del player, modalità di scala |

## 3. CRITICO: installazione per Android Auto (`CAR.VALIDATOR`)

### Il problema

Android Auto mostra solo le app di navigazione e "template" installate dal Play Store: un APK installato con `adb install` o dal file manager viene scartato (`CAR.VALIDATOR: Package DENIED`), anche con le opzioni sviluppatore attive e le origini sconosciute abilitate.

### La soluzione (`install_aa.sh`)

1. Copia l'APK in `/sdcard/Download` con un nome univoco e lo indicizza nel MediaStore dalla shell adb (UID 2000), per ottenere un URI `content://media/external/downloads/<id>`.
2. Avvia `com.google.android.packageinstaller/com.android.packageinstaller.InstallStart` su quell'URI con gli extra `ORIGINATING_UID=2000`, `NOT_UNKNOWN_SOURCE=true` e `INSTALLER_PACKAGE_NAME=com.android.vending`: l'app risulta installata dal Play Store (`installerPackageName=com.android.vending`) e Android Auto la accetta.
3. Conferma il dialogo dell'installer (TAB, freccia destra, INVIO) **solo se l'installer è davvero in primo piano**: altrimenti i tasti finirebbero nell'app che si sta usando sul telefono.
4. Se compare Play Protect tocca i pulsanti cercandoli **per testo** con `uiautomator` ("Analizza l'app", "Installa"), mai per coordinate fisse: "Annulla e non installare" è a pochi pixel. L'analisi può durare decine di secondi; lo script attende fino a 90 s e termina con errore se `lastUpdateTime` non cambia.
5. Riavvia Android Auto (`am force-stop com.google.android.projection.gearhead`) **solo se le risorse dell'APK sono cambiate** (confronto di `resources.arsc` con l'APK installato). Se le risorse cambiano e Android Auto resta aperto, il suo processo `gearhead:projection` carica le icone dell'app con gli ID vecchi e va in crash (`Resources$NotFoundException`). Se non cambiano, la sessione Android Auto o del DHU in corso resta attiva.

Verifica: `adb shell dumpsys package com.casadei.aavideoscreensaver | grep -E "installerPackageName|lastUpdateTime"`.

## 4. Prove con il Desktop Head Unit (DHU)

* Sul telefono, in Android Auto → impostazioni sviluppatore, avviare il **server unità principale**; poi `adb forward tcp:5277 tcp:5277` e lanciare `$ANDROID_HOME/extras/google/auto/desktop-head-unit` (configurazioni in `config/`, es. `default_wide.ini`). Con quella configurazione la superficie dell'app è 1190×460.
* Catture della finestra del DHU: `import -window <id>` (ImageMagick), con l'id trovato da `xdotool search --name "Desktop Head Unit"`.
* Su Wayland i clic simulati con `xdotool` **non arrivano** al DHU. Per comandare il player senza toccare l'auto:
  * i pulsanti dell'app sul telefono con `adb shell input tap X Y` (coordinate da `adb exec-out screencap -p`; `uiautomator dump` non funziona su `MainActivity` perché la schermata si aggiorna ogni secondo);
  * oppure una condivisione: `adb shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '<url>' -n com.casadei.aavideoscreensaver/.phone.MainActivity"`.

## 5. Architettura del player e dell'uscita video

* **Un solo ExoPlayer** (`VideoPlayerController`), condiviso tra telefono e auto; lo stato è uno `StateFlow<PlayerUiState>` osservato da entrambe le interfacce.
* **Un elemento alla volta**: il player usa `REPEAT_MODE_OFF` e la fine del contenuto la decide `PlaybackPolicy` (successivo o ricomincia). Con `REPEAT_MODE_ONE/ALL` su un solo elemento `STATE_ENDED` non arriverebbe mai e la playlist non avanzerebbe. Un video lungo che "finisce" pochi secondi dopo l'avvio ricomincia invece di saltare al successivo.
* **Uscita su Android Auto (`CarVideoOutput`)**: sulla superficie fornita da Android Auto (`SurfaceContainer.surface`) viene creato un **VirtualDisplay privato** (`VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`) con una `android.app.Presentation` che contiene una `SurfaceView` dentro un `AspectRatioFrameLayout`:
  * le tre modalità di scala sono `RESIZE_MODE_FIT` (Originale, bande nere), `RESIZE_MODE_ZOOM` (Zoom) e `RESIZE_MODE_FILL` (Stira);
  * quando Android Auto distrugge o sostituisce la superficie (ad esempio passando da "Sfoglia") cambia solo `VirtualDisplay.setSurface()`: la superficie su cui scrive il decoder resta sempre valida;
  * se il VirtualDisplay non si può creare, il player torna alla superficie diretta: lì il decoder sa solo stirare (`SCALE_TO_FIT`) o ritagliare (`SCALE_TO_FIT_WITH_CROPPING`), quindi "Originale" si comporta come "Stira".
* **Perché non gli effetti di Media3**: con Media3 1.4.1 la catena di effetti (`Presentation` di `media3-effect`) si blocca al cambio di contenuto (`FinalShaderWrapper: Output surface and size not set`, poi `Detaching surface timed out`); inoltre, una volta attiva, il renderer non torna più alla superficie diretta.
* **Ripresa della posizione**: `ResumePolicy` verifica la posizione salvata contro la durata reale quando la timeline non è più segnaposto (`onTimelineChanged`); le dirette non riprendono e non salvano posizioni. Le chiavi delle posizioni (`normalizeProgressKey`) sono normalizzate solo per gli host vidxgo/d2b.you e YouTube.
* Un contatore di caricamento scarta i tentativi ritardati (retry del decoder, rete) che appartengono a un elemento precedente.
* Playlist modificata solo **per id** (`PlaylistOps`): l'indice mostrato in una lista può essere già vecchio quando l'utente tocca la riga.
* Fine sessione Android Auto → salvataggio della posizione e pausa; aprire l'app sul telefono non avvia la riproduzione.
* Buffer (`DefaultLoadControl`): minimo 20 s, massimo 60 s, avvio con 1,5 s, ripartenza dopo un'interruzione con 8 s (meno pause quando la rete è appena sotto il bitrate di un flusso a qualità unica). Risoluzione massima 1920×1080.

## 6. Android Auto: limiti dei template e menu

* L'host consente **al massimo 5 template per task** e il quinto deve essere Navigation/Pane/Message/...; oltre, mostra un errore e chiude l'app. Il `NavigationTemplate` azzera il conteggio.
* Un `ListTemplate` aggiornato conta come refresh (gratuito) solo se titolo, numero di righe e titoli delle righe restano uguali; un `SearchTemplate` considera refresh **qualsiasi** cambio di contenuto.
* Struttura dei menu (profondità massima 4):
  1. `VideoSurfaceScreen` (NavigationTemplate)
  2. `CarBrowseHubScreen` (ListTemplate con righe statiche)
  3. browser del provider (`CarBrowserScreen` su SearchTemplate: sottomenu, pagine, ricerca e modalità elimina restano nella stessa schermata)
  4. solo gli episodi di una serie (SearchTemplate) e le sorgenti di un evento Streamed.pk (ListTemplate senza pagine)
* Scegliere un contenuto avvia la riproduzione e torna alla radice (`popToRoot`). I push sono protetti dal doppio tocco (`pushIfOnTop`).

## 7. Sorgenti

### YouTube

Strategie in ordine (`YouTubeStrategy`): **NEWPIPE → ANDROID → IOS → ADAPTIVE**. Verifiche di ottobre 2026:
* NewPipeExtractor 0.26.5 fornisce un **HLS adattivo** (fino a 1080p H.264) anche per i video normali, con URL scaricabili senza limitazioni; estrazione in circa 1,5–2,5 s.
* InnerTube ANDROID restituisce solo il formato muxed itag 18 (360p) e l'HLS delle dirette; i formati adattivi di InnerTube senza poToken rispondono 403; IOS per i video normali non offre formati utilizzabili.
* Un 403 **prima** che il video parta esclude la strategia per quell'elemento; un 403 **dopo** (URL scaduto o cambio di IP, ad esempio Wi-Fi → rete mobile) rigenera il link con la stessa strategia.

### StreamingCommunity

* Il player `v.vidxgo.co/{imdb}` (o `/{imdb}/{stagione}/{episodio}`) contiene script offuscati (XOR + base64) con l'URL del master HLS su `*.d2b.you`, firmato con `t=` ed `e=` (scadenza di circa 180 s).
* `VidxgoTokenRefreshInterceptor` (sul client OkHttp del player) riscrive `t`/`e` su manifest e segmenti, rinnovando il token 40 s prima della scadenza o dopo un 403/410.
* Catalogo dal sito StreamingCommunity con Cinemeta come riserva; le stagioni e il numero di episodi pubblicati vengono letti dal player (`"seasons":[{"n":..,"count":..}]`) per nascondere gli episodi non ancora disponibili. Un episodio non disponibile dà un errore chiaro: nessun ripiego su un altro episodio.
* Di solito c'è un solo flusso 1080p a circa 3 Mbit/s.

### Streamed.pk

* API `streamed.pk/api` (dirette, eventi del giorno, sport, sorgenti di un evento). Le pagine `embed.st` vengono aperte in una WebView invisibile (`HeadlessStreamSniffer`) che toglie gli script pubblicitari, segue gli iframe e intercetta il primo `.m3u8`.
* Prima di usare il flusso, `isM3u8Reachable` ne verifica il contenuto: alcuni server rispondono HTTP 200 con "Not found".
* Al massimo 3 sorgenti dello stesso evento e 30 s complessivi; ogni chiamata all'API ha un limite di 6 s; le sorgenti alternative e il calendario del giorno vengono riusati dalle schermate appena viste.
* L'app non passa mai a un evento diverso da quello scelto; per un evento che inizia più tardi mostra l'orario.

## 8. Gestione degli errori e log

`ErrorRecoveryPolicy` decide la reazione agli errori di ExoPlayer (i contatori si azzerano quando il player torna pronto):

| Errore | Reazione |
|---|---|
| `BEHIND_LIVE_WINDOW` | riaggancio al punto live (max 3) |
| Decoder non inizializzato | nuovo tentativo dopo 300 ms (max 2) |
| Superficie non utilizzabile (`FAILED_RUNTIME_CHECK` con `CodecException`, `VIDEO_FRAME_PROCESSING_FAILED`) | si continua senza video in attesa della nuova superficie |
| Rete assente o timeout | nuovi tentativi dopo 3, 6 e 9 s |
| HTTP 401/403/410 e simili | nuova estrazione del link dalla stessa posizione (YouTube fino a 3 volte, gli altri 2) |
| HTTP 404 | errore, tranne per Streamed.pk dove il link della diretta ruota |
| Manifest non valido su Streamed.pk | nuova estrazione |

Log: tag `AAVideo`. Utili `adb logcat -G 16M` (buffer più grande) e `adb logcat -s AAVideo ExoPlayerImplInternal`; il log riporta anche le tracce video disponibili e la risoluzione in uso.

I test JVM (`app/src/test`) coprono le regole pure (ripresa, fine contenuto, recupero dagli errori, playlist, chiavi delle posizioni, scelta dei formati YouTube, episodi disponibili, eventi Streamed.pk).
