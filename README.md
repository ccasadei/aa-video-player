# AA Video Player

Player video **a tutto schermo** per **Android Auto**: file locali, flussi HLS/MP4, YouTube, StreamingCommunity e dirette sportive di Streamed.pk, scelti direttamente dallo schermo dell'auto.

> **In English** — Full-screen video player for Android Auto. It plays local files, HLS/MP4 streams, YouTube (search and live), StreamingCommunity movies and series and Streamed.pk live sports, all browsable from the car screen with voice search. It takes the whole display (navigation category), keeps the original aspect ratio with black bars (or zoom/stretch) and recovers automatically from expired links, network drops and surface changes. Documentation and UI are in Italian. If you find it useful, you can support it via [PayPal](https://paypal.me/ccasadei74).

> [!IMPORTANT]
> Progetto personale e **non ufficiale**, non affiliato né approvato da Google, YouTube o dai siti da cui legge i contenuti. I marchi citati appartengono ai rispettivi proprietari e i contenuti restano dei rispettivi titolari: sta a te usarli nel rispetto delle leggi e dei diritti d'autore del tuo paese. **Guardare video mentre guidi è pericoloso e vietato**: usa l'app solo a veicolo fermo, ad esempio durante una ricarica.

## Funzionalità

* **Video a tutto schermo su Android Auto**: l'app si registra come app di navigazione e occupa l'intero display, senza Google Maps a lato.
* **Tre modalità di scala**: **Originale** (proporzioni intatte con bande nere, predefinita), **Zoom** (riempie lo schermo tagliando i bordi) e **Stira**.
* **Sorgenti**:
  * file video del telefono (`.mp4`, `.mkv`, `.webm`) e URL diretti (`.m3u8`, `.mp4`);
  * **YouTube**: ricerca, categorie rapide e dirette, con qualità adattiva fino a 1080p;
  * **StreamingCommunity**: film e serie TV con scelta di stagione ed episodio;
  * **Streamed.pk**: dirette sportive del momento, per sport o per ricerca.
* **Scelta dallo schermo dell'auto** con ricerca vocale o da tastiera, senza toccare il telefono.
* **Playlist** con ripresa automatica dal punto in cui avevi lasciato, ripetizione del singolo video o dell'intera playlist, eliminazione anche da Android Auto.
* **Barra di avanzamento a scomparsa**, salti di ±30 secondi, play/pausa con un tocco, swipe per cambiare video.
* **Modalità silenziosa**: il video gira senza audio e lascia libera la musica o la radio dell'auto.
* **App sul telefono** per gestire la playlist, aggiungere file e URL o condividere un link verso l'app ("Condividi → AA Video").
* **Recupero automatico** da link scaduti, rete persa, dirette rimaste indietro e cambi della superficie di Android Auto; pausa automatica quando l'auto si disconnette.

## Requisiti

* **Telefono**: Android 10 o successivo, con Android Auto.
* **Auto o simulatore**: Android Auto con le opzioni sviluppatore attive. Per le prove sul PC va bene il Desktop Head Unit dell'Android SDK.
* **Connessione dati**: per i contenuti in streaming.

## Compilazione

Servono JDK 17, Android SDK (compileSdk 34) e Gradle 8.9: il repository non include il Gradle wrapper.

```bash
export JAVA_HOME=/percorso/jdk-17
export ANDROID_HOME=/percorso/android-sdk   # in alternativa sdk.dir in local.properties
gradle assembleDebug                        # APK in app/build/outputs/apk/debug/
gradle testDebugUnitTest                    # test JVM, nessun dispositivo necessario
```

## Installazione

L'app sul telefono si installa come un normale APK. Android Auto però mostra solo le app di navigazione e "template" installate dal Play Store, anche con le opzioni sviluppatore attive: lo script [`install_aa.sh`](install_aa.sh) compila e installa l'app con la procedura descritta in [`AGENTS.md`](AGENTS.md) (§3).

```bash
./install_aa.sh   # legge i percorsi da variabili d'ambiente o da install_aa.local.sh
```

Al primo avvio la playlist è vuota: su Android Auto tocca lo schermo, poi **Sfoglia**, e scegli una sorgente.

## Documentazione tecnica

[`AGENTS.md`](AGENTS.md) raccoglie le conoscenze del progetto:
* l'installazione per Android Auto e le prove con il Desktop Head Unit;
* l'architettura del player e dell'uscita video su Android Auto (VirtualDisplay con proporzioni gestite dal layout);
* i limiti dei template di Android Auto e come sono organizzati i menu;
* come vengono estratti i flussi delle varie sorgenti e come l'app si riprende dagli errori.

## Contribuire

* Le modifiche arrivano su `main` tramite pull request unite in **squash**.
* Il titolo della PR segue i [Conventional Commits](https://www.conventionalcommits.org/it/) (`feat:`, `fix:`, `perf:`, `docs:`, ...).
* La versione (`versionName` in `app/build.gradle.kts`) si aggiorna a mano: non ci sono release automatiche.

## Sostieni il progetto

AA Video Player è gratuito e sviluppato nel tempo libero. Se ti è utile, puoi offrirmi qualcosa con PayPal:

* [☕ Un caffè — 3 €](https://paypal.me/ccasadei74/3EUR)
* [🍿 I popcorn per il film — 5 €](https://paypal.me/ccasadei74/5EUR)
* [🍕 Una pizza — 10 €](https://paypal.me/ccasadei74/10EUR)
* [💚 Importo libero](https://paypal.me/ccasadei74)

Gli importi sono solo suggerimenti: puoi sempre cambiarli prima di confermare. Grazie!

## Autore

Made with ❤️ by Cristiano Casadei.
