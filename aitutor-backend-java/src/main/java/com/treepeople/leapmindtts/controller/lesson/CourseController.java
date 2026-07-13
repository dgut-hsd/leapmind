package com.treepeople.leapmindtts.controller;

import com.treepeople.leapmindtts.pojo.dto.AdminCourseSectionDTO;
import com.treepeople.leapmindtts.pojo.dto.CourseSectionDTO;
import com.treepeople.leapmindtts.pojo.result.ApiResponse;
import com.treepeople.leapmindtts.pojo.vo.CourseSectionVO;
import com.treepeople.leapmindtts.pojo.vo.CourseVO;
import com.treepeople.leapmindtts.service.CourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @ Author：YangYu
 * @ Package：com.treepeople.leapmindtts.controller
 * @ Project：leapmind-tts - 语音分段
 * @ Description: 课本章节
 * @ Date：2025/11/2  17:13
 */
@Slf4j
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService CourseService;

    /**
     * 根据阶段年级学期学科获取课本章节
     * @param adminCourseSectionDTO
     * @return
     */
    @PostMapping("/section")
    public ResponseEntity<ApiResponse<List<CourseVO>>> getCourseSectionByStageGradeSemesterSubject(@RequestBody AdminCourseSectionDTO adminCourseSectionDTO) {
        log.info("查询课程：{}",adminCourseSectionDTO);
        try {
            List<CourseVO> courseSectionVOList = CourseService.getCourseSectionByStageGradeSemesterSubject(adminCourseSectionDTO);
            return ResponseEntity.ok(ApiResponse.success(courseSectionVOList, "获取课本章节成功"));
        }catch (Exception e){
            log.error("获取课本章节失败: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "没有这个条件下的课本章节"));
        }
    }
}
