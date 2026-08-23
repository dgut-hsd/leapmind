package com.treepeople.leapmindtts.service.profile;

import com.treepeople.leapmindtts.pojo.dto.profile.M6ProfileDtos.KnowledgeStatusResponse;
import com.treepeople.leapmindtts.pojo.dto.profile.M6ProfileDtos.ProfileView;
import com.treepeople.leapmindtts.pojo.dto.profile.M6ProfileDtos.SummaryView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * M6 用户画像查询服务接口。
 * <p>
 * 提供画像完整查询、场景化摘要和知识点掌握度查询。
 * 内置缓存（Redis）和权限校验（JWT + DB 双重验证）。
 * </p>
 */
public interface UserProfileQueryService {

    /**
     * 查询用户完整画像。
     *
     * @param userId  目标用户 ID
     * @param request HTTP 请求（用于鉴权）
     * @return 画像视图（READY/STALE 返回 FullProfile，NOT_READY 返回 NotReadyProfile）
     */
    ProfileView profile(Long userId, HttpServletRequest request);

    /**
     * 查询场景化最小摘要。
     *
     * @param userId  目标用户 ID
     * @param scene   场景类型（explaining / lecturing / conversation / lesson_prep / photo_qa）
     * @param kpId    知识点 ID（lesson_prep 场景需要，其他场景可空）
     * @param request HTTP 请求（用于鉴权）
     * @return 摘要视图（按场景类型差异化组装）
     */
    SummaryView summary(Long userId, String scene, Long kpId, HttpServletRequest request);

    /**
     * 按知识点 ID 列表查询掌握度状态。
     *
     * @param userId  目标用户 ID
     * @param kpIds   知识点 ID 列表（去重保序）
     * @param request HTTP 请求（用于鉴权）
     * @return 知识点掌握度响应
     */
    KnowledgeStatusResponse knowledge(Long userId, List<Long> kpIds, HttpServletRequest request);
}
