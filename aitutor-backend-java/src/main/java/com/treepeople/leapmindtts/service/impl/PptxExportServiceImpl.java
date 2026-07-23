package com.treepeople.leapmindtts.service.impl;

import com.treepeople.leapmindtts.service.PptxExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PPT导出服务桩实现（暂未实现，仅返回占位URL）
 * TODO: 由王佳雯实现真正的PPTX导出逻辑
 */
@Slf4j
@Service
public class PptxExportServiceImpl implements PptxExportService {

    @Override
    public String export(Long prepId) {
        log.info("PPT导出服务被调用，备课ID: {}（暂未实现真实导出）", prepId);
        return "/download/placeholder-" + prepId + ".pptx";
    }
}
