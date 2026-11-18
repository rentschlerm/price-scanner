# Price Scanner

Android POS / price-check app for the convenience store. Scans product barcodes live with the camera, looks up prices from the Google Sheet, totals sales and works out change.

## Install on your phone
Open the latest release on your phone and download **PriceScanner.apk**:
https://github.com/rentschlerm/price-scanner/releases/latest

## Changing the app
Edit `app/src/main/assets/index.html` and push to `main`. GitHub Actions builds a new APK and publishes it as a release automatically. Installing the new APK over the old one keeps your data.

Note: `app/pricescanner.keystore` is the signing key that lets new versions install over old ones. Keep it in the repo (or the update will refuse to install).
