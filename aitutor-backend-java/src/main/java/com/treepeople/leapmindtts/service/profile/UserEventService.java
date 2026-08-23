package com.treepeople.leapmindtts.service.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.treepeople.leapmindtts.pojo.dto.profile.M6Dtos.EventAck;
import com.treepeople.leapmindtts.pojo.dto.profile.M6Dtos.EventResult;
import com.treepeople.leapmindtts.pojo.dto.profile.M6Dtos.LearningEventRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * M6 用户学习事件服务接口。
 * <p>
 * 负责学习事件的接入、幂等校验和持久化。事件写入后由外部 Python AI 引擎
 * 异步消费并更新用户画像。
 * </p>
 */
public interface UserEventService {

    /**
     * 记录单条学习事件（幂等）。
     *
     * @param userId  目标用户 ID（路径参数）
     * @param event   学习事件请求
     * @param request HTTP 请求（用于提取 JWT 中的操作者信息）
     * @return 事件确认（ACK），包含事件状态和幂等信息
     */
    EventAck record(Long userId, LearningEventRequest event, HttpServletRequest request);

    /**
     * 批量记录学习事件（保序，逐项报告失败）。
     *
     * @param userId  目标用户 ID（路径参数）
     * @param events  事件列表（1~100 条 JSON 节点）
     * @param request HTTP 请求
     * @return 每条事件的处理结果
     */
    List<EventResult> batch(Long userId, List<JsonNode> events, HttpServletRequest request);
}
