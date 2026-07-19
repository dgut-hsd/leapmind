package com.treepeople.leapmindtts.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * AI 内部调用请求（Java → Python）
 */
@Data
public class AICallRequest {

    /** 调用模块：explain/lesson/prep/qa */
    @NotBlank(message = "模块名不能为空")
    private String moduleName;

    /** 场景类型：answer_question/explain_wrong/generate_lesson */
    @NotBlank(message = "场景类型不能为空")
    private String sceneType;

    /** 完整 Prompt */
    @NotBlank(message = "Prompt 不能为空")
    private String prompt;

    /** 模型选择，默认从配置读取 */
    private String modelName;

    /** 最大输出 token */
    private Integer maxTokens;

    /** 温度参数 */
    private Double temperature;

    /** 扩展参数 */
    private Map<String, Object> extra;
}
