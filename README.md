# AcidSolitaire Collection — Android

Klondike (Solitaire), Freecell and Spider in Jetpack Compose. Same features as the iPhone version:
drag cards (including back off the foundations), 160 science/engineering/culture/nature facts,
"only 1 useful move left" / "no moves left" warnings, chime only for column and foundation moves,
volume slider, icon Help/Undo/Hint buttons, one shared background for all games (any number of photos
and whole albums, random after each win, built-in art ignored once you add your own), stats (fastest game,
games played, total time) with XML export/import, timer that starts on first touch, large card values,
10 card backs plus your own photo, red felt home screen, per-game rules in the Help page.

## Build the APK
1. Install Android Studio (Ladybug or newer) and open this folder.
2. Let Gradle sync (needs internet the first time).
3. **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The file is in `app/build/outputs/apk/debug/app-debug.apk`.
   Or from a terminal: `./gradlew assembleDebug`.
4. Copy the APK to a phone and open it (allow installs from that source), or run it from Android Studio.

Requires Android 9 (API 28) or newer. Unit tests: `./gradlew test`.

## Notes
- Statistics export uses the system share sheet; import uses the Files picker, or open/share an `.xml`
  file to the app. The XML format is identical to the iPhone version.
- The system photo picker may cap how many photos you can choose per round; choose again to add more.
- This project was written without being compiled; if Gradle reports an error, it will name the file and line.
