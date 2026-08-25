package com.treepeople.leapmindtts.service.lesson.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.treepeople.leapmindtts.mapper.AudioSegmentMapper;
import com.treepeople.leapmindtts.pojo.dto.PPTAudioSegment;
import com.treepeople.leapmindtts.pojo.entity.AudioSegment;
import com.treepeople.leapmindtts.service.lesson.PageLevelAudioService;
import com.treepeople.leapmindtts.util.WavMergeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 页面级音频服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PageLevelAudioServiceImpl implements PageLevelAudioService {

    private final AudioSegmentMapper audioSegmentMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean savePageAudio(String courseId, Integer pageNumber, String pageTitle,
                               String slideType, String slideDescription,
                               List<PPTAudioSegment> audioSegments,
                               String audioFormat, Integer sampleRate) {
        if (audioSegments == null || audioSegments.isEmpty()) {
            log.warn("音频片段列表为空，跳过保存，会话ID: {}, 页码: {}", courseId, pageNumber);
            return false;
        }

        try {
            int savedCount = 0;
            for (PPTAudioSegment audioSegment : audioSegments) {
                if (audioSegment == null) continue;

                // upsert：先查已存在记录（audio_segments 有 uk_session_page 唯一约束，
                // 直接 insert 会在重复合成/重试时触发 DuplicateKeyException）
                QueryWrapper<AudioSegment> existsWrapper = new QueryWrapper<>();
                existsWrapper.eq("course_id", courseId)
                             .eq("segment_index", audioSegment.getSegmentIndex());
                AudioSegment existing = audioSegmentMapper.selectOne(existsWrapper);

                if (existing != null) {
                    // 更新现有记录
                    existing.setTextContent(audioSegment.getTextContent());
                    existing.setAudioData(audioSegment.getAudioData());
                    existing.setAudioSize(audioSegment.getAudioSize());
                    existing.setDuration(audioSegment.getDuration());
                    existing.setAudioFormat(audioFormat != null ? audioFormat : audioSegment.getAudioFormat());
                    existing.setSampleRate(sampleRate != null ? sampleRate : audioSegment.getSampleRate());
                    existing.setChecksum(audioSegment.getChecksum());
                    existing.setSlidePageNumber(pageNumber);
                    existing.setSlideTitle(pageTitle);
                    existing.setSlideType(slideType);
                    existing.setSlideDescription(slideDescription);
                    existing.setOriginalText(audioSegment.getOriginalText());
                    existing.setPolishedText(audioSegment.getPolishedText());
                    existing.setSegmentStatus("AUDIO_GENERATED");

                    int result = audioSegmentMapper.updateById(existing);
                    if (result > 0) {
                        savedCount++;
                    } else {
                        log.error("更新音频片段失败，会话ID: {}, 片段索引: {}", courseId, audioSegment.getSegmentIndex());
                    }
                } else {
                    // 插入新记录
                    AudioSegment segment = AudioSegment.builder()
                            .courseId(courseId)
                            .segmentIndex(audioSegment.getSegmentIndex())
                            .textContent(audioSegment.getTextContent())
                            .audioData(audioSegment.getAudioData())
                            .audioSize(audioSegment.getAudioSize())
                            .duration(audioSegment.getDuration())
                            .audioFormat(audioFormat != null ? audioFormat : audioSegment.getAudioFormat())
                            .sampleRate(sampleRate != null ? sampleRate : audioSegment.getSampleRate())
                            .checksum(audioSegment.getChecksum())
                            .slidePageNumber(pageNumber)
                            .slideTitle(pageTitle)
                            .slideType(slideType)
                            .slideDescription(slideDescription)
                            .originalText(audioSegment.getOriginalText())
                            .polishedText(audioSegment.getPolishedText())
                            .segmentStatus("AUDIO_GENERATED")
                            .createdAt(LocalDateTime.now())
                            .build();

                    int result = audioSegmentMapper.insert(segment);
                    if (result > 0) {
                        savedCount++;
                    } else {
                        log.error("保存音频片段失败，会话ID: {}, 片段索引: {}", courseId, audioSegment.getSegmentIndex());
                    }
                }
            }

            log.info("保存页面音频完成，会话ID: {}, 页码: {}, 成功: {}/{}",
                    courseId, pageNumber, savedCount, audioSegments.size());
            return savedCount > 0;

        } catch (Exception e) {
            log.error("保存页面音频异常，会话ID: {}, 页码: {}", courseId, pageNumber, e);
            return false;
        }
    }

    @Override
    public AudioSegment getPageAudio(String courseId, Integer pageNumber) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .eq("slide_page_number", pageNumber)
                       .orderByAsc("segment_index")  // 按片段索引排序
                       .last("LIMIT 1");  // 只取第一条（如果有多条）

            AudioSegment segment = audioSegmentMapper.selectOne(queryWrapper);
            if (segment != null) {
                log.debug("查询页面音频成功，会话ID: {}, 页码: {}", courseId, pageNumber);
            } else {
                log.warn("未找到页面音频，会话ID: {}, 页码: {}", courseId, pageNumber);
            }
            return segment;
        } catch (Exception e) {
            log.error("查询页面音频失败，会话ID: {}, 页码: {}", courseId, pageNumber, e);
            return null;
        }
    }

    @Override
    public byte[] getPageAudioSegment(String courseId, Integer pageNumber, Integer segmentIndex) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .eq("slide_page_number", pageNumber)
                       .eq("segment_index", segmentIndex)
                       .eq("segment_status", "AUDIO_GENERATED");

            AudioSegment segment = audioSegmentMapper.selectOne(queryWrapper);
            if (segment != null && segment.getAudioData() != null) {
                log.debug("获取页面音频片段成功，会话ID: {}, 页码: {}, 片段索引: {}, 大小: {} bytes",
                        courseId, pageNumber, segmentIndex, segment.getAudioData().length);
                return segment.getAudioData();
            } else {
                log.warn("未找到页面音频片段，会话ID: {}, 页码: {}, 片段索引: {}", courseId, pageNumber, segmentIndex);
                return new byte[0];
            }
        } catch (Exception e) {
            log.error("获取页面音频片段失败，会话ID: {}, 页码: {}, 片段索引: {}", courseId, pageNumber, segmentIndex, e);
            return new byte[0];
        }
    }

    @Override
    public List<AudioSegment> getSessionPageAudios(String courseId) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .orderByAsc("slide_page_number");

            List<AudioSegment> segments = audioSegmentMapper.selectList(queryWrapper);
            log.info("查询会话页面音频，会话ID: {}, 找到 {} 个页面", courseId, segments.size());
            return segments;
        } catch (Exception e) {
            log.error("查询会话页面音频失败，会话ID: {}", courseId, e);
            return List.of();
        }
    }

    @Override
    public List<PPTAudioSegment> getPageAudioSegments(String courseId, Integer pageNumber) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .eq("slide_page_number", pageNumber)
                       .eq("segment_status", "AUDIO_GENERATED"); // 只查询已生成音频的

            List<AudioSegment> segments = audioSegmentMapper.selectList(queryWrapper);

            // 转换为PPTAudioSegment
            List<PPTAudioSegment> result = segments.stream()
                    .map(this::convertToPPTAudioSegment)
                    .collect(Collectors.toList());

            log.info("查询页面音频片段，会话ID: {}, 页码: {}, 找到 {} 个片段", courseId, pageNumber, result.size());
            return result;
        } catch (Exception e) {
            log.error("查询页面音频片段失败，会话ID: {}, 页码: {}", courseId, pageNumber, e);
            return List.of();
        }
    }

    @Override
    public byte[] getAudioSegmentByGlobalIndex(String courseId, Integer globalSegmentIndex) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .eq("segment_index", globalSegmentIndex);

            AudioSegment segment = audioSegmentMapper.selectOne(queryWrapper);
            if (segment != null && segment.getAudioData() != null) {
                log.debug("获取音频片段成功，会话ID: {}, 全局索引: {}", courseId, globalSegmentIndex);
                return segment.getAudioData();
            } else {
                log.warn("未找到音频片段，会话ID: {}, 全局索引: {}", courseId, globalSegmentIndex);
                return new byte[0];
            }
        } catch (Exception e) {
            log.error("获取音频片段失败，会话ID: {}, 全局索引: {}", courseId, globalSegmentIndex, e);
            return new byte[0];
        }
    }

    @Override
    public PPTAudioSegment getAudioSegmentInfoByGlobalIndex(String courseId, Integer globalSegmentIndex) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .eq("segment_index", globalSegmentIndex);

            AudioSegment segment = audioSegmentMapper.selectOne(queryWrapper);
            if (segment != null) {
                log.debug("获取音频片段信息成功，会话ID: {}, 全局索引: {}", courseId, globalSegmentIndex);
                return convertToPPTAudioSegment(segment);
            } else {
                log.warn("未找到音频片段信息，会话ID: {}, 全局索引: {}", courseId, globalSegmentIndex);
                return null;
            }
        } catch (Exception e) {
            log.error("获取音频片段信息失败，会话ID: {}, 全局索引: {}", courseId, globalSegmentIndex, e);
            return null;
        }
    }

    @Override
    public boolean deletePageAudio(String courseId, Integer pageNumber) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .eq("slide_page_number", pageNumber);

            int result = audioSegmentMapper.delete(queryWrapper);
            if (result > 0) {
                log.info("删除页面音频成功，会话ID: {}, 页码: {}, 删除 {} 条记录", courseId, pageNumber, result);
            } else {
                log.warn("删除页面音频失败，会话ID: {}, 页码: {}", courseId, pageNumber);
            }
            return result > 0;
        } catch (Exception e) {
            log.error("删除页面音频异常，会话ID: {}, 页码: {}", courseId, pageNumber, e);
            return false;
        }
    }

    @Override
    public int deleteSessionPageAudios(String courseId) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId);

            int result = audioSegmentMapper.delete(queryWrapper);
            log.info("删除会话页面音频，会话ID: {}, 删除 {} 条记录", courseId, result);
            return result;
        } catch (Exception e) {
            log.error("删除会话页面音频异常，会话ID: {}", courseId, e);
            return 0;
        }
    }

    @Override
    public long[] getSessionAudioStats(String courseId) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .eq("segment_status", "AUDIO_GENERATED");

            List<AudioSegment> segments = audioSegmentMapper.selectList(queryWrapper);

            long totalAudioSize = segments.stream()
                    .mapToLong(segment -> segment.getAudioSize() != null ? segment.getAudioSize() : 0L)
                    .sum();

            long totalDuration = segments.stream()
                    .mapToLong(segment -> segment.getDuration() != null ? segment.getDuration() : 0L)
                    .sum();

            long totalSegments = segments.size();

            log.info("获取会话音频统计，会话ID: {}, 总大小: {} bytes, 总时长: {} ms, 总片段数: {}",
                    courseId, totalAudioSize, totalDuration, totalSegments);

            return new long[]{totalAudioSize, totalDuration, totalSegments};
        } catch (Exception e) {
            log.error("获取会话音频统计失败，会话ID: {}", courseId, e);
            return new long[]{0, 0, 0};
        }
    }

    @Override
    public byte[] getPageAudioData(String courseId, Integer pageNumber) {
        try {
            // 获取该页面的所有音频片段（按片段索引排序）
            List<PPTAudioSegment> segments = getPageAudioSegments(courseId, pageNumber);

            if (segments == null || segments.isEmpty()) {
                log.warn("未找到页面音频数据，会话ID: {}, 页码: {}", courseId, pageNumber);
                return new byte[0];
            }

            log.info("获取页面音频数据，会话ID: {}, 页码: {}, 片段数: {}",
                    courseId, pageNumber, segments.size());

            // 如果只有一个片段，直接返回
            if (segments.size() == 1) {
                byte[] audioData = segments.get(0).getAudioData();
                if (audioData != null) {
                    log.info("返回单个音频片段，大小: {} bytes", audioData.length);
                    return audioData;
                }
                return new byte[0];
            }

            // 多个片段，使用 WavMergeUtil 正确合并（剥离后续片段的 WAV header，只拼接 PCM 数据）
            log.info("开始合并 {} 个音频片段", segments.size());

            List<byte[]> audioDataList = new ArrayList<>();
            for (PPTAudioSegment segment : segments) {
                if (segment.getAudioData() != null && segment.getAudioData().length > 0) {
                    audioDataList.add(segment.getAudioData());
                    log.debug("收集片段 {}, 大小: {} bytes", segment.getSegmentIndex(), segment.getAudioData().length);
                }
            }

            if (audioDataList.isEmpty()) {
                log.warn("所有片段音频数据为空");
                return new byte[0];
            }

            byte[] mergedAudio = WavMergeUtil.mergeWavSegments(audioDataList);

            log.info("音频合并完成，总大小: {} bytes", mergedAudio.length);
            return mergedAudio;

        } catch (Exception e) {
            log.error("获取页面音频数据失败，会话ID: {}, 页码: {}", courseId, pageNumber, e);
            return new byte[0];
        }
    }

    @Override
    public boolean hasPageAudio(String courseId, Integer pageNumber) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.select("id") // 只查主键，不加载 audioData
                       .eq("course_id", courseId)
                       .eq("slide_page_number", pageNumber)
                       .eq("segment_status", "AUDIO_GENERATED")
                       .last("LIMIT 1");
            Long count = audioSegmentMapper.selectCount(queryWrapper);
            return count != null && count > 0;
        } catch (Exception e) {
            log.error("检查页面音频存在性失败，会话ID: {}, 页码: {}", courseId, pageNumber, e);
            return false;
        }
    }

    @Override
    public void streamPageAudio(String courseId, Integer pageNumber, OutputStream outputStream) throws IOException {
        // 1. 轻量查询该页所有片段索引（不加载 audioData）
        QueryWrapper<AudioSegment> metaWrapper = new QueryWrapper<>();
        metaWrapper.select("segment_index")
                   .eq("course_id", courseId)
                   .eq("slide_page_number", pageNumber)
                   .eq("segment_status", "AUDIO_GENERATED")
                   .orderByAsc("segment_index");
        List<AudioSegment> metas = audioSegmentMapper.selectList(metaWrapper);

        if (metas == null || metas.isEmpty()) {
            log.warn("未找到页面音频数据，会话ID: {}, 页码: {}", courseId, pageNumber);
            return;
        }

        // 2. 第一遍：逐段加载统计总 PCM 大小并提取 WAV 格式参数（用后即弃，内存峰值 = 单个最大片段）
        short numChannels = 1;
        int sampleRate = 16000;
        short bitsPerSample = 16;
        long totalPcmSize = 0;
        for (AudioSegment meta : metas) {
            byte[] audioData = getAudioSegmentByGlobalIndex(courseId, meta.getSegmentIndex());
            if (audioData == null || audioData.length == 0) {
                continue;
            }
            if (WavMergeUtil.isWav(audioData)) {
                if (totalPcmSize == 0) { // 首次遇到有效片段时提取格式参数
                    numChannels = WavMergeUtil.getChannels(audioData);
                    sampleRate = WavMergeUtil.getSampleRate(audioData);
                    bitsPerSample = WavMergeUtil.getBitsPerSample(audioData);
                }
                totalPcmSize += WavMergeUtil.getPcmDataSize(audioData);
            } else {
                // 非 WAV 片段按裸 PCM 处理
                totalPcmSize += audioData.length;
            }
        }

        if (totalPcmSize <= 0) {
            log.warn("页面音频片段均为空，会话ID: {}, 页码: {}", courseId, pageNumber);
            return;
        }

        // 3. 写入 WAV header（PCM 总量可能超过 int 上限时截断，音频场景一般远小于 2GB）
        int pcmSizeInt = totalPcmSize > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalPcmSize;
        outputStream.write(WavMergeUtil.buildWavHeader(pcmSizeInt, numChannels, sampleRate, bitsPerSample));

        // 4. 第二遍：逐段加载并写入 PCM 数据（剥离各片段 WAV 头）
        for (AudioSegment meta : metas) {
            byte[] audioData = getAudioSegmentByGlobalIndex(courseId, meta.getSegmentIndex());
            if (audioData == null || audioData.length == 0) {
                continue;
            }
            if (WavMergeUtil.isWav(audioData)) {
                int dataOffset = WavMergeUtil.getPcmDataOffset(audioData);
                if (dataOffset < 0) {
                    log.warn("片段无法定位 data chunk，跳过，索引: {}", meta.getSegmentIndex());
                    continue;
                }
                outputStream.write(audioData, dataOffset, audioData.length - dataOffset);
            } else {
                outputStream.write(audioData);
            }
            outputStream.flush();
        }

        log.info("页面音频流式输出完成，会话ID: {}, 页码: {}, 片段数: {}, 总 PCM 大小: {} bytes",
                courseId, pageNumber, metas.size(), totalPcmSize);
    }

    @Override
    public List<PPTAudioSegment> getPageSegmentMetadata(String courseId, Integer pageNumber) {
        // 获取页面的片段元数据（不包含音频数据）
        return getPageAudioSegments(courseId, pageNumber).stream()
                .peek(segment -> segment.setAudioData(null)) // 移除音频数据
                .collect(Collectors.toList());
    }

    // 新增：支持审核流程的方法实现

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveTextOnlySegments(String courseId, Integer pageNumber, String pageTitle,
                                      String slideType, String slideDescription,
                                      List<PPTAudioSegment> textSegments) {
        try {
            for (PPTAudioSegment textSegment : textSegments) {
                // upsert：先查已存在记录（audio_segments 有 uk_session_page 唯一约束，
                // 直接 insert 会在预处理重复执行时触发 DuplicateKeyException）
                QueryWrapper<AudioSegment> existsWrapper = new QueryWrapper<>();
                existsWrapper.eq("course_id", courseId)
                             .eq("segment_index", textSegment.getSegmentIndex());
                AudioSegment existing = audioSegmentMapper.selectOne(existsWrapper);

                if (existing != null) {
                    // 更新现有记录（重新预处理时覆盖旧的文本/音频）
                    existing.setTextContent(textSegment.getTextContent());
                    existing.setAudioData(null);
                    existing.setAudioSize(0L);
                    existing.setDuration(0L);
                    existing.setAudioFormat(textSegment.getAudioFormat());
                    existing.setSampleRate(textSegment.getSampleRate());
                    existing.setSlidePageNumber(pageNumber);
                    existing.setSlideTitle(pageTitle);
                    existing.setSlideType(slideType);
                    existing.setSlideDescription(slideDescription);
                    existing.setOriginalText(textSegment.getOriginalText());
                    existing.setPolishedText(textSegment.getPolishedText());
                    existing.setSegmentStatus("TEXT_ONLY");

                    int result = audioSegmentMapper.updateById(existing);
                    if (result <= 0) {
                        log.error("更新文本片段失败，会话ID: {}, 片段索引: {}", courseId, textSegment.getSegmentIndex());
                        return false;
                    }
                } else {
                    AudioSegment segment = AudioSegment.builder()
                            .courseId(courseId)
                            .segmentIndex(textSegment.getSegmentIndex())
                            .textContent(textSegment.getTextContent())
                            .audioData(null) // 不包含音频数据
                            .audioSize(0L)
                            .duration(0L)
                            .audioFormat(textSegment.getAudioFormat())
                            .sampleRate(textSegment.getSampleRate())
                            .slidePageNumber(pageNumber)
                            .slideTitle(pageTitle)
                            .slideType(slideType)
                            .slideDescription(slideDescription)
                            .originalText(textSegment.getOriginalText())
                            .polishedText(textSegment.getPolishedText())
                            .segmentStatus("TEXT_ONLY") // 标记为仅文本状态
                            .createdAt(LocalDateTime.now())
                            .build();

                    int result = audioSegmentMapper.insert(segment);
                    if (result <= 0) {
                        log.error("保存文本片段失败，会话ID: {}, 片段索引: {}", courseId, textSegment.getSegmentIndex());
                        return false;
                    }
                }
            }

            log.info("保存文本片段成功，会话ID: {}, 页码: {}, 片段数: {}", courseId, pageNumber, textSegments.size());
            return true;
        } catch (Exception e) {
            log.error("保存文本片段异常，会话ID: {}, 页码: {}", courseId, pageNumber, e);
            return false;
        }
    }

    @Override
    public List<AudioSegment> getTextOnlySegments(String courseId) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .eq("segment_status", "TEXT_ONLY")
                       .orderByAsc("slide_page_number", "segment_index");

            List<AudioSegment> segments = audioSegmentMapper.selectList(queryWrapper);
            log.info("查询文本片段，会话ID: {}, 找到 {} 个片段", courseId, segments.size());
            return segments;
        } catch (Exception e) {
            log.error("查询文本片段失败，会话ID: {}", courseId, e);
            return List.of();
        }
    }

    @Override
    public boolean deleteTextOnlySegments(String courseId) {
        try {
            QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("course_id", courseId)
                       .eq("segment_status", "TEXT_ONLY");

            int result = audioSegmentMapper.delete(queryWrapper);
            log.info("删除文本片段，会话ID: {}, 删除数量: {}", courseId, result);
            return true;
        } catch (Exception e) {
            log.error("删除文本片段失败，会话ID: {}", courseId, e);
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSegmentsWithAudio(String courseId, Integer pageNumber, List<PPTAudioSegment> audioSegments) {
        try {
            for (PPTAudioSegment audioSegment : audioSegments) {
                // 更新对应的文本片段，添加音频数据
                QueryWrapper<AudioSegment> queryWrapper = new QueryWrapper<>();
                queryWrapper.eq("course_id", courseId)
                           .eq("segment_index", audioSegment.getSegmentIndex());

                AudioSegment existingSegment = audioSegmentMapper.selectOne(queryWrapper);
                if (existingSegment != null) {
                    existingSegment.setAudioData(audioSegment.getAudioData());
                    existingSegment.setAudioSize(audioSegment.getAudioSize());
                    existingSegment.setDuration(audioSegment.getDuration());
                    existingSegment.setChecksum(audioSegment.getChecksum());
                    existingSegment.setSegmentStatus("AUDIO_GENERATED"); // 更新状态

                    int result = audioSegmentMapper.updateById(existingSegment);
                    if (result <= 0) {
                        log.error("更新音频片段失败，会话ID: {}, 片段索引: {}", courseId, audioSegment.getSegmentIndex());
                        return false;
                    }
                } else {
                    log.warn("未找到对应的文本片段，会话ID: {}, 片段索引: {}", courseId, audioSegment.getSegmentIndex());
                }
            }

            log.info("更新音频片段成功，会话ID: {}, 页码: {}, 片段数: {}", courseId, pageNumber, audioSegments.size());
            return true;
        } catch (Exception e) {
            log.error("更新音频片段异常，会话ID: {}, 页码: {}", courseId, pageNumber, e);
            return false;
        }
    }

    /**
     * 转换AudioSegment为PPTAudioSegment
     */
    private PPTAudioSegment convertToPPTAudioSegment(AudioSegment segment) {
        return PPTAudioSegment.builder()
                .courseId(segment.getCourseId())
                .slidePageNumber(segment.getSlidePageNumber())
                .slideTitle(segment.getSlideTitle())
                .contentPointIndex(0) // 需要根据实际情况设置
                .segmentIndex(segment.getSegmentIndex())
                .slideType(segment.getSlideType())
                .slideDescription(segment.getSlideDescription())
                .originalText(segment.getOriginalText())
                .polishedText(segment.getPolishedText())
                .textContent(segment.getTextContent())
                .audioData(segment.getAudioData())
                .audioSize(segment.getAudioSize())
                .duration(segment.getDuration())
                .audioFormat(segment.getAudioFormat())
                .sampleRate(segment.getSampleRate())
                .checksum(segment.getChecksum())
                .createdAt(segment.getCreatedAt())
                .build();
    }
}
