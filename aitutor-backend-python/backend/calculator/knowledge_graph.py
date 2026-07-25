"""
知识图谱构建 — 基于 networkx 构建知识点关联图

功能：
  - 构建知识点层级有向图（parent → child）
  - 计算节点重要性（PageRank，反映知识点在体系中的核心程度）
  - 查找前置/后置知识点（predecessor/successor 关系）
  - 基于图谱增强薄弱度分析（关联薄弱传播）
"""
from typing import Optional

import networkx as nx


def build_knowledge_graph(knowledge_points: list[dict]) -> nx.DiGraph:
    """
    构建知识点有向图

    边方向：父知识点 → 子知识点（例如 几何 → 勾股定理）

    参数
    ----
    knowledge_points : list[dict]
        每项含 id, name, parent_id

    返回
    ----
    nx.DiGraph
        节点属性: id, name, level
    """
    G = nx.DiGraph()

    for kp in knowledge_points:
        G.add_node(kp["id"], id=kp["id"], name=kp["name"], level=kp.get("level", 1))

    for kp in knowledge_points:
        pid = kp.get("parent_id")
        if pid is not None:
            G.add_edge(pid, kp["id"])  # 父 → 子

    return G


def compute_node_importance(G: nx.DiGraph) -> dict[int, float]:
    """
    计算知识点重要性（PageRank）

    核心知识点（如"几何"、"代数"）的 PageRank 值会更高，
    可用于在薄弱度计算中增加权重：核心知识点薄弱的影响更大。

    返回 {kp_id: importance_score}，范围 0~1
    """
    if len(G) == 0:
        return {}

    pr = nx.pagerank(G, alpha=0.85)
    # 归一化到 0~1
    max_pr = max(pr.values()) if pr else 1
    return {k: round(v / max_pr, 3) for k, v in pr.items()}


def find_related_kps(
    G: nx.DiGraph,
    kp_id: int,
    max_distance: int = 2,
) -> list[dict]:
    """
    查找一个知识点的关联知识点（前置/后置/同级）

    返回：
    [
        {"kpId": 1, "kpName": "几何", "relation": "parent"},
        {"kpId": 101, "kpName": "勾股定理逆定理", "relation": "child"},
        ...
    ]
    """
    if kp_id not in G:
        return []

    related = []

    # 父节点（前置知识）
    for pred in G.predecessors(kp_id):
        related.append({
            "kp_id": pred,
            "kp_name": G.nodes[pred].get("name", ""),
            "relation": "parent",
        })

    # 子节点（后置知识）
    for succ in G.successors(kp_id):
        related.append({
            "kp_id": succ,
            "kp_name": G.nodes[succ].get("name", ""),
            "relation": "child",
        })

    # 兄弟节点（同父）
    parents = list(G.predecessors(kp_id))
    for parent in parents:
        for sibling in G.successors(parent):
            if sibling != kp_id:
                related.append({
                    "kp_id": sibling,
                    "kp_name": G.nodes[sibling].get("name", ""),
                    "relation": "sibling",
                })

    return related


def get_graph_data(
    G: nx.DiGraph,
    weak_points: list[dict],
) -> dict:
    """
    获取知识图谱可视化数据（供前端 ECharts 力导向图使用）

    返回：
    {
        "nodes": [{"id": 1, "name": "几何", "weaknessScore": 0.72}, ...],
        "edges": [{"source": 1, "target": 101}, ...]
    }
    """
    kp_scores = {wp["kp_id"]: wp["weakness_score"] for wp in weak_points}

    nodes = []
    for node_id, data in G.nodes(data=True):
        nodes.append({
            "id": node_id,
            "name": data.get("name", ""),
            "weaknessScore": kp_scores.get(node_id, 0),
        })

    edges = []
    for u, v in G.edges():
        edges.append({"source": u, "target": v})

    return {"nodes": nodes, "edges": edges}
