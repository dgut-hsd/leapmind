# 虚拟讲师表情口型手势NLP分析服务（Python作业）
## 项目说明
基于FastAPI开发，接收讲课文本，语义分析生成带时间戳表情、手势、口型序列，供Java后端调用。
## 环境依赖
Python 3.11
安装命令：
pip install -r requirements.txt
## 项目启动
1. 终端进入项目根目录
2. 执行 python main.py
3. 接口文档地址：http://127.0.0.1:8001/docs
## 接口说明
核心接口：POST /api/internal/ai/analyze-sentiment
入参：讲课原文文本
出参：分段口型、情绪表情、触发手势时间戳数据
## 目录结构
python-ai-service/
├── api/            # FastAPI接口路由
├── models/         # 交互数据结构体schemas
├── services/      # NLP语义、表情、手势核心逻辑
├── main.py         # 服务启动入口
├── requirements.txt # 项目全部依赖
└── .gitignore     # 过滤无用缓存文件