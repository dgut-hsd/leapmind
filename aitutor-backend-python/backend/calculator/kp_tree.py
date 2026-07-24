"""
知识点树工具 — 构建知识点层级树，递归查找子知识点薄弱度
"""
from typing import Optional


def build_kp_tree(knowledge_points: list[dict]) -> dict[int, dict]:
    """
    将知识点列表构建为树结构

    返回 {kp_id: { "kp": {id, name, parent_id, ...}, "children": [kp_id, ...] }}
    """
    tree = {}
    for kp in knowledge_points:
        tree[kp["id"]] = {
            "kp": kp,
            "children": [],
        }

    for kp in knowledge_points:
        pid = kp.get("parent_id")
        if pid and pid in tree:
            tree[pid]["children"].append(kp["id"])

    return tree


def find_all_descendants(tree: dict[int, dict], kp_id: int) -> list[int]:
    """
    递归查找一个知识点的所有后代知识点 ID（包括自身）
    """
    if kp_id not in tree:
        return [kp_id]

    result = []
    stack = [kp_id]
    while stack:
        node_id = stack.pop()
        result.append(node_id)
        for child_id in tree[node_id]["children"]:
            stack.append(child_id)

    return result


def find_all_ancestors(tree: dict[int, dict], kp_id: int) -> list[int]:
    """
    从下往上查找所有祖先节点（包括自身）
    """
    from collections import deque

    # 先建立 child -> parent 映射
    child_to_parent = {}
    for node_id, node in tree.items():
        for child_id in node["children"]:
            child_to_parent[child_id] = node_id

    result = []
    current = kp_id
    while current is not None:
        result.append(current)
        current = child_to_parent.get(current)

    return result


def get_sub_weak_points(
    tree: dict[int, dict],
    kp_id: int,
    all_weak_points: list[dict],
    kp_map: dict[int, str],
    max_depth: int = 1,
) -> list[dict]:
    """
    获取某个知识点的直接子知识点薄弱点列表

    参数
    ----
    tree : 知识点树
    kp_id : 父知识点 ID
    all_weak_points : 全量薄弱点结果列表
    kp_map : {kp_id: kp_name}
    max_depth : 递归层级（1=只查直接子级）

    返回
    ----
    [{kp_id, kp_name, weakness_score}, ...]
    """
    if kp_id not in tree:
        return []

    children = tree[kp_id]["children"]
    wp_map = {wp["kp_id"]: wp for wp in all_weak_points}

    sub_list = []
    for child_id in children:
        if child_id in wp_map:
            sub_list.append({
                "kp_id": child_id,
                "kp_name": kp_map.get(child_id, f"未知({child_id})"),
                "weakness_score": wp_map[child_id]["weakness_score"],
            })

    # 按薄弱度降序
    sub_list.sort(key=lambda x: x["weakness_score"], reverse=True)
    return sub_list


def aggregate_parent_weakness(
    all_weak_points: list[dict],
    knowledge_points: list[dict],
) -> list[dict]:
    """
    为父知识点聚合子知识点的薄弱度（如果父知识点本身没有做题数据）

    1. 找出有子节点但没有做题数据的父知识点
    2. 用子节点的平均薄弱度作为父节点的薄弱度
    3. 返回补充后的全量薄弱点列表
    """
    tree = build_kp_tree(knowledge_points)
    kp_map = {kp["id"]: kp["name"] for kp in knowledge_points}

    existing_kp_ids = {wp["kp_id"] for wp in all_weak_points}
    results = list(all_weak_points)

    for kp in knowledge_points:
        kp_id = kp["id"]
        if kp_id in existing_kp_ids:
            continue  # 已有数据，跳过

        descendants = find_all_descendants(tree, kp_id)
        # 去除自身，只看后代
        child_ids = [d for d in descendants if d != kp_id]

        if not child_ids:
            continue

        # 查找后代中有数据的知识点
        child_scores = [
            wp["weakness_score"]
            for wp in all_weak_points
            if wp["kp_id"] in child_ids
        ]

        if not child_scores:
            continue

        avg_score = sum(child_scores) / len(child_scores)
        results.append({
            "kp_id": kp_id,
            "kp_name": kp_map.get(kp_id, f"未知({kp_id})"),
            "weakness_score": round(avg_score, 3),
            "error_count": 0,
            "total_attempts": 0,
            "error_rate": 0.0,
            "recent_correct_rate": 0.0,
            "confusion_count": 0,
            "trend": "stable",
            "last_error_at": None,
            "parent_id": kp.get("parent_id"),
        })

    # 重新排序
    results.sort(key=lambda x: x["weakness_score"], reverse=True)
    return results
