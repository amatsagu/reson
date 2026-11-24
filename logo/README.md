# Reson Brand Identity and Logo Assets

## Overview
The primary identity for Reson is the stylized "R" glyph in the Cotton Candy colorway. It provides a distinct, modern visual mark across application launchers, in-app branding, and system notifications.

## Color Palette

### App Launcher
- **Background**: Dark Navy Gradient
  - Start (Top): `#0E1324`
  - End (Bottom): `#060912`
- **Glyph Body**: Linear Gradient (45-degree angle)
  - Start (Upper Left): `#F472B6`
  - End (Lower Right): `#38BDF8`
- **Highlights**:
  - Upper Rim Gloss: `#A6FFFFFF` (pure white, 65% opacity)
  - Stem Gloss: `#8CFFFFFF` (pure white, 55% opacity)

### Monochrome and Notification (Android 12+)
- **Body**: `#FFFFFF` (100% white) with EvenOdd counter cutout
- **Background**: Transparent / system dynamic wallpaper tint

## Source Files
Located in this directory (`logo/`):
- `reson_logo_cotton_candy.svg` - 512x512 master vector in Cotton Candy palette
- `reson_logo_monochrome.svg` - 512x512 master monochrome vector

Design drafts and alternative explorations are archived in `logo_designs/`.

## Android Resource Mapping

### 1. Adaptive App Icons
- Standard Launcher: `app/src/main/res/mipmap-anydpi/ic_launcher.xml`
- Round Launcher: `app/src/main/res/mipmap-anydpi/ic_launcher_round.xml`
  - Background Vector: `app/src/main/res/drawable/ic_launcher_background.xml` (108x108 dp)
  - Foreground Vector: `app/src/main/res/drawable/ic_launcher_foreground.xml` (centered in 72 dp safe zone)
  - Monochrome Vector: `app/src/main/res/drawable/ic_launcher_monochrome.xml` (dynamic Material You tinting on Android 13+)

### 2. Status Bar and Media Notifications
- Small Icon: `app/src/main/res/drawable/ic_notification.xml` (24x24 dp monochrome vector)
- Declared in `app/src/main/AndroidManifest.xml` under `ResonPlaybackService`:
  ```xml
  <meta-data
      android:name="androidx.media3.session.notification.small_icon"
      android:resource="@drawable/ic_notification" />
  ```

### 3. Density Mipmaps
Optimized WebP raster assets for legacy launchers:
- `mipmap-mdpi/`: 48x48 px
- `mipmap-hdpi/`: 72x72 px
- `mipmap-xhdpi/`: 96x96 px
- `mipmap-xxhdpi/`: 144x144 px
- `mipmap-xxxhdpi/`: 192x192 px
