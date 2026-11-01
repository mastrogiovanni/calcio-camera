# Calcio Camera

App nativa Android che acquisisce la fotocamera e pubblica una sorgente **NDI® High Bandwidth** per OBS tramite DistroAV. Android 9 o successivo, processore ARM64. L'app usa Camera2 e il runtime NDI ufficiale, chiamato attraverso JNA: non servono server intermedi.

**Stato:** runtime ufficiale Android **NDI 6.3.2.0** scaricato e integrato nell'APK ARM64. Invio e ricezione NDI verificati su emulatore Android 14 ARM64, con due cicli di arresto e riavvio. La ricezione in OBS da un telefono fisico sulla tua rete resta da collaudare.

Funzioni implementate:

- Anteprima locale e selezione fotocamera anteriore/posteriore.
- Richiesta 720p/1080p e 15/30 fps; scelta di un formato supportato dal dispositivo, con dimensioni effettive mostrate a schermo.
- Nome sorgente persistente, avvio/arresto e conteggio dei ricevitori dal runtime NDI.
- FPS e frame inviati misurati, torcia se disponibile, indirizzo IPv4 della LAN.
- Rotazione fisica del video I420, gestione di stride e piani YUV interlacciati.
- Schermo acceso, lock Wi-Fi/multicast durante l'invio, rilascio della fotocamera e del sender passando in background.
- Guida OBS integrata. Nessun account, backend o servizio cloud. Solo video: microfono non acquisito.

## Aprire e compilare

Apri questa cartella con Android Studio. Servono JDK 17 o successivo (consigliato il JBR incluso in Android Studio), SDK Platform **36.1** e Build Tools 36.x. Gradle Wrapper 9.4.1 e Android Gradle Plugin 9.2.1 sono configurati nel progetto.

Android Studio configura `local.properties`; in alternativa imposta `ANDROID_HOME` oppure crea `local.properties` con `sdk.dir=/percorso/Android/sdk`.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Si può installare dal file oppure con:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

È una build di sviluppo firmata con la chiave debug locale. Non è una release per Play Store.

Una copia della build con NDI già integrato è disponibile in `dist/calcio-camera-0.1.0-debug-arm64.apk` (circa 5 MB).

## Runtime NDI incluso

La build locale contiene già `app/src/main/jniLibs/arm64-v8a/libndi.so`; per installare l'APK non serve scaricare altro sul telefono. Il pacchetto originale è conservato in `vendor/downloads/` e l'SDK estratto in `vendor/NDI SDK for Android/`. Queste directory e i binari `.so` sono esclusi dal controllo versione.

- Download: [SDK Android NDI 6, server ufficiale](https://downloads.ndi.tv/SDK/NDI_SDK_Android/Install_NDI_SDK_v6_Android.tar.gz).
- Versione del pacchetto: `NDI 2026-04-13 git-5396c5f15 v6.3.2.0`.
- SHA-256 archivio: `895fe323f7329af86df5e0a8ad01f708d30362ff2771ae1179794dc1b4ed8668`.
- SHA-256 libreria ARM64: `09848dc0a66c79fb4918193517b65ad374af9a4e393db6bfb0c4f4c93ee4fccb`.

La libreria dipende soltanto da `libdl.so`, `libm.so` e `libc.so` di Android. I segmenti ELF hanno allineamento 16 KiB. Gli avvisi originali e la licenza SDK sono inclusi nell'APK in `assets/licenses/`. L'SDK standard integrato è distinto dalle trial Advanced.

### Ripristinare il runtime su un altro checkout

1. Scarica l'SDK Android dal [server ufficiale](https://downloads.ndi.tv/SDK/NDI_SDK_Android/Install_NDI_SDK_v6_Android.tar.gz), estrai l'archivio ed esegui l'installer nella cartella desiderata secondo i termini inclusi. Il runtime desktop non è utilizzabile su Android.
2. Estrai l'SDK e importa il runtime:

```sh
python3 scripts/import-ndi.py "/percorso/SDK Android"
```

Puoi indicare direttamente il file `libndi.so` se l'SDK contiene più versioni. Lo script controlla formato ELF, architettura ARM64, dipendenza Bionic e simbolo di invio NDI. Non scarica l'SDK e non ne accetta i termini al tuo posto. Il controllo non garantisce tutte le dipendenze o la compatibilità con ogni versione Android. Se l'SDK richiede ulteriori librerie Android, inseriscile accanto a `libndi.so`, preservandone i nomi. Se il pacchetto offre solo librerie statiche `.a`, serve un bridge JNI aggiuntivo: questa implementazione richiede la libreria condivisa.

3. Compila e reinstalla:

```sh
./gradlew assembleNdiDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`assembleNdiDebug` fallisce esplicitamente se il runtime è assente. `assembleDebug` permette invece di compilare l'anteprima senza SDK. Il runtime viene incluso nell'APK e resta escluso dal controllo versione. Conserva anche gli avvisi richiesti dalla licenza del tuo SDK; quelli della versione integrata sono già presenti in `app/src/main/assets/licenses/`.

## Collegare OBS

Per montare l'impianto senza Wi-Fi, confrontare telefoni Ethernet e camere PoE e seguire la prima configurazione: [guida pratica alla rete cablata](docs/GUIDA_RETE_CABLATA.md). La guida distingue la produzione OBS già utilizzabile dal servizio Mac per regia automatica e analisi ancora da sviluppare.

1. Telefono e computer sulla stessa LAN. Preferisci Wi-Fi a 5/6 GHz oppure Ethernet USB sul telefono. Evita reti ospiti con isolamento tra client.
2. Sul computer installa [DistroAV](https://github.com/DistroAV/DistroAV) e il runtime NDI richiesto dalla versione del plugin. Le versioni correnti documentano OBS 31.1.1+ e NDI Runtime 6.3+; controlla i requisiti della release che installi.
3. Riavvia OBS. Apri Calcio Camera, consenti la fotocamera e premi **Avvia trasmissione**.
4. In OBS: **Fonti → + → NDI Source**, quindi seleziona il nome completo mostrato nell'app, tipicamente `TELEFONO (Calcio Camera)`.
5. Il conteggio ricevitori aumenta quando un ricevitore si collega. Esso misura connessioni NDI, non identifica specificamente OBS.

Se la sorgente non appare, prova prima NDI Video Monitor/Studio Monitor, consenti NDI e OBS nel firewall privato, verifica VPN e isolamento Wi-Fi. La scoperta automatica usa il networking del runtime NDI e richiede una rete che consenta la discovery. La pubblicazione della sorgente non prova che OBS la stia ricevendo. Questa implementazione usa NDI High Bandwidth; non implementa NDI HX/HX3 e non offre un controllo del bitrate.

L'invio si interrompe passando in background, bloccando il telefono o chiudendo l'app. Tornando all'app si riapre l'anteprima e occorre avviare nuovamente l'invio. Il Wi-Fi può limitare FPS e stabilità; 1080p30 è una richiesta, non una prestazione garantita. Il sender invia sincronicamente su un worker dedicato e scarta frame vecchi attraverso `acquireLatestImage()` per evitare code crescenti.

## Verifica

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

I test YUV verificano stride di riga, chroma con pixel stride 2, offset iniziali dei buffer e rotazioni 0/90/180/270. La libreria NDI è isolata in `NdiSender.java`: strutture C mappate con booleani di un byte e memoria mantenuta valida fino al termine della chiamata sincrona. L'invio e lo stop vengono serializzati sullo stesso thread della fotocamera.

Verifiche eseguite il 17 ottobre 2026: build debug riuscita, quattro test unitari superati, lint senza errori (restano avvisi per testi italiani nel codice, orientamento, configurazione backup e aggiornamenti Gradle). Su emulatore Android 14 ARM64: APK installato, anteprima attiva a circa 30 fps, cambio fotocamera e avviso di configurazione del runtime verificati; nessun crash Android rilevato nel controllo finale. [Schermata dell'anteprima emulata](docs/android-preview.png).

Dopo l'integrazione di NDI 6.3.2, il test strumentale `NdiInstrumentation` ha verificato sul vero runtime Android: layout ABI ARM64, invio I420 640×360, ricezione di almeno tre frame, conteggio dei ricevitori e distruzione/ricreazione del sender per due cicli. Il test usa una connessione locale sul dispositivo e non verifica la discovery multicast attraverso il Wi-Fi. Risultato salvato in `docs/ndi-android-test.txt`.

Verificato anche l'avvio e l'arresto della trasmissione della fotocamera dall'interfaccia Android, con frame effettivamente inviati al runtime e nessun crash rilevato. [Schermata della trasmissione emulata](docs/android-ndi-transmitting.png). La schermata mostra zero ricevitori perché la prova UI non è collegata a OBS.

Per ripeterlo su un dispositivo ARM64 con debug USB e runtime già importato:

```sh
./gradlew assembleNdiDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w it.calciocamera.test/it.calciocamera.NdiInstrumentation
```

Inoltre `scripts/NdiSmoke.java` ha verificato i binding di produzione contro il runtime NDI macOS già installato: strutture a 64 bit, creazione sender, invio I420 640×360, ricezione effettiva di un frame e conteggio di un ricevitore. Questo test desktop **non sostituisce il collaudo Android/OBS**. Per ripeterlo con un JAR JNA desktop 5.18.1 e un runtime NDI ufficiale compatibile:

```sh
mkdir -p build/ndi-smoke
javac -cp /percorso/jna-5.18.1.jar -d build/ndi-smoke \
  app/src/main/java/it/calciocamera/NdiSender.java scripts/NdiSmoke.java
java -Djna.library.path=/percorso/runtime-ndi \
  -cp /percorso/jna-5.18.1.jar:build/ndi-smoke NdiSmoke
```

Collaudo su telefono fisico necessario prima dell'uso operativo: verificare anteprima e orientamento su entrambe le fotocamere, immagine e colori in OBS, start/stop ripetuti, cambio formato, rifiuto permessi, perdita Wi-Fi, background/ritorno e una sessione prolungata per carico e temperatura.

## File principali

- `MainActivity.java`: interfaccia Android, permessi, stato e guida.
- `CameraController.java`: Camera2, worker, lifecycle e lock di rete.
- `Yuv420.java`: conversione dei piani camera in frame I420 orientati.
- `NdiSender.java`: caricamento runtime e binding dell'API ufficiale NDI.
- `scripts/import-ndi.py`: importazione controllata del runtime Android.

Riferimenti tecnici: [NDI Send](https://docs.ndi.video/all/developing-with-ndi/sdk/ndi-send), [formati dei frame](https://docs.ndi.video/all/developing-with-ndi/sdk/frame-types), [JNA su Android](https://github.com/java-native-access/jna/blob/master/www/FrequentlyAskedQuestions.md#jna-on-android), [DistroAV](https://github.com/DistroAV/DistroAV).

NDI® is a registered trademark of Vizrt NDI AB. Vedi anche `THIRD_PARTY_NOTICES.md`.
