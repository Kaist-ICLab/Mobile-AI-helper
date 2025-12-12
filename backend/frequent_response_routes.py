from typing import List, Dict
import uuid
import logging
import json
import os
from pathlib import Path
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel


logger = logging.getLogger(__name__)
router = APIRouter(prefix="/frequentResponse", tags=["frequentResponse"])

# JSON 파일 경로
DATA_FILE = Path("frequent_responses.json")
TASK_CLASSIFICATIONS_FILE = Path("task_classifications.json")
def load_task_classifications() -> List[Dict[str, str]]:
    """파일에서 task classifications를 로드합니다."""
    if TASK_CLASSIFICATIONS_FILE.exists():
        try:
            with open(TASK_CLASSIFICATIONS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                logger.info(f"Loaded {len(data)} task classifications from disk")
                return data
        except Exception as e:
            logger.error(f"Error loading task classifications: {e}")
            return get_default_task_classifications()
    else:
        # 파일이 없으면 기본값으로 초기화
        default_data = get_default_task_classifications()
        save_task_classifications(default_data)
        return default_data

def get_default_task_classifications() -> List[Dict[str, str]]:
    """기본 task classifications를 반환합니다."""
    return [
        {
            "id": str(uuid.uuid4()),
            "name": "Open Naver",
        }
    ]

def save_task_classifications(data: List[Dict[str, str]]):
    """task classifications를 파일에 저장합니다."""
    try:
        with open(TASK_CLASSIFICATIONS_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        logger.info(f"Saved {len(data)} task classifications to disk")
    except Exception as e:
        logger.error(f"Error saving task classifications: {e}")
        raise

task_classifications: List[Dict[str, str]] = load_task_classifications()

def load_responses() -> List[Dict[str, str]]:
    """파일에서 frequent responses를 로드합니다."""
    if DATA_FILE.exists():
        try:
            with open(DATA_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                logger.info(f"Loaded {len(data)} frequent responses from disk")
                return data
        except Exception as e:
            logger.error(f"Error loading frequent responses: {e}")
            return get_default_responses()
    else:
        # 파일이 없으면 기본값으로 초기화
        default_data = get_default_responses()
        save_responses(default_data)
        return default_data


def get_default_responses() -> List[Dict[str, str]]:
    """기본 frequent responses를 반환합니다."""
    return [
        {
            "id": str(uuid.uuid4()),
            "taskClassification": "Open Naver",
            "content": "안녕하세요! 무엇을 도와드릴까요?",
            "order": 1
        },
    ]


def save_responses(data: List[Dict[str, str]]):
    """frequent responses를 파일에 저장합니다."""
    try:
        with open(DATA_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        logger.info(f"Saved {len(data)} frequent responses to disk")
    except Exception as e:
        logger.error(f"Error saving frequent responses: {e}")
        raise


# 서버 시작 시 파일에서 로드
frequent_responses: List[Dict[str, str]] = load_responses()


class FrequentResponse(BaseModel):
    id: str
    taskClassification: str
    content: str
    order: int


class FrequentResponseCreate(BaseModel):
    taskClassification: str
    content: str
    order : int | None = None

def find_frequent_response(id: str) -> Dict[str, str]:
    for item in frequent_responses:
        if item["id"] == id:
            return item
    return None

@router.get("", response_model=List[FrequentResponse])
async def list_frequent_responses():
    return frequent_responses


@router.post("", response_model=FrequentResponse, status_code=201)
async def add_frequent_response(request: FrequentResponseCreate):
    sameTaskResponse = [r for r in frequent_responses if r["taskClassification"] == request.taskClassification];
    if request.order != None:
        for response in sameTaskResponse:
            if response["order"] >= request.order:
                response["order"] += 1;
    new_item = {
        "id": str(uuid.uuid4()),
        "taskClassification": request.taskClassification,
        "content": request.content,
        "order": request.order if request.order else len(sameTaskResponse)
    }
    frequent_responses.append(new_item)
    save_responses(frequent_responses)
    logger.info(f"Frequent response added: {new_item}")
    return new_item


@router.put("/{response_id}", response_model=FrequentResponse)
async def update_frequent_response(response_id: str, request: FrequentResponseCreate):
    print("update_frequent_response", response_id, request)
    targetItem = find_frequent_response(response_id);
    print("targetItem", targetItem)
    if not targetItem:
        raise HTTPException(status_code=404, detail="Frequent response not found");

    if request.order != None:
        print("request.order", request.order)
        previousOrder = targetItem["order"]
        currentOrder = request.order
        print("change status", previousOrder, currentOrder)
        if previousOrder > currentOrder:
            for response in frequent_responses:
                if response["order"] < previousOrder and response["order"] >= currentOrder:
                    response["order"] += 1;
        elif previousOrder < currentOrder:
            for response in frequent_responses:
                if response["order"] > previousOrder and response["order"] <= currentOrder:
                    response["order"] -= 1;

    targetItem["taskClassification"] = request.taskClassification
    targetItem["content"] = request.content
    targetItem["order"] = request.order if request.order != None else targetItem["order"]
    save_responses(frequent_responses)
    logger.info(f"Frequent response updated: {targetItem}")
    return targetItem


@router.delete("/{response_id}")
async def delete_frequent_response(response_id: str):
    for idx, item in enumerate(frequent_responses):
        if item["id"] == response_id:
            frequent_responses.pop(idx)
            for i in range(idx, len(frequent_responses)):
                frequent_responses[i]["order"] -= 1
            save_responses(frequent_responses)
            logger.info(f"Frequent response deleted: {response_id}")
            return {"status": "deleted", "id": response_id}
    raise HTTPException(status_code=404, detail="Frequent response not found")


class TaskClassification(BaseModel):
    id: str
    name: str

class TaskClassificationCreate(BaseModel):
    name: str

@router.get("/taskClassifications", response_model=List[TaskClassification])
async def list_task_classifications():
    return task_classifications

@router.post("/taskClassifications", response_model=TaskClassification, status_code=201)
async def add_task_classification(request: TaskClassificationCreate):
    new_item = {
        "id": str(uuid.uuid4()),
        "name": request.name
    }
    task_classifications.append(new_item)
    save_task_classifications(task_classifications)
    logger.info(f"Task classification added: {new_item}")
    return new_item

@router.put("/taskClassifications/{task_classification_id}", response_model=TaskClassification)
async def update_task_classification(task_classification_id: str, request: TaskClassificationCreate):
    for item in task_classifications:
        if item["id"] == task_classification_id:
            item["name"] = request.name
            save_task_classifications(task_classifications)
            logger.info(f"Task classification updated: {item}")
            return item
    raise HTTPException(status_code=404, detail="Task classification not found")

@router.delete("/taskClassifications/{task_classification_id}")
async def delete_task_classification(task_classification_id: str):
    for idx, item in enumerate(task_classifications):
        if item["id"] == task_classification_id:
            task_classifications.pop(idx)
            save_task_classifications(task_classifications)
            logger.info(f"Task classification deleted: {task_classification_id}")
            return {"status": "deleted", "id": task_classification_id}
    raise HTTPException(status_code=404, detail="Task classification not found")