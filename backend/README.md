# Backend API

FastAPI server for the Senior Helper Wizard-of-Oz system.

## Installation

```bash
pip install -r requirements.txt
```

## Running

```bash
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

## Endpoints

### POST /message
Handles user messages and returns assistant responses.

**Request:**
```json
{
  "session_id": "unique-session-id",
  "role": "user",
  "text": "How do I use my phone?"
}
```

**Response:**
```json
{
  "reply": "I can help you with that...",
  "session_id": "unique-session-id",
  "timestamp": "2024-01-15T10:30:00"
}
```

### POST /log
Logs events for analysis.

**Request:**
```json
{
  "session_id": "unique-session-id",
  "event_type": "bubble_opened",
  "event_data": {"action": "tap"}
}
```
