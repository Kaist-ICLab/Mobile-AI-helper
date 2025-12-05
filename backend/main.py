from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Dict, Optional
import logging
from datetime import datetime
from frequent_response_routes import router as frequent_response_router

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('wizard_of_oz.log'),
        logging.StreamHandler()
    ]
)

logger = logging.getLogger(__name__)

app = FastAPI(title="Senior Helper WOZ API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.include_router(frequent_response_router)

sessions: Dict[str, list] = {}

class MessageRequest(BaseModel):
    session_id: str
    role: str
    text: str

class MessageResponse(BaseModel):
    reply: str
    session_id: str
    timestamp: str

class LogRequest(BaseModel):
    session_id: str
    event_type: str
    event_data: Optional[Dict] = None
    timestamp: Optional[str] = None

class LogResponse(BaseModel):
    status: str
    logged_at: str


class ConnectionManager:
    def __init__(self):
        self.active_connections: Dict[str, WebSocket] = {}

    async def connect(self, websocket: WebSocket, client_id: str):
        await websocket.accept()
        self.active_connections[client_id] = websocket

    def disconnect(self, client_id: str):
        if client_id in self.active_connections:
            del self.active_connections[client_id]

    async def send_personal_message(self, message: str, websocket: WebSocket):
        await websocket.send_text(message)

    async def broadcast_bytes(self, data: bytes, target_id: str):
        # Relay data from Phone (source) to Wizard (target)
        if target_id in self.active_connections:
            await self.active_connections[target_id].send_bytes(data)

manager = ConnectionManager()

@app.get("/")
async def root():
    return {
        "service": "Senior Helper WOZ API",
        "version": "1.0.0",
        "endpoints": ["/message", "/log", "/sessions"]
    }

@app.post("/message", response_model=MessageResponse)
async def handle_message(request: MessageRequest):
    """
    Handles incoming messages. 
    User messages are stored and wait for wizard response.
    Wizard/assistant messages are stored as replies.
    """
    try:
        if request.session_id not in sessions:
            sessions[request.session_id] = []
            logger.info(f"New session created: {request.session_id}")
        
        message_entry = {
            "role": request.role,
            "text": request.text,
            "timestamp": datetime.now().isoformat()
        }
        sessions[request.session_id].append(message_entry)
        
        logger.info(f"Session {request.session_id} - {request.role}: {request.text}")
        
        # ONLY return automated response if role is "user" AND you want auto-replies
        # For pure Wizard-of-Oz, we DON'T auto-reply
        if request.role == "user":
            reply = "Message received. A wizard will respond shortly."
        else:
            reply = "Message sent"
        
        return MessageResponse(
            reply=reply,
            session_id=request.session_id,
            timestamp=datetime.now().isoformat()
        )
    
    except Exception as e:
        logger.error(f"Error handling message: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/log", response_model=LogResponse)
async def log_event(request: LogRequest):
    try:
        timestamp = request.timestamp or datetime.now().isoformat()
        
        log_entry = {
            "session_id": request.session_id,
            "event_type": request.event_type,
            "event_data": request.event_data or {},
            "timestamp": timestamp
        }
        
        logger.info(f"Event logged: {log_entry}")
        
        return LogResponse(
            status="logged",
            logged_at=timestamp
        )
    
    except Exception as e:
        logger.error(f"Error logging event: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/sessions/{session_id}")
async def get_session(session_id: str):
    if session_id not in sessions:
        raise HTTPException(status_code=404, detail="Session not found")
    
    return {
        "session_id": session_id,
        "messages": sessions[session_id]
    }

@app.get("/sessions")
async def list_sessions():
    return {
        "sessions": list(sessions.keys()),
        "count": len(sessions)
    }

@app.get("/wizard", response_class=HTMLResponse)
async def get_wizard_interface():
    with open("wizard_interface.html", "r") as f:
        return f.read()

@app.websocket("/ws/phone/{session_id}")
async def websocket_phone(websocket: WebSocket, session_id: str):
    await manager.connect(websocket, f"phone_{session_id}")
    try:
        while True:
            # Receive image bytes from phone
            data = await websocket.receive_bytes()
            # Forward immediately to the wizard of this session
            await manager.broadcast_bytes(data, f"wizard_{session_id}")
    except WebSocketDisconnect:
        manager.disconnect(f"phone_{session_id}")

@app.websocket("/ws/wizard/{session_id}")
async def websocket_wizard(websocket: WebSocket, session_id: str):
    await manager.connect(websocket, f"wizard_{session_id}")
    try:
        while True:
            await websocket.receive_text() # Keep connection alive
    except WebSocketDisconnect:
        manager.disconnect(f"wizard_{session_id}")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
