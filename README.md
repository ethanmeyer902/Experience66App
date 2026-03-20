# Experience 66 - Route 66 Arizona Explorer

An Android app for exploring Arizona Route 66 POIs with interactive mapping, geofencing, search, offline support, and archive integration.

![Android](https://img.shields.io/badge/Android-3DDC84?style=flat&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)
![Mapbox](https://img.shields.io/badge/Mapbox-000000?style=flat&logo=mapbox&logoColor=white)

## Current Dataset

The app now uses a cleaned POI dataset:

- Asset file: `app/src/main/assets/Route_66_Database.csv`
- Schema source: reduced landmarks CSV (cleaned Arizona-focused records)
- POI description field: `Description` (preferred), with fallback behavior for legacy fields
- Duplicate filtering: applied during repository load to return valid, unique POIs

## Features

### Map and POI Experience

- Interactive Mapbox map focused on Arizona Route 66
- Route 66 corridor line rendered from `route66.geojson`
- POI markers and geofence circles displayed on the map
- POI detail card with name, historical description, and image
- POI image resolution via `drawable/poi_<landmark_id>` with fallback to `ic_launcher`
- POI list screen (`POIs` button) for quick browsing and map selection

### User Location

- User location displayed as a custom blue dot with hazy outer ring
- Map recenters around the user location at startup (broad zoom for POI context)
- Location display activates after permission is granted

### Geofencing and Monitoring

- Geofence registration for loaded POIs
- ENTER, EXIT, and DWELL transition handling
- Real-time monitor panel showing registered geofences and event log
- Active geofence visual highlight updates on the map

### Search and Archive

- Search POIs and archive-linked records by name/id/text
- Camera navigation and highlight when a matching POI is found
- Archive result list with details and direct external URL opening
- POI `About` action opens matched archive item for the current landmark

### POI Actions and Accessibility

- `Listen` button uses Text-to-Speech for POI narration
- `Navigate` button opens driving directions in Google Maps (or browser fallback)
- Onboarding overlay introduces key app actions and workflows

### Offline and Data Reliability

- Cleaned CSV-backed POI loading (`Route_66_Database.csv`)
- Duplicate filtering during repository load to keep valid unique POIs
- Online/offline status bar with cache info
- Manual `UPDATE` flow for metadata and offline map region caching
- Cached POI data usage when network is unavailable

## Requirements

- Android Studio Hedgehog or later
- Android SDK 24+ (Android 7.0)
- Mapbox Account (for access token)

## Setup

### 1. Clone the Repository
```bash
git clone https://github.com/ethanmeyer902/Experience66Hello.git
cd Experience66
```

### 2. Mapbox Configuration

**Public Token** (for map display):
- Get your token from [Mapbox Account](https://account.mapbox.com/)
- Add to `app/src/main/res/values/mapbox-resource-token.xml`:
```xml
<string name="mapbox_access_token">YOUR_PUBLIC_TOKEN</string>
```

**Secret Token** (for SDK download):
- Create a secret token with `Downloads:Read` scope
- Add to `gradle.properties`:
```properties
MAPBOX_DOWNLOADS_TOKEN=sk.your_secret_token_here
```

### 3. Build & Run
```bash
./gradlew assembleDebug
```

Or open in Android Studio and run on device/emulator.

## Project Structure

```
Experience66/
├── app/
│   ├── src/main/java/com/example/experience66hello/
│   │   ├── MainActivity.kt                # Main map UI, POI cards, search, monitor
│   │   ├── Route66Landmark.kt             # POI model
│   │   ├── Route66DatabaseEntry.kt        # CSV entry model
│   │   ├── Route66DatabaseParser.kt       # CSV parser
│   │   ├── Route66DatabaseRepository.kt   # POI data access + dedupe
│   │   ├── GeofenceManager.kt             # Geofence registration
│   │   ├── GeofenceBroadcastReceiver.kt   # Geofence event handling
│   │   ├── OfflineMapManager.kt           # Offline map region cache
│   │   ├── OfflineDataCache.kt            # Metadata cache
│   │   └── ArchiveRepository.kt           # Archive item loading/search
│   └── src/main/
│       ├── assets/Route_66_Database.csv   # Active cleaned POI dataset
│       └── res/
│           ├── drawable/red_marker.xml
│           └── values/mapbox-resource-token.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Permissions

The app requires the following permissions:

| Permission | Purpose |
|------------|---------|
| `ACCESS_FINE_LOCATION` | Precise location for geofencing |
| `ACCESS_BACKGROUND_LOCATION` | Geofence detection when app is backgrounded |
| `INTERNET` | Map tiles and online features |
| `ACCESS_NETWORK_STATE` | Offline mode detection |


## Technologies

- **Kotlin** - Primary language
- **Mapbox Maps SDK 11.5** - Interactive maps
- **Google Play Services Location 21.2** - Geofencing API
- **Android Jetpack** - Activity, Lifecycle components
- **SharedPreferences** - Local caching/state

## License

This project is for educational purposes as part of the Experience 66 Route 66 preservation initiative.

---

*"Get your kicks on Route 66"* 🛣️

