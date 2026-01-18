from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Dict, Optional, List
import logging
import json
import os
from datetime import datetime
from frequent_response_routes import router as frequent_response_router

HERE = os.path.dirname(os.path.abspath(__file__))

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
        if target_id in self.active_connections:
            await self.active_connections[target_id].send_bytes(data)

manager = ConnectionManager()

@app.get("/api/_debug_routes")
def _debug_routes():
    return {"has_choice_templates": True}

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
    with open(os.path.join(HERE, "wizard_interface.html"), "r", encoding="utf-8") as f:
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

# ==================== CHOICE TEMPLATES ====================
TEMPLATES_PATH = os.path.join(os.path.dirname(__file__), "choice_templates.json")

def _load_templates_file():
    if not os.path.exists(TEMPLATES_PATH):
        return {"categories": [], "templates": []}
    with open(TEMPLATES_PATH, "r", encoding="utf-8") as f:
        return json.load(f)

def _save_templates_file(data):
    with open(TEMPLATES_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

class ChoiceTemplate(BaseModel):
    id: str
    category: str
    name: str
    prompt: str
    options: List[str]

class ChoiceTemplateUpdate(BaseModel):
    category: Optional[str] = None
    name: Optional[str] = None
    prompt: Optional[str] = None
    options: Optional[List[str]] = None

@app.get("/api/choice-templates")
@app.get("/choice-templates")
def list_choice_templates():
    return _load_templates_file()

@app.post("/api/choice-templates")
@app.post("/choice-templates")
def create_choice_template(t: ChoiceTemplate):
    data = _load_templates_file()
    templates = data.get("templates", [])

    if any(x.get("id") == t.id for x in templates):
        raise HTTPException(status_code=409, detail="Template id already exists")

    templates.append(t.model_dump())
    data["templates"] = templates
    _save_templates_file(data)
    return {"ok": True}

@app.put("/api/choice-templates/{template_id}")
@app.put("/choice-templates/{template_id}")
def update_choice_template(template_id: str, patch: ChoiceTemplateUpdate):
    data = _load_templates_file()
    templates = data.get("templates", [])

    for i, x in enumerate(templates):
        if x.get("id") == template_id:
            updated = dict(x)
            if patch.category is not None:
                updated["category"] = patch.category
            if patch.name is not None:
                updated["name"] = patch.name
            if patch.prompt is not None:
                updated["prompt"] = patch.prompt
            if patch.options is not None:
                updated["options"] = patch.options

            templates[i] = updated
            data["templates"] = templates
            _save_templates_file(data)
            return {"ok": True}

    raise HTTPException(status_code=404, detail="Template not found")

@app.delete("/api/choice-templates/{template_id}")
@app.delete("/choice-templates/{template_id}")
def delete_choice_template(template_id: str):
    data = _load_templates_file()
    templates = data.get("templates", [])
    new_templates = [x for x in templates if x.get("id") != template_id]

    if len(new_templates) == len(templates):
        raise HTTPException(status_code=404, detail="Template not found")

    data["templates"] = new_templates
    _save_templates_file(data)
    return {"ok": True}

# ==================== TASK FLOWS ====================
TASK_STEPS_PATH = os.path.join(HERE, "task_steps.json")

def _load_task_steps_file():
    if not os.path.exists(TASK_STEPS_PATH):
        return {"categories": [], "tasks": []}
    try:
        with open(TASK_STEPS_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        logger.error(f"Error loading task_steps.json: {e}")
        return {"categories": [], "tasks": []}

def _save_task_steps_file(data):
    try:
        with open(TASK_STEPS_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"Error saving task_steps.json: {e}")
        raise HTTPException(status_code=500, detail="Failed to save task steps")

class TaskStepUpdate(BaseModel):
    title_en: Optional[str] = None
    say_ko: Optional[str] = None

class TaskUpdate(BaseModel):
    name_en: Optional[str] = None
    category: Optional[str] = None
    level: Optional[str] = None

@app.get("/api/task-flows")
def get_task_flows():
    """Returns the full contents of task_steps.json"""
    return _load_task_steps_file()

@app.put("/api/task-flows/tasks/{task_id}/steps/{step_number}")
def update_task_step(task_id: str, step_number: int, patch: TaskStepUpdate):
    """Update a specific step within a task"""
    data = _load_task_steps_file()
    tasks = data.get("tasks", [])

    for task in tasks:
        if task.get("id") == task_id:
            steps = task.get("steps", [])
            for step in steps:
                if step.get("step") == step_number:
                    if patch.title_en is not None:
                        step["title_en"] = patch.title_en
                    if patch.say_ko is not None:
                        step["say_ko"] = patch.say_ko

                    _save_task_steps_file(data)
                    return {"ok": True, "step": step}

            raise HTTPException(status_code=404, detail=f"Step {step_number} not found in task {task_id}")

    raise HTTPException(status_code=404, detail=f"Task {task_id} not found")

@app.put("/api/task-flows/tasks/{task_id}")
def update_task(task_id: str, patch: TaskUpdate):
    """Update task metadata (name, category, level)"""
    data = _load_task_steps_file()
    tasks = data.get("tasks", [])

    for task in tasks:
        if task.get("id") == task_id:
            if patch.name_en is not None:
                task["name_en"] = patch.name_en
            if patch.category is not None:
                task["category"] = patch.category
            if patch.level is not None:
                task["level"] = patch.level

            _save_task_steps_file(data)
            return {"ok": True, "task": task}

    raise HTTPException(status_code=404, detail=f"Task {task_id} not found")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)