# Senior Helper Wizard-of-Oz System

Complete implementation of a Wizard-of-Oz assistant system for seniors with FastAPI backend and React Native Android frontend.

## Architecture

- **Backend**: FastAPI server handling message routing and logging
- **Frontend**: Bare React Native Android app with system overlay
- **Features**: Floating bubble, speech-to-text, text-to-speech, chat interface

## Setup Instructions

### Backend Setup
```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

### Frontend Setup
```bash
cd frontend
npm install
npx react-native start
# In another terminal:
npx react-native run-android
```

## Requirements

- Python 3.8+
- Node.js 16+
- Android Studio
- Android SDK 23+
- Physical Android device (overlay requires real device)

## Configuration

Update the API URL in `frontend/src/services/api.ts` to point to your backend server.

## Permissions

The app requires:
- RECORD_AUDIO: For speech recognition
- FOREGROUND_SERVICE: For overlay service
- SYSTEM_ALERT_WINDOW: For floating overlay

## License

MIT
