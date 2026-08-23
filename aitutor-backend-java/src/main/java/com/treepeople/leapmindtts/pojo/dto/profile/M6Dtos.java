package com.treepeople.leapmindtts.pojo.dto.profile;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * M6 用户画像模块数据传输对象。
 * <p>
 * 定义学习事件请求/响应的 DTO 结构，配合 Bean Validation 注解
 * 在 Controller 层进行第一道输入校验。
 * </p>
 */
public final class M6Dtos {
    private M6Dtos() { }

    /**
     * 学习事件请求 DTO。
     *
     * @param eventId       事件唯一标识，字母数字及 . _ : - 组成，最长 64 字符
     * @param userId        目标用户 ID，正整数
     * @param eventType     事件类型（如 answer_question, finish_practice 等），最长 40 字符
     * @param sourceModule  源模块（M1-M7），最长 10 字符
     * @param occurredAt    事件发生时间（RFC3339 格式）
     * @param schemaVersion Schema 版本号，格式为 X.Y
     * @param sessionId     会话 ID，最长 64 字符（可空）
     * @param kpId          知识点 ID，正整数（可空）
     * @param traceId       链路追踪 ID，最长 64 字符（可空）
     * @param data          事件数据载荷（JSON 对象）
     */
    public record LearningEventRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotNull @Positive Long userId,
        @NotBlank @Size(max = 40) String eventType,
        @NotBlank @Size(max = 10) String sourceModule,
        @NotNull OffsetDateTime occurredAt,
        @NotBlank @Pattern(regexp = "\\d+\\.\\d+", message = "Schema 版本格式应为 X.Y") String schemaVersion,
        @Size(max = 64) String sessionId,
        @Positive Long kpId,
        @Size(max = 64) String traceId,
        @NotNull JsonNode data) { }

    /**
     * 批量事件请求 DTO。
     *
     * @param events 事件列表，1~100 条
     */
    public record BatchEventsRequest(@NotNull @Size(min = 1, max = 100) List<JsonNode> events) { }

    /** 事件处理结果。 */
    public record EventResult(Integer index, String eventId, String status, String profileUpdateStatus,
                              String receivedAt, String errorCode, String requestId) { }

    /** 事件确认（ACK）。 */
    public record EventAck(boolean acknowledged, String eventId, String eventStatus, boolean duplicate,
                           String profileUpdateStatus, String receivedAt, String requestId) { }

    /** 错误响应数据。 */
    public record ErrorData(String requestId, String errorCode, List<FieldViolation> details) { }

    /**
     * 字段级校验错误详情。
     * <p>
     * 安全规则：仅包含字段名和校验失败原因，<b>绝不</b>包含被拒绝的输入值，
     * 防止通过错误响应泄露敏感信息。
     * </p>
     */
    public record FieldViolation(String field, String reason) { }
}
