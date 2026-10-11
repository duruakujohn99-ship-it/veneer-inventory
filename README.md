# Veneer Inventory for Titan 2 — starter source

Camera barcode/QR scan, offline records, m² calculation, and CSV export. The sample barcode mappings are examples only; verify dimensions on every new supplier label. This is source code, not a compiled APK.

## Build without installing Android Studio
The GitHub Actions workflow builds the APK in the cloud. Upload the project to a GitHub repository, open Actions, run **Build Android APK**, then download artifact `VeneerInventory-debug-apk`. Extract `app-debug.apk`, copy it to Titan 2, and install. Test carefully before relying on it for stocktaking.
