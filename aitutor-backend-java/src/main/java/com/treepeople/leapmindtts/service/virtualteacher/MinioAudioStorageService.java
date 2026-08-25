package com.treepeople.leapmindtts.service.virtualteacher;

import com.treepeople.leapmindtts.config.VirtualTeacherProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "virtual-teacher.storage", name = "type", havingValue = "minio")
public class MinioAudioStorageService implements AudioStorageService {

    /** 合法 objectKey 格式：64 位十六进制 + .wav（SHA-256 哈希命名）。 */
    private static final Pattern OBJECT_KEY_PATTERN = Pattern.compile("[a-f0-9]{64}\\.wav");

    private final MinioClient client;
    private final String bucket;

    public MinioAudioStorageService(VirtualTeacherProperties properties) {
        VirtualTeacherProperties.Storage storage = properties.getStorage();
        this.bucket = storage.getBucket();
        this.client = MinioClient.builder()
                .endpoint(storage.getEndpoint())
                .credentials(storage.getAccessKey(), storage.getSecretKey())
                .build();
    }

    @PostConstruct
    public void initializeBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化 MinIO Bucket 失败", e);
        }
    }

    @Override
    public void store(String objectKey, byte[] data, String contentType) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(input, data.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("保存音频到 MinIO 失败", e);
        }
    }

    @Override
    public Optional<byte[]> load(String objectKey) {
        validateObjectKey(objectKey);
        try (var input = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return Optional.of(input.readAllBytes());
        } catch (Exception e) {
            log.warn("从 MinIO 加载音频失败: objectKey={}", objectKey, e);
            return Optional.empty();
        }
    }

    @Override
    public String createReadUrl(String objectKey) {
        validateObjectKey(objectKey);
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(24, TimeUnit.HOURS)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("生成 MinIO 音频地址失败", e);
        }
    }

    /**
     * 校验 objectKey 格式，防止路径遍历和 IDOR 攻击。
     * <p>
     * 仅允许 64 位十六进制 + .wav 后缀（SHA-256 哈希命名），
     * 与 {@link LocalAudioStorageService} 的安全等级保持一致。
     * </p>
     */
    private void validateObjectKey(String objectKey) {
        if (objectKey == null || !OBJECT_KEY_PATTERN.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("非法音频对象键");
        }
    }
}
