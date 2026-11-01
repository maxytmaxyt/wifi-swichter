# Root Wifi Switcher (FRITZ!Box 5590 WD Optimizer)

Eine Android-App für gerootete Smartphones, die die WLAN-Qualität überwacht und bei schwachem Empfang automatisch zwischen zwei FRITZ!Box-SSIDs wechselt.

## Das Problem
In Umgebungen mit vielen Repeatern (z. B. 5 Repeater + 1 FRITZ!Box) halten Smartphones oft stur an einer schlechteren Verbindung fest. Da einige Repeater im Setup nur das 2,4-GHz-Band verstärken, ist dieses in bestimmten Räumen deutlich schneller und stabiler als das 5-GHz-Band. Android blockiert Apps standardmäßig das automatisierte Wechseln von Netzwerken im Hintergrund.

## Was diese App macht
Die App nutzt **Root-Rechte (`su`)** und den Android-Shell-Befehl `cmd wifi connect-network`, um den Netzwerkwechsel anzufordern. Beim ersten Start werden Standortberechtigungen sowie ab Android 13 eine Benachrichtigungsberechtigung benötigt; außerdem müssen die Standortdienste eingeschaltet sein. Die Überwachung läuft als Foreground Service mit sichtbarer Benachrichtigung und endet über „Überwachung stoppen“ oder wenn die App in den Systemeinstellungen erzwungen beendet wird.

### Kernfunktionen:
* **Überwachung:** Fordert alle 30 Sekunden einen Scan an (Android kann Scans begrenzen) und ermittelt Signalstärke (dBm) sowie eine geschätzte maximale PHY-Rate mit einem räumlichen Datenstrom für das Zielnetz. Die Standard-SSIDs sind in der App änderbar; wenn das Ziel fehlt, zeigt die App die aktuell gefundenen WLAN-Namen:
  1. `FRITZ!Box 5590 WD 2,4` (Reines 2,4-GHz-Netz)
  2. `FRITZ!Box 5590 WD` (Standardnetz / 5-GHz-Netz)
* **Intelligentes Roaming:** Ein Wechsel wird angefordert, wenn der aktuelle Empfang höchstens `-72 dBm` beträgt und das andere Netz mindestens `8 dB` stärker ist oder bei ausreichendem Empfang eine mindestens 25 % höhere geschätzte Rate als die aktuell ausgehandelte Verbindung bietet. Eine Pause von 60 Sekunden verhindert schnelles Hin- und Herschalten.
* **Zugangsdaten:** Beim Start kann das gemeinsame WLAN-Passwort eingegeben werden. Es wird mit AES-GCM verschlüsselt auf dem Gerät gespeichert; der Schlüssel verbleibt im Android Keystore. Die App zeigt an, welche Zugangsdaten gespeichert sind; leere Passwortfelder lassen gespeicherte Passwörter unverändert.
* **Root-Wechsel:** Die App verwendet das Passwort für `cmd wifi connect-network <SSID> wpa2 <Passwort>`. Beide SSIDs müssen WPA2 mit demselben Passwort verwenden. Ein erfolgreicher Shell-Aufruf bestätigt nur, dass Android den Verbindungsversuch angenommen hat; die tatsächliche Verbindung hängt vom Gerät, der Android-Version und der WLAN-Umgebung ab.
* **Hotspot-Wiederherstellung:** Wenn der mobile Hotspot vor dem Wechsel aktiv ist, wird sein Status vor dem WLAN-Wechsel über `dumpsys wifi` geprüft. Falls Android ihn abschaltet, fordert die App den Neustart mit der in der App hinterlegten Hotspot-SSID und dem WPA2-Passwort an. Diese Daten werden ebenfalls verschlüsselt gespeichert. Androids geschützte Hotspot-Konfiguration kann die App nicht auslesen; die eingegebenen Daten müssen daher dem gewünschten Hotspot-Profil entsprechen. Der Neustart verwendet WPA2 und das Android-Standardband; der Wechsel wird ausgelassen, wenn sich der Hotspot-Status nicht zuverlässig feststellen lässt oder seine Daten fehlen. Manche Geräte können WLAN-Client und Hotspot nicht gleichzeitig betreiben oder verwenden abweichende Shell-Befehle.

## Technische Umsetzung
* **Sprache:** Kotlin (Android)
* **Mindestvoraussetzung:** Android 11 (API 30) oder höher. `cmd wifi connect-network` und `cmd wifi start-softap` sind in der Android-10-Shell nicht verfügbar; auf Hersteller-ROMs kann sich das Verhalten zusätzlich unterscheiden.
* **Rechte:** Root-Zugriff (Magisk / APatch / KernelSU)
* **Build-System:** Die APK wird ausschließlich durch GitHub Actions mit Gradle 8.2.1 und JDK 17 gebaut. Der Workflow legt das APK als `app-debug`-Artifact ab.
* **Projektstruktur:** `ui/` enthält die Oberfläche, `service/` den Foreground Service, `wifi/` Scan- und Root-Steuerung und `security/` die verschlüsselte Zugangsdatenablage.
