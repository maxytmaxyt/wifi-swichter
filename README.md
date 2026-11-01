# Root Wifi Switcher (FRITZ!Box 5590 WD Optimizer)

Eine minimalistische Android-App für gerootete Smartphones, die automatisch zwischen dem 2,4-GHz- und dem 5-GHz-Band wechselt – basierend auf der tatsächlichen Verbindungsqualität und Geschwindigkeit.

## Das Problem
In Umgebungen mit vielen Repeatern (z. B. 5 Repeater + 1 FRITZ!Box) halten Smartphones oft stur an einer schlechteren Verbindung fest. Da einige Repeater im Setup nur das 2,4-GHz-Band verstärken, ist dieses in bestimmten Räumen deutlich schneller und stabiler als das 5-GHz-Band. Android blockiert Apps standardmäßig das automatisierte Wechseln von Netzwerken im Hintergrund.

## Was diese App macht
Die App nutzt **Root-Rechte (`su`)**, um die Systemsperren von Android zu umgehen und erzwingt den Netzwerkwechsel direkt über die System-Shell.

### Kernfunktionen:
* **Überwachung:** Scannt permanent die Signalstärke (dBm) und die theoretische Verbindungsrate der beiden Ziel-Netzwerke:
  1. `FRITZ!Box 5590 WD 2,4` (Reines 2,4-GHz-Netz)
  2. `FRITZ!Box 5590 WD` (Standardnetz / 5-GHz-Netz)
* **Intelligentes Roaming (Aggressive Switching):** Sobald das aktuell genutzte Band unter einen Schwellenwert fällt und das andere Band signifikant stärker/schneller ist, wird der Wechsel sofort im Hintergrund eingeleitet.
* **Nahtloser Wechsel:** Da beide Netzwerke das identische WLAN-Passwort besitzen, wird die Verbindung über den Root-Befehl `cmd wlan connect-to-network` ohne spürbare Unterbrechung für den Nutzer umgeschaltet.

## Technische Umsetzung
* **Sprache:** Kotlin (Android)
* **Mindestvoraussetzung:** Android 10 (API 29) oder höher (wegen des `cmd wlan`-Befehls)
* **Rechte:** Root-Zugriff (Magisk / APatch / KernelSU)
* **Build-System:** Automatische APK-Kompilierung via GitHub Actions (`Gradle`)
