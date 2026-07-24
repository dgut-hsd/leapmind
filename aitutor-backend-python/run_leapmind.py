"""
LeapMind AI Service - Standalone entry point
Run: conda run -n leapmind python run_leapmind.py
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), 'src'))

from src.landppt.leapmind.main import app

if __name__ == "__main__":
    import uvicorn
    from dotenv import load_dotenv
    env_file = os.path.join(os.path.dirname(__file__), '.env')
    load_dotenv(env_file, override=True)
    from landppt.core.config import reload_ai_config
    reload_ai_config()
    uvicorn.run(
        "src.landppt.leapmind.main:app",
        host="0.0.0.0",
        port=8001,
        reload=False,
        log_level="info"
    )
