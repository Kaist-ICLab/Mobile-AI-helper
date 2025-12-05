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
  "event_data": {"action": "tap"},
  "timestamp": "2024-01-15T10:30:00"
}
```

**Response:**
```json
{
  "status": "logged",
  "logged_at": "2024-01-15T10:30:00"
}
```

### Wizard Interface

#### GET /wizard
Returns the HTML interface for the wizard control panel.

### WebSocket Endpoints

#### WS /ws/phone/{session_id}
WebSocket endpoint for phone connections. Receives image bytes from the phone and forwards them to the wizard interface.



### Frequent Responses

#### GET /frequentResponse
Get all frequent responses.

**Response:**
```json
[
  {
    "id": "uuid-string",
    "taskClassification": "Open Naver",
    "content": "안녕하세요! 무엇을 도와드릴까요?"
  }
]
```

#### POST /frequentResponse
Create a new frequent response.

**Request:**
```json
{
  "taskClassification": "Open Naver",
  "content": "안녕하세요! 무엇을 도와드릴까요?"
}
```

**Response:**
```json
{
  "id": "uuid-string",
  "taskClassification": "Open Naver",
  "content": "안녕하세요! 무엇을 도와드릴까요?"
}
```

#### PUT /frequentResponse/{response_id}
Update an existing frequent response.

**Request:**
```json
{
  "taskClassification": "Open Naver",
  "content": "Updated content"
}
```

**Response:**
```json
{
  "id": "uuid-string",
  "taskClassification": "Open Naver",
  "content": "Updated content"
}
```

#### DELETE /frequentResponse/{response_id}
Delete a frequent response.

**Response:**
```json
{
  "status": "deleted",
  "id": "uuid-string"
}
```

### Task Classifications

#### GET /frequentResponse/taskClassifications
Get all task classifications.

**Response:**
```json
[
  {
    "id": "uuid-string",
    "name": "Open Naver"
  }
]
```

#### POST /frequentResponse/taskClassifications
Create a new task classification.

**Request:**
```json
{
  "name": "Open Naver"
}
```

**Response:**
```json
{
  "id": "uuid-string",
  "name": "Open Naver"
}
```

#### PUT /frequentResponse/taskClassifications/{task_classification_id}
Update an existing task classification.

**Request:**
```json
{
  "name": "Updated Name"
}
```

**Response:**
```json
{
  "id": "uuid-string",
  "name": "Updated Name"
}
```

#### DELETE /frequentResponse/taskClassifications/{task_classification_id}
Delete a task classification.

**Response:**
```json
{
  "status": "deleted",
  "id": "uuid-string"
}
```