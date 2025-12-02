export interface MessageRequest {
  session_id: string;
  role: string;
  text: string;
}

export interface MessageResponse {
  reply: string;
  session_id: string;
  timestamp: string;
}

export interface LogRequest {
  session_id: string;
  event_type: string;
  event_data?: Record<string, any>;
}

const API_BASE_URL = 'http://143.248.57.111:8000';

export const sendMessage = async (
  sessionId: string,
  role: string,
  text: string
): Promise<MessageResponse> => {
  try {
    const response = await fetch(`${API_BASE_URL}/message`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        session_id: sessionId,
        role,
        text,
      }),
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    return await response.json();
  } catch (error) {
    console.error('Error sending message:', error);
    throw error;
  }
};

export const logEvent = async (
  sessionId: string,
  eventType: string,
  eventData?: Record<string, any>
): Promise<void> => {
  try {
    await fetch(`${API_BASE_URL}/log`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        session_id: sessionId,
        event_type: eventType,
        event_data: eventData,
      }),
    });
  } catch (error) {
    console.error('Error logging event:', error);
  }
};