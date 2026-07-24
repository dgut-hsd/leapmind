class PromptManager:
    WRONG_REASON_MAP = {
        "concept_unclear": "概念理解不清",
        "careless": "粗心大意",
        "formula_wrong": "公式记错或用错",
        "method_wrong": "解题方法选择错误"
    }

    def build_photo_qa_prompt(self, question: dict, user_profile: dict = None) -> str:
        prompt = f"""你是一位经验丰富的教师，请解答以下题目并提供详细解析。

## 题目信息
科目：{question.get('subject', '未知')}
题干：{question['stem']}
"""
        if question.get('options'):
            prompt += "选项：\n"
            for i, opt in enumerate(question['options']):
                prompt += f"  {chr(65 + i)}. {opt}\n"

        if user_profile:
            grade = user_profile.get('grade', '未知年级')
            weak_points = user_profile.get('weakPoints', [])
            prompt += f"\n## 学生信息\n年级：{grade}\n"
            if weak_points:
                weak_names = [wp.get('kpName', '') for wp in weak_points]
                prompt += f"薄弱知识点：{', '.join(weak_names)}\n"
                prompt += "（请在解析中重点关注上述薄弱知识点的讲解）\n"

        prompt += """
## 输出格式要求
请按以下结构输出，每部分用换行分隔：

### 答案
直接给出正确答案

### 解析步骤
逐步解析，每步标注步骤名

### 涉及知识点
列出本题涉及的知识点

### 易错点提醒
列出本题的常见错误点
"""
        return prompt

    def build_explain_wrong_prompt(
        self,
        question: dict,
        user_answer: dict,
        wrong_reason_tag: str,
        knowledge_points: list,
        user_profile: dict = None
    ) -> str:
        wrong_reason = self.WRONG_REASON_MAP.get(wrong_reason_tag, "未知原因")
        kp_names = [kp.get('name', '') for kp in knowledge_points]

        prompt = f"""你是一位经验丰富的教师，学生做错了一道题，请根据学生的具体错误原因进行针对性讲解。

## 题目信息
题干：{question['stem']}
"""
        if question.get('options'):
            for i, opt in enumerate(question.get('options', [])):
                prompt += f"  {chr(65 + i)}. {opt}\n"

        prompt += f"正确答案：{question.get('correctAnswer', '未知')}\n"
        prompt += f"学生选择：{user_answer.get('selected', '未知')}\n"

        prompt += f"""
## 错误分析
错误原因：{wrong_reason}
涉及知识点：{', '.join(kp_names)}

## 讲解要求
因为学生「{wrong_reason}」，请重点讲解：
- 概念理解不清 → 从基础概念重新梳理，举生活实例帮助理解
- 粗心大意 → 指出容易看错的细节，强调审题技巧
- 公式记错或用错 → 对比正确公式与错误用法，说明公式推导过程
- 解题方法选择错误 → 分析为什么应该用这种方法，其他方法为什么不合适

## 输出格式
请依次输出以下 JSON 对象，每个对象一行：

1. {{"type":"overview","content":"题目整体分析"}}
2. {{"type":"step","stepNumber":1,"title":"审题分析","content":"..."}}
3. {{"type":"step","stepNumber":2,"title":"解题过程","content":"..."}}
4. {{"type":"step","stepNumber":3,"title":"得出答案","content":"..."}}
5. {{"type":"tip","content":"易错点提醒"}}
6. {{"type":"similar","content":"同类题推荐"}}
"""
        if user_profile and user_profile.get('weakPoints'):
            weak_names = [wp.get('kpName', '') for wp in user_profile['weakPoints']]
            prompt += f"\n注意：学生当前薄弱知识点为 {', '.join(weak_names)}，请在讲解中额外关注。\n"

        return prompt
