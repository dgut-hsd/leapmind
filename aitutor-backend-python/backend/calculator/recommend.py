"""
推荐练习 — 基于薄弱点推荐未做过的题目
"""
from database.mysql_connector import Database


def recommend_questions(
    user_id: int,
    kp_id: int,
    count: int = 10,
    db: Database = None,
) -> tuple[str, list[dict]]:
    """
    为用户推荐某个知识点的练习题目

    参数
    ----
    user_id : int
    kp_id : int
    count : int
        推荐题目数量
    db : Database

    返回
    ----
    (kp_name, questions)
        kp_name : str
        questions : list[dict]  每项含 id, kp_id, content, difficulty
    """
    should_close = False
    if db is None:
        db = Database()
        should_close = True

    try:
        # 1. 获取知识点名称
        kps = db.fetch_knowledge_points()
        kp_map = {kp["id"]: kp["name"] for kp in kps}
        kp_name = kp_map.get(kp_id, f"未知知识点({kp_id})")

        # 2. 获取该知识点下所有题目
        all_questions = db.fetch_questions_by_kp(kp_id)

        # 3. 获取用户已做过的题目
        answered_ids = set(db.fetch_answered_question_ids(user_id, kp_id))

        # 4. 过滤出未做的题目
        unanswered = [
            q for q in all_questions if q["id"] not in answered_ids
        ]

        # 5. 按难度排序（易 → 难），取前 count 个
        unanswered.sort(key=lambda q: q["difficulty"])
        recommended = unanswered[:count]

        return kp_name, recommended
    finally:
        if should_close:
            pass  # Database 实例没有显式 close 方法，连接会自行关闭
