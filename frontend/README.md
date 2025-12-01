# Senior Helper Frontend

React Native Android application with system overlay capability.

## Prerequisites

- Node.js 16+
- Android Studio
- Android SDK 23+
- Physical Android device (overlays don't work well on emulators)

## Installation

```bash
npm install
```

## Configuration

1. Update API URL in `src/services/api.ts`:
   - For emulator: `http://10.0.2.2:8000`
   - For physical device: `http://YOUR_COMPUTER_IP:8000`

2. Find your computer's IP:
   - Windows: `ipconfig`
   - Mac/Linux: `ifconfig` or `ip addr`

## Running

```bash
# Start Metro bundler
npm start

# In another terminal, run on Android
npm run android
```

## Features

- Floating overlay bubble
- Speech-to-text recognition
- Text-to-speech output
- Chat window interface
- Foreground service for persistent overlay

## Permissions

The app requests:
- RECORD_AUDIO: For speech recognition
- FOREGROUND_SERVICE: For background operation
- SYSTEM_ALERT_WINDOW: For overlay display

## Troubleshooting

### Overlay not appearing
- Grant "Display over other apps" permission in Android settings
- Restart the app after granting permission

### Speech recognition not working
- Grant microphone permission
- Test with built-in Android voice input first

### Cannot connect to backend
- Verify backend is running
- Check firewall settings
- Ensure correct IP address in api.ts
