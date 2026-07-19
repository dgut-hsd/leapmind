package com.treepeople.leapmindtts.service.explain;

import com.treepeople.leapmindtts.pojo.dto.AICallRequest;
import com.treepeople.leapmindtts.pojo.dto.GenerateExplainRequest;
import com.treepeople.leapmindtts.pojo.dto.PhotoQARequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

/**
 * 答疑/讲题服务
 * 负责构建 Prompt → 调 Python AI → 返回流式结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExplainService {

    private final PythonAIClient pythonAIClient;

    /**
     * 拍照答疑 —— 流式 SSE
     */
    public Flux<String> photoQA(PhotoQARequest request) {
        String prompt = buildPhotoQAPrompt(request);
        AICallRequest aiRequest = buildAIRequest("explain", "answer_question", prompt);
        return pythonAIClient.callStream(aiRequest);
    }

    /**
     * 讲题生成 —— 流式 SSE
     */
    public Flux<String> generateExplain(GenerateExplainRequest request) {
        String prompt = buildExplainPrompt(request);
        AICallRequest aiRequest = buildAIRequest("explain", "explain_wrong", prompt);
        return pythonAIClient.callStream(aiRequest);
    }

    /**
     * 生成唯一的会话 ID
     */
    public String generateId() {
        return "explain_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ─── Prompt 构建（占位，后续对接 Python 组 Prompt 模板）───

    private String buildPhotoQAPrompt(PhotoQARequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("请解答以下题目：\n");
        sb.append("题目：").append(request.getQuestion().getStem()).append("\n");
        if (request.getQuestion().getOptions() != null && !request.getQuestion().getOptions().isEmpty()) {
            sb.append("选项：").append(String.join(" | ", request.getQuestion().getOptions())).append("\n");
        }
        sb.append("科目：").append(request.getQuestion().getSubject() != null ? request.getQuestion().getSubject() : "未知").append("\n");
        sb.append("要求：先给出正确答案，再逐步解析解题思路，标注涉及的知识点，提醒易错点。");
        return sb.toString();
    }

    private String buildExplainPrompt(GenerateExplainRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("请针对以下错题生成个性化讲解：\n");
        if (request.getQuestionStem() != null) {
            sb.append("题目：").append(request.getQuestionStem()).append("\n");
        }
        if (request.getUserAnswer() != null) {
            sb.append("学生错误答案：").append(request.getUserAnswer()).append("\n");
        }
        if (request.getCorrectAnswer() != null) {
            sb.append("正确答案：").append(request.getCorrectAnswer()).append("\n");
        }
        if (request.getWrongReasonTag() != null) {
            sb.append("错误原因：").append(request.getWrongReasonTag()).append("\n");
            sb.append("因为学生在此处出错，请重点讲解该知识点，帮助学生真正理解。\n");
        }
        sb.append("输出结构：①题目分析 ②分步解题 ③核心知识点梳理 ④易错点提醒 ⑤同类题推荐");
        return sb.toString();
    }

    private AICallRequest buildAIRequest(String module, String scene, String prompt) {
        AICallRequest req = new AICallRequest();
        req.setModuleName(module);
        req.setSceneType(scene);
        req.setPrompt(prompt);
        req.setExtra(Map.of("stream", true));
        return req;
    }
}
