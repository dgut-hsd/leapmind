import sys
from pathlib import Path
sys.path.append(str(Path(__file__).parent.parent))

from fastapi import FastAPI
from api.sentiment_analysis import router as sentiment_router

app = FastAPI(title="表情动作NLP分析服务")
app.include_router(sentiment_router)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8001, reload=True)