const API_URL = ''; // Use current domain
let currentSessionId = null;
let pollInterval = null;
let lastMessageCount = 0;
let ws = null;

let frequentResponses = [];
let taskClassifications = [];
let selectedClassification = '';

// Speaking indicator state
let isUserSpeaking = false;
let speakingStartTime = null;
let speakingTimerInterval = null;

function showSpeakingIndicator() {
    isUserSpeaking = true;
    speakingStartTime = Date.now();
    const indicator = document.getElementById('speakingIndicator');
    indicator.classList.add('active');

    const container = document.getElementById('messagesContainer');
    container.scrollTop = container.scrollHeight;

    if (speakingTimerInterval) clearInterval(speakingTimerInterval);
    const updateTimer = () => {
    const elapsed = Math.floor((Date.now() - speakingStartTime) / 1000);
    document.getElementById('speakingTimer').textContent = elapsed + 's';
    };
    updateTimer();
    speakingTimerInterval = setInterval(updateTimer, 100);
}

function hideSpeakingIndicator() {
    isUserSpeaking = false;
    const indicator = document.getElementById('speakingIndicator');
    indicator.classList.remove('active');
    if (speakingTimerInterval) {
    clearInterval(speakingTimerInterval);
    speakingTimerInterval = null;
    }
}

function parseEventData(text) {
    try {
    const json = JSON.parse(text);
    if (json && json.type === 'event') return json;
    return null;
    } catch (e) {
    return null;
    }
}

function formatTimestamp(timestamp) {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    return date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function formatDuration(ms) {
    if (!ms) return '';
    const seconds = Math.floor(ms / 1000);
    const tenths = Math.floor((ms % 1000) / 100);
    return seconds + '.' + tenths + 's';
}

function formatGap(ms) {
    if (ms < 1000) return '';
    const seconds = Math.floor(ms / 1000);
    if (seconds < 60) return '⏱️ ' + seconds + 's gap';
    const minutes = Math.floor(seconds / 60);
    const remainingSecs = seconds % 60;
    return '⏱️ ' + minutes + 'm ' + remainingSecs + 's gap';
}

const addResponseBackdrop = document.getElementById('addResponseBackdrop');
const newResponseContent = document.getElementById('newResponseContent');

// ==================== TUTORIAL SYSTEM ====================
let currentTutorialStep = 0;
const tutorialSteps = [
    { step: 1, total: 7, title_ko: "도움말", body_ko: "안녕하세요! 저는 당신이 휴대폰을 편하게 사용할 수 있도록 도와주는 AI입니다." },
    { step: 2, total: 7, title_ko: "도움말", body_ko: "휴대폰으로 하고 싶은 일이 있으시면 편하게 말씀해 주세요. 제가 답해 드릴게요." },
    { step: 3, total: 7, title_ko: "도움말", body_ko: "요청하신 내용을 제가 제대로 이해했는지 확인한 이후에, 휴대폰 사용을 도와드릴게요." },
    { step: 4, total: 7, title_ko: "도움말", body_ko: "가끔, 사용자분께서 직접 휴대폰을 조작하셔야 할 때가 있어요. 그럴 때는, 제가 \"어디를 누르세요\"라고 말씀드릴게요." },
    { step: 5, total: 7, title_ko: "도움말", body_ko: "혹시나 헷갈리거나 이해가 안되는 내용이 있으시면, 제게 말씀해주시면 안내드리겠습니다." },
    { step: 6, total: 7, title_ko: "도움말", body_ko: "상단의 반복 버튼을 누르면 제가 말씀 드린 내용을 다시 들을 수 있어요." },
    { step: 7, total: 7, title_ko: "도움말", body_ko: "혹시나 제가 도와드리는 동안 중간에 불편한 점이 생기시거나, 설명이 필요하신 부분이 있으시면, 위의 일시정지 버튼을 눌러서 알려주시면 도와드리겠습니다." }
]

function populateTutorialDropdown() {
    const select = document.getElementById('tutorialStepSelect');
    if (!select) return;
    select.innerHTML = '';
    tutorialSteps.forEach(opt => {
        const el = document.createElement('option');
        el.value = opt.step;
        el.textContent = `${opt.step}. ${opt.body_ko}`;
        select.appendChild(el);
    });
}

document.addEventListener('DOMContentLoaded', () => {
    populateTutorialDropdown();
});

async function sendTutorialCommand(tutorialData) {
    if (!currentSessionId) {
    alert('Connect to a session first!');
    return;
    }
    const payload = JSON.stringify(tutorialData);
    try {
    await fetch(`${API_URL}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ session_id: currentSessionId, role: 'wizard', text: payload })
    });
    console.log('Tutorial command sent:', tutorialData);
    loadMessages();
    } catch (e) {
    alert('Error sending tutorial command: ' + e);
    }
}

function onStepSelected() {
    if (currentTutorialStep === 0) {
    document.getElementById('showStepBtn').disabled = false;
    }
}

function showSelectedStep() {
    const selectedStep = parseInt(document.getElementById('tutorialStepSelect').value, 10);
    const wasHidden = (currentTutorialStep === 0);
    currentTutorialStep = selectedStep;

    const stepData = tutorialSteps[selectedStep - 1];
    const action = wasHidden ? 'show' : 'update';

    sendTutorialCommand({
    type: 'tutorial',
    action: action,
    step: stepData.step,
    total: stepData.total,
    title_ko: stepData.title_ko,
    body_ko: stepData.body_ko
    });

    updateTutorialUI();
}

function hideTutorial() {
    currentTutorialStep = 0;

    sendTutorialCommand({
    type: 'tutorial',
    action: 'hide',
    step: 0,
    total: 0,
    title_ko: '',
    body_ko: ''
    });

    updateTutorialUI();
}

function updateTutorialUI() {
    const showBtn = document.getElementById('showStepBtn');
    const hideBtn = document.getElementById('hideTutorialBtn');
    const indicator = document.getElementById('tutorialStepIndicator');
    const stepSelect = document.getElementById('tutorialStepSelect');

    if (currentTutorialStep === 0) {
    showBtn.disabled = false;
    hideBtn.disabled = true;
    indicator.textContent = '';
    stepSelect.disabled = false;
    } else {
    showBtn.disabled = false;
    hideBtn.disabled = false;
    indicator.textContent = `Currently showing: Step ${currentTutorialStep}/${tutorialSteps.length}`;
    stepSelect.disabled = false;
    stepSelect.value = String(currentTutorialStep);
    }
}

function parseTutorialCommand(text) {
    try {
    const json = JSON.parse(text);
    if (json && json.type === 'tutorial') return json;
    return null;
    } catch (e) {
    return null;
    }
}

// ==================== CHOICES HELPER ====================
let CHOICE_CATEGORIES = [];
let CHOICE_TEMPLATES = [];
let selectedTemplateId = null;

async function loadChoiceTemplates() {
    try {
    const url = `${API_URL}/api/choice-templates`;
    console.log("[ChoiceTemplates] fetching:", url);

    const res = await fetch(url, { cache: "no-store" });
    console.log("[ChoiceTemplates] status:", res.status);

    if (!res.ok) {
        const text = await res.text();
        console.error("[ChoiceTemplates] non-OK response body:", text);
        alert(`Choice templates failed: HTTP ${res.status}`);
        CHOICE_CATEGORIES = [];
        CHOICE_TEMPLATES = [];
        populateCategoryDropdown();
        return;
    }

    const data = await res.json();
    console.log("[ChoiceTemplates] data:", data);

    CHOICE_CATEGORIES = Array.isArray(data.categories) ? data.categories : [];
    CHOICE_TEMPLATES = Array.isArray(data.templates) ? data.templates : [];

    populateCategoryDropdown();
    } catch (e) {
    console.error("Failed to load choice templates:", e);
    alert("Failed to load choice templates (see console).");
    CHOICE_CATEGORIES = [];
    CHOICE_TEMPLATES = [];
    populateCategoryDropdown();
    }
}

function populateCategoryDropdown() {
    const sel = document.getElementById("choicesCategorySelect");
    sel.innerHTML = `<option value="">(Select category)</option>`;
    CHOICE_CATEGORIES.forEach(cat => {
    const opt = document.createElement("option");
    opt.value = cat.id;
    opt.textContent = cat.name;
    sel.appendChild(opt);
    });

    // Reset template dropdown
    document.getElementById("choicesTemplateSelect").innerHTML = `<option value="">(Select template)</option>`;
}

function onCategorySelected() {
    const categoryId = document.getElementById("choicesCategorySelect").value;
    const templateSel = document.getElementById("choicesTemplateSelect");

    templateSel.innerHTML = `<option value="">(Select template)</option>`;
    selectedTemplateId = null;

    if (!categoryId) return;

    // Filter templates by category
    const filtered = CHOICE_TEMPLATES.filter(t => t.category === categoryId);
    filtered.forEach(t => {
    const opt = document.createElement("option");
    opt.value = t.id;
    opt.textContent = t.name;
    templateSel.appendChild(opt);
    });
}

function onTemplateSelected() {
    const templateId = document.getElementById("choicesTemplateSelect").value;
    if (!templateId) {
    selectedTemplateId = null;
    return;
    }

    const t = CHOICE_TEMPLATES.find(x => x.id === templateId);
    if (!t) return;

    selectedTemplateId = templateId;

    // Fill prompt/options fields
    document.getElementById("choicesPrompt").value = t.prompt || "";
    document.getElementById("choicesOptions").value = (t.options || []).join("\n");
}

async function sendChoices() {
    if (!currentSessionId) {
    alert('Connect to a session first!');
    return;
    }

    const prompt = document.getElementById('choicesPrompt').value.trim();
    const optionsText = document.getElementById('choicesOptions').value.trim();

    if (!prompt) {
    alert('Please enter a prompt');
    return;
    }

    if (!optionsText) {
    alert('Please enter at least one option');
    return;
    }

    // Parse options (one per line, filter empty lines)
    const options = optionsText.split('\n')
    .map(line => line.trim())
    .filter(line => line.length > 0);

    if (options.length === 0) {
    alert('Please enter at least one option');
    return;
    }

    const payload = {
    type: 'choices',
    prompt: prompt,
    options: options
    };

    try {
    await fetch(`${API_URL}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ session_id: currentSessionId, role: 'wizard', text: JSON.stringify(payload) })
    });
    console.log('Choices sent:', payload);
    loadMessages();
    } catch (e) {
    alert('Error sending choices: ' + e);
    }
}

async function saveCurrentTemplate() {
    if (!selectedTemplateId) {
    alert("Select a template first to save changes");
    return;
    }

    const prompt = (document.getElementById("choicesPrompt").value || "").trim();
    const optionsText = (document.getElementById("choicesOptions").value || "").trim();
    const options = optionsText
    ? optionsText.split("\n").map(s => s.trim()).filter(Boolean)
    : [];

    if (!prompt) {
    alert("Prompt is required");
    return;
    }
    if (options.length === 0) {
    alert("At least one option is required");
    return;
    }

    try {
    const res = await fetch(`${API_URL}/api/choice-templates/${selectedTemplateId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt, options })
    });

    if (res.ok) {
        const categoryId = document.getElementById("choicesCategorySelect").value;
        await loadChoiceTemplates();

        // Restore selection
        document.getElementById("choicesCategorySelect").value = categoryId;
        onCategorySelected();
        document.getElementById("choicesTemplateSelect").value = selectedTemplateId;

        alert("Template saved ✅");
    } else {
        const err = await res.json();
        alert("Save failed: " + (err.detail || res.status));
    }
    } catch (e) {
    alert("Error saving template: " + e);
    }
}

function parseChoicesCommand(text) {
    try {
    const json = JSON.parse(text);
    if (json && json.type === 'choices') return json;
    return null;
    } catch (e) {
    return null;
    }
}

// ==================== TASK FLOWS ====================
let TASK_FLOWS_CATEGORIES = [];
let TASK_FLOWS_TASKS = [];
let selectedTaskFlowsTaskId = null;
let selectedStepNumber = null;
let editingStepNumber = null;
function setTaskFlowsStatus(msg) {
    const el = document.getElementById("taskFlowsStatus");
    if (!el) return;
    el.textContent = msg || '';
}

async function loadTaskFlows() {
    try {
    const url = `${API_URL}/api/task-flows`;
    console.log("[TaskFlows] fetching:", url);

    const res = await fetch(url, { cache: "no-store" });
    console.log("[TaskFlows] status:", res.status);

    if (!res.ok) {
        const text = await res.text();
        console.error("[TaskFlows] non-OK response body:", text);
        TASK_FLOWS_CATEGORIES = [];
        TASK_FLOWS_TASKS = [];
        populateTaskFlowsCategoryDropdown();
        return;
    }

    const data = await res.json();
    console.log("[TaskFlows] data:", data);

    TASK_FLOWS_CATEGORIES = Array.isArray(data.categories) ? data.categories : [];
    TASK_FLOWS_TASKS = Array.isArray(data.tasks) ? data.tasks : [];

    populateTaskFlowsCategoryDropdown();
    setTaskFlowsStatus(`Loaded ${TASK_FLOWS_TASKS.length} tasks`);
    } catch (e) {
    console.error("Failed to load task flows:", e);
    TASK_FLOWS_CATEGORIES = [];
    TASK_FLOWS_TASKS = [];
    populateTaskFlowsCategoryDropdown();
    setTaskFlowsStatus("Failed to load tasks");
    }
}

function populateTaskFlowsCategoryDropdown() {
    const sel = document.getElementById("taskFlowsCategorySelect");
    sel.innerHTML = `<option value="">(Select category)</option>`;
    TASK_FLOWS_CATEGORIES.forEach(cat => {
    const opt = document.createElement("option");
    opt.value = cat.id;
    opt.textContent = cat.name;
    sel.appendChild(opt);
    });

    // Reset task dropdown
    document.getElementById("taskFlowsTaskSelect").innerHTML = `<option value="">(Select task)</option>`;
    // Clear steps list
    document.getElementById("taskStepsList").innerHTML = `
    <div style="text-align:center; color:#999; padding:20px;">
        Select a category and task to view steps
    </div>
    `;
}

function onTaskFlowsCategorySelected() {
    const categoryId = document.getElementById("taskFlowsCategorySelect").value;
    const taskSel = document.getElementById("taskFlowsTaskSelect");

    taskSel.innerHTML = `<option value="">(Select task)</option>`;
    selectedTaskFlowsTaskId = null;
    selectedStepNumber = null;
    editingStepNumber = null;

    if (!categoryId) {
    document.getElementById("taskStepsList").innerHTML = `
        <div style="text-align:center; color:#999; padding:20px;">
        Select a category and task to view steps
        </div>
    `;
    return;
    }

    // Filter tasks by category
    const filtered = TASK_FLOWS_TASKS.filter(t => t.category === categoryId);
    filtered.forEach(t => {
    const opt = document.createElement("option");
    opt.value = t.id;
    opt.textContent = `${t.name_en} (${t.level || 'N/A'})`;
    taskSel.appendChild(opt);
    });

    document.getElementById("taskStepsList").innerHTML = `
    <div style="text-align:center; color:#999; padding:20px;">
        Select a task to view steps
    </div>
    `;
}

function onTaskFlowsTaskSelected() {
    const taskId = document.getElementById("taskFlowsTaskSelect").value;
    if (!taskId) {
    selectedTaskFlowsTaskId = null;
    selectedStepNumber = null;
    editingStepNumber = null;
    document.getElementById("taskStepsList").innerHTML = `
        <div style="text-align:center; color:#999; padding:20px;">
        Select a task to view steps
        </div>
    `;
    return;
    }

    selectedTaskFlowsTaskId = taskId;
    selectedStepNumber = null;
    editingStepNumber = null;
    displayTaskSteps();
}

function displayTaskSteps() {
    const container = document.getElementById("taskStepsList");
    container.innerHTML = '';

    if (!selectedTaskFlowsTaskId) return;

    const task = TASK_FLOWS_TASKS.find(t => t.id === selectedTaskFlowsTaskId);
    if (!task || !task.steps || task.steps.length === 0) {
    container.innerHTML = `
        <div style="text-align:center; color:#999; padding:20px;">
        No steps found for this task
        </div>
    `;
    return;
    }

    task.steps.forEach(step => {
    const stepId = String(step.step);
    const isSelected = stepId === selectedStepNumber;
    const isEditing = stepId === editingStepNumber;

    const div = document.createElement('div');
    div.className = 'task-step-row' + (isSelected ? ' selected' : '') + (isEditing ? ' editing' : '');
    div.dataset.stepNumber = stepId;

    div.innerHTML = `
        <div class="task-step-header">
        <span class="task-step-number">Step ${stepId}</span>
        <span class="task-step-title">— ${escapeHtml(step.title_en || '')}</span>
        <button class="task-step-edit-btn">Edit</button>
        </div>
        <div class="task-step-say">${escapeHtml(step.say_ko || '')}</div>
        <div class="task-step-edit-area">
        <textarea id="stepEditTextarea_${stepId}">${escapeHtml(step.say_ko || '')}</textarea>
        <div class="task-step-edit-buttons">
            <button class="task-step-save-btn">Save</button>
            <button class="task-step-cancel-btn">Cancel</button>
        </div>
        </div>
    `;

    // Wire up click handlers
    const editBtn = div.querySelector('.task-step-edit-btn');
    if (editBtn) {
        editBtn.onclick = (e) => startEditingStep(e, stepId);
    }
    const saveBtn = div.querySelector('.task-step-save-btn');
    if (saveBtn) {
        saveBtn.onclick = (e) => saveStepEdit(e, stepId);
    }
    const cancelBtn = div.querySelector('.task-step-cancel-btn');
    if (cancelBtn) {
        cancelBtn.onclick = (e) => cancelStepEdit(e, stepId);
    }

    // Click to select step 
    div.onclick = (e) => {
        if (e.target.closest('.task-step-edit-btn') || e.target.closest('.task-step-save-btn') || e.target.closest('.task-step-cancel-btn') || e.target.closest('.task-step-edit-area') || e.target.tagName === 'TEXTAREA') return;
        selectStep(step);
    };

    container.appendChild(div);
    });
}

function selectStep(stepObj) {
    const stepId = String(stepObj.step);
    selectedStepNumber = stepId;

    const hasLinkedTemplates = Array.isArray(stepObj.choice_template_ids) && stepObj.choice_template_ids.length > 0;

    if (hasLinkedTemplates) {
    const templateId = stepObj.choice_template_ids[0];
    if (!CHOICE_TEMPLATES || CHOICE_TEMPLATES.length === 0) {
        setTaskFlowsStatus("Linked template unavailable (not loaded)");
    } else {
        const template = CHOICE_TEMPLATES.find(t => t.id === templateId);
        if (template) {
        const categorySelect = document.getElementById("choicesCategorySelect");
        categorySelect.value = template.category || '';
        onCategorySelected();

        const templateSelect = document.getElementById("choicesTemplateSelect");
        templateSelect.value = template.id;
        onTemplateSelected();
        setTaskFlowsStatus(`Linked template selected: ${template.name || template.id}`);
        } else {
        setTaskFlowsStatus(`Linked template not found: ${templateId}`);
        }
    }
    } else {
    // Copy say_ko to message input
    const replyText = document.getElementById('replyText');
    replyText.value = stepObj.say_ko || '';
    replyText.focus();
    setTaskFlowsStatus("Step copied to input");
    }

    // Re-render to show selection highlight
    displayTaskSteps();
}

function startEditingStep(e, stepNum) {
    e.stopPropagation();
    editingStepNumber = String(stepNum);
    displayTaskSteps();

    // Focus the textarea
    setTimeout(() => {
    const textarea = document.getElementById(`stepEditTextarea_${stepNum}`);
    if (textarea) textarea.focus();
    }, 50);
}

function cancelStepEdit(e, stepNum) {
    e.stopPropagation();
    editingStepNumber = null;
    displayTaskSteps();
}

async function saveStepEdit(e, stepNum) {
    e.stopPropagation();

    if (!selectedTaskFlowsTaskId) {
    alert("No task selected");
    return;
    }

    const stepId = String(stepNum);
    const textarea = document.getElementById(`stepEditTextarea_${stepId}`);
    if (!textarea) return;

    const newSayKo = textarea.value.trim();

    try {
    const res = await fetch(`${API_URL}/api/task-flows/tasks/${selectedTaskFlowsTaskId}/steps/${encodeURIComponent(stepId)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ say_ko: newSayKo })
    });

    if (res.ok) {
        // Reload task flows to get updated data
        const categoryId = document.getElementById("taskFlowsCategorySelect").value;
        const taskId = selectedTaskFlowsTaskId;

        await loadTaskFlows();

        // Restore selections
        document.getElementById("taskFlowsCategorySelect").value = categoryId;
        onTaskFlowsCategorySelected();
        document.getElementById("taskFlowsTaskSelect").value = taskId;
        selectedTaskFlowsTaskId = taskId;
        editingStepNumber = null;
        displayTaskSteps();

        console.log("Step saved successfully");
        setTaskFlowsStatus("Save ok");
    } else {
        const err = await res.json();
        alert("Save failed: " + (err.detail || res.status));
        setTaskFlowsStatus("Save failed");
    }
    } catch (e) {
    alert("Error saving step: " + e);
    setTaskFlowsStatus("Save failed");
    }
}

    // ==================== CORE CHAT ====================
async function connectToSession() {
    const sessionId = document.getElementById('sessionIdInput').value.trim();
    if (!sessionId) { alert('Enter Session ID'); return; }

    const connectBtn = document.getElementById('connectBtn');
    const hadSession = !!currentSessionId;
    const statusDot = document.getElementById('statusDot');
    const statusText = document.getElementById('statusText');

    connectBtn.disabled = true;
    connectBtn.textContent = 'Connecting...';
    connectBtn.style.background = '#2E7D32';

    try {
    const res = await fetch(`${API_URL}/sessions/${sessionId}`);
    if (!res.ok) {
        const msg = res.status === 404
        ? 'Session not found. Make sure the Android app has started this session.'
        : `Failed to connect (HTTP ${res.status})`;
        alert(msg);
        if (!hadSession) {
        statusDot.classList.remove('connected');
        statusText.textContent = 'Disconnected';
        }
        return;
    }

    currentSessionId = sessionId;
    lastMessageCount = 0;
    currentTutorialStep = 0;
    hideSpeakingIndicator();

    // Clear messages container
    const container = document.getElementById('messagesContainer');
    const speakingIndicator = document.getElementById('speakingIndicator');
    container.innerHTML = '';
    container.innerHTML += '<div style="text-align:center; color:#999; margin-top:50px;">Loading messages...</div>';
    container.appendChild(speakingIndicator);

    // Reset tutorial UI
    updateTutorialUI();

    document.getElementById('replyText').value = '';

    statusText.textContent = `Active Session: ${sessionId}`;
    statusDot.classList.add('connected');
    document.getElementById('replyText').disabled = false;
    document.getElementById('sendButton').disabled = false;

    connectBtn.textContent = 'Connected';
    connectBtn.style.background = '#1976D2';

    connectWebSocket(sessionId);
    startPolling();
    loadMessages();
    } catch (e) {
    alert('Failed to connect: ' + e);
    if (!hadSession) {
        statusDot.classList.remove('connected');
        statusText.textContent = 'Disconnected';
    }
    } finally {
    connectBtn.disabled = false;
    if (currentSessionId) {
        connectBtn.textContent = 'Connected';
        connectBtn.style.background = '#1976D2';
    } else {
        connectBtn.textContent = 'Connect';
        connectBtn.style.background = '#2E7D32';
    }
    }
}

function connectWebSocket(sessionId) {
    if (ws) { ws.close(); ws = null; }
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/wizard/${sessionId}`;
    ws = new WebSocket(wsUrl);
    ws.onmessage = function(event) {
    if (typeof event.data === 'string') {
        try {
        const data = JSON.parse(event.data);
        if (data.type === 'new_message') {
            if (data.event_data && data.event_data.type === 'event') {
            if (data.event_data.event === 'speaking_started' && !isUserSpeaking) {
                showSpeakingIndicator();
            } else if (data.event_data.event === 'speaking_stopped' && isUserSpeaking) {
                hideSpeakingIndicator();
            }
            }
            loadMessages();
        }
        } catch (e) {
        // ignore non-JSON
        }
    }
    };
    ws.onclose = function() {
    setTimeout(() => {
        if (currentSessionId === sessionId) connectWebSocket(sessionId);
    }, 3000);
    };
    ws.onerror = function() {
    console.error('WebSocket error');
    };
}

function startPolling() {
    if (pollInterval) clearInterval(pollInterval);
    // Polling as fallback
    pollInterval = setInterval(loadMessages, 5000);
}

function refreshMessages() {
    if (!currentSessionId) {
    alert('Connect to a session first!');
    return;
    }
    const btn = event.target;
    const originalText = btn.textContent;
    btn.textContent = '⏳ Loading...';
    btn.disabled = true;
    loadMessages().finally(() => {
    btn.textContent = originalText;
    btn.disabled = false;
    });
}

async function loadMessages() {
    if (!currentSessionId) return;
    try {
    const response = await fetch(`${API_URL}/sessions/${currentSessionId}`);
    if (response.ok) {
        const data = await response.json();
        displayMessages(data.messages || []);
    }
    } catch (e) {
    console.error(e);
    }
}

async function sendReply() {
    const text = document.getElementById('replyText').value.trim();
    if (!text || !currentSessionId) return;
    try {
    await fetch(`${API_URL}/message`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ session_id: currentSessionId, role: 'wizard', text })
    });
    document.getElementById('replyText').value = '';
    loadMessages();
    } catch (e) {
    alert('Error sending: ' + e);
    }
}

function displayMessages(messages) {
    const container = document.getElementById('messagesContainer');
    const shouldScroll =
    container.scrollHeight - container.scrollTop === container.clientHeight ||
    lastMessageCount === 0;

    const speakingIndicator = document.getElementById('speakingIndicator');
    container.innerHTML = '';

    if (!messages || messages.length === 0) {
    container.innerHTML += '<div style="text-align:center; color:#999; margin-top:50px;">No messages yet.</div>';
    container.appendChild(speakingIndicator);
    lastMessageCount = 0;
    return;
    }

    let lastTimestamp = null;
    let finalSpeakingState = false;

    messages.forEach((msg) => {
    const tutorialData = parseTutorialCommand(msg.text);
    const choicesData = parseChoicesCommand(msg.text);
    const eventData = parseEventData(msg.text);

    if (eventData) {
        if (eventData.event === 'speaking_started') {
        finalSpeakingState = true;
        } else if (eventData.event === 'speaking_stopped') {
        finalSpeakingState = false;

        const div = document.createElement('div');
        div.className = 'message event';
        const duration = eventData.duration_ms ? formatDuration(eventData.duration_ms) : '';
        div.innerHTML = `
            User spoke for <span class="event-duration">${duration}</span>
            <div class="message-timestamp">${formatTimestamp(eventData.timestamp)}</div>
        `;
        container.appendChild(div);
        lastTimestamp = eventData.timestamp;
        }
        return;
    }

    const currentTimestamp = msg.timestamp ? new Date(msg.timestamp).getTime() : null;
    let gapHtml = '';
    if (lastTimestamp && currentTimestamp && (currentTimestamp - lastTimestamp > 5000)) {
        gapHtml = `<div class="message-gap">${formatGap(currentTimestamp - lastTimestamp)}</div>`;
    }
    if (currentTimestamp) lastTimestamp = currentTimestamp;

    if (tutorialData) {
        const div = document.createElement('div');
        div.className = 'message tutorial';

        if (tutorialData.action === 'hide') {
        div.innerHTML = `${gapHtml}Tutorial Hidden <div class="message-timestamp" style="text-align:left;">${formatTimestamp(msg.timestamp)}</div>`;
        } else {
        div.innerHTML = `${gapHtml}<strong>${escapeHtml(tutorialData.title_ko || '도움말')} ${escapeHtml(String(tutorialData.step))}/${escapeHtml(String(tutorialData.total || 7))}</strong><br>${escapeHtml(tutorialData.body_ko || '')} <div class="message-timestamp" style="text-align:left;">${formatTimestamp(msg.timestamp)}</div>`;
        }

        container.appendChild(div);
    } else if (choicesData) {
        const div = document.createElement('div');
        div.className = 'message wizard';
        div.style.background = '#E8F5E9';
        div.style.border = '2px solid #4CAF50';

        const optionsList = choicesData.options.map((opt, i) => `${i + 1}. ${escapeHtml(opt)}`).join('<br>');

        div.innerHTML = `
        ${gapHtml}
        <div class="message-meta">📋 CHOICES SENT</div>
        <div style="font-weight:bold; color:#2E7D32;">${escapeHtml(choicesData.prompt || '')}</div>
        <div style="margin-top:8px; padding:8px; background:#fff; border-radius:6px; font-size:13px;">${optionsList}</div>
        <div class="message-timestamp">${formatTimestamp(msg.timestamp)}</div>
        `;

        container.appendChild(div);
    } else {
        const div = document.createElement('div');
        div.className = `message ${String(msg.role || '').toLowerCase()}`;
        div.innerHTML = `
        ${gapHtml}
        <div class="message-meta">${escapeHtml(String(msg.role || '').toUpperCase())}</div>
        <div>${escapeHtml(String(msg.text ?? ''))}</div>
        <div class="message-timestamp">${formatTimestamp(msg.timestamp)}</div>
        `;
        container.appendChild(div);
    }
    });

    container.appendChild(speakingIndicator);

    // Apply speaking state
    if (finalSpeakingState && !isUserSpeaking) {
    showSpeakingIndicator();
    } else if (!finalSpeakingState && isUserSpeaking) {
    hideSpeakingIndicator();
    }

    if (shouldScroll) container.scrollTop = container.scrollHeight;
    lastMessageCount = messages.length;
}

// ==================== FREQUENT RESPONSES ====================
async function loadTaskClassifications() {
    try {
    const response = await fetch(`${API_URL}/frequentResponse/taskClassifications`);
    if (response.ok) {
        taskClassifications = await response.json();
        populateClassificationDropdowns();
    }
    } catch (e) { console.error(e); }
}

async function openAddClassificationModal() {
    const name = (prompt('New Category Name:') || '').trim();
    if (!name) return;
    try {
    await fetch(`${API_URL}/frequentResponse/taskClassifications`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
    });
    await loadTaskClassifications();
    } catch (e) { console.error(e); }
}

function populateClassificationDropdowns() {
    const filterSelect = document.getElementById('classificationFilter');
    const modalSelect = document.getElementById('newResponseClassification');
    filterSelect.innerHTML = '<option value="">All Categories</option>';
    modalSelect.innerHTML = '<option value="">Select Category</option>';
    taskClassifications.forEach(c => {
    filterSelect.innerHTML += `<option value="${escapeHtml(c.name)}">${escapeHtml(c.name)}</option>`;
    modalSelect.innerHTML += `<option value="${escapeHtml(c.name)}">${escapeHtml(c.name)}</option>`;
    });
}

function filterFrequentResponses() {
    selectedClassification = document.getElementById('classificationFilter').value;
    displayFrequentResponses();
}

function openAddResponseModal() {
    newResponseContent.value = '';
    document.getElementById('newResponseClassification').value = '';
    addResponseBackdrop.style.display = 'flex';
    newResponseContent.focus();
}

function closeAddResponseModal() {
    addResponseBackdrop.style.display = 'none';
}

async function saveFrequentResponse() {
    const content = newResponseContent.value.trim();
    const taskClassification = document.getElementById('newResponseClassification').value.trim();
    if (!content || !taskClassification) { alert('Please fill all fields'); return; }
    try {
    await fetch(`${API_URL}/frequentResponse`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content, taskClassification })
    });
    await loadFrequentResponses();
    closeAddResponseModal();
    } catch (e) { console.error(e); }
}

function fillText(text) {
    const replyText = document.getElementById('replyText');
    replyText.value = text;
    replyText.focus();
}

async function loadFrequentResponses() {
    try {
    const response = await fetch(`${API_URL}/frequentResponse`);
    if (response.ok) {
        frequentResponses = await response.json();
        displayFrequentResponses();
    }
    } catch (e) { console.error(e); }
}

function displayFrequentResponses() {
    const container = document.getElementById('responsesContainer');
    container.innerHTML = '';

    const filtered = selectedClassification
    ? frequentResponses.filter(r => r.taskClassification === selectedClassification)
    : frequentResponses;

    const sorted = filtered.sort((a, b) => a.order - b.order);
    const minOrder = sorted.length > 0 ? Math.min(...sorted.map(r => r.order)) : 0;
    const maxOrder = sorted.length > 0 ? Math.max(...sorted.map(r => r.order)) : 0;

    sorted.forEach((response) => {
    const div = document.createElement('div');
    div.className = 'frequent-response';
    div.innerHTML = `
        <div class="frequent-response-row">
        <div class="frequent-response-content">${escapeHtml(response.content)}</div>
        <div class="frequent-response-buttons">
            <button class="action-btn delete-btn" title="Delete">Del</button>
            <button class="action-btn up-btn" title="Move Up">↑</button>
            <button class="action-btn down-btn" title="Move Down">↓</button>
        </div>
        </div>
    `;
    div.onclick = () => fillText(response.content);

    div.querySelector('.delete-btn').onclick = (e) => { e.stopPropagation(); deleteFrequentResponse(response.id); };

    const upBtn = div.querySelector('.up-btn');
    upBtn.disabled = response.order === minOrder;
    upBtn.onclick = (e) => { e.stopPropagation(); changeFrequentResponseOrder(response.id, response.order - 1); };

    const downBtn = div.querySelector('.down-btn');
    downBtn.disabled = response.order === maxOrder;
    downBtn.onclick = (e) => { e.stopPropagation(); changeFrequentResponseOrder(response.id, response.order + 1); };

    container.appendChild(div);
    });
}

async function changeFrequentResponseOrder(id, order) {
    const target = frequentResponses.find(r => r.id === id);
    if (!target) return;
    try {
    await fetch(`${API_URL}/frequentResponse/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ order, taskClassification: target.taskClassification, content: target.content })
    });
    await loadFrequentResponses();
    } catch (e) { console.error(e); }
}

async function deleteFrequentResponse(id) {
    if (!confirm('Delete this response?')) return;
    try {
    await fetch(`${API_URL}/frequentResponse/${id}`, { method: 'DELETE' });
    await loadFrequentResponses();
    } catch (e) { console.error(e); }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

document.getElementById('replyText').addEventListener('keydown', function(e) {
    if (e.key === 'Enter' && !e.ctrlKey && !e.shiftKey) { e.preventDefault(); sendReply(); }
});

// Init
window.addEventListener('load', function() {
    updateTutorialUI();
    (async function() {
    await loadTaskClassifications();
    await loadFrequentResponses();
    await loadChoiceTemplates();
    await loadTaskFlows();
    })();
});