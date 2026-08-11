package com.cognizant.emk.multiagent.infrastructure.tool;

import com.cognizant.emk.multiagent.domain.tool.ToolGroup;
import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The v1 reference tool (EPIC-07 / REQ-TOOL-005). Adapted from
 * {@code backend/docs/AwsS3Tool.java}: Spring-managed, no static state, region from
 * {@code app.aws.region}, AWS SDK default credentials chain (env / instance role).
 *
 * <p>The {@link ToolGroup} annotation declares the catalog entry; the per-method
 * {@link Tool} annotations describe each operation the LLM can invoke at chat time
 * (EPIC-11 plumbs them through {@code ChatRequest}). The two annotations are
 * orthogonal — see the {@link ToolCatalogAdapter} Javadoc.
 *
 * <p>PDF reading is provided as a stub that throws {@link UnsupportedOperationException}
 * — pulling a PDF library is out of scope for EPIC-07. A follow-up can swap the stub
 * for Apache PDFBox without touching the catalog or the chat wiring.
 */
@Component
@ToolGroup(
        name = "AwsS3Tool",
        description = "Perform actions on AWS S3 buckets: list buckets and folders, "
                + "read text files, write data, delete objects.")
public class AwsS3Tool {

    private static final Logger log = LoggerFactory.getLogger(AwsS3Tool.class);

    private final S3Client s3Client;

    public AwsS3Tool(ApplicationProperties properties) {
        this.s3Client = S3Client.builder()
                .region(Region.of(properties.aws().region()))
                .build();
    }

    @Tool(description = "List the content of an S3 bucket as a list of object keys.")
    public List<String> readBucketContent(String bucketName) {
        log.info("USE TOOL AwsS3Tool: readBucketContent. bucketName: {}", bucketName);
        return s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucketName).build()).contents().stream()
                .filter(obj -> obj.size() != 0)
                .map(S3Object::key)
                .toList();
    }

    @Tool(description = "List the content of a folder of an S3 bucket as a list of object keys.")
    public List<String> readFolderContent(String bucketName, String folderName) {
        log.info("USE TOOL AwsS3Tool: readFolderContent. bucketName: {}, folderName: {}",
                bucketName, folderName);
        String prefix = folderName.endsWith("/") ? folderName : folderName + "/";
        return s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucketName).prefix(prefix).build()).contents().stream()
                .filter(obj -> obj.size() != 0)
                .map(S3Object::key)
                .map(key -> key.substring(prefix.length()))
                .toList();
    }

    @Tool(description = "Read the content of a specific text file from an S3 bucket.")
    public String readDataFromS3(String bucketName, String filePath) {
        log.info("USE TOOL AwsS3Tool: readDataFromS3. bucketName: {}, filePath: {}",
                bucketName, filePath);
        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucketName).key(filePath).build());
        return response.asString(StandardCharsets.UTF_8);
    }

    @Tool(description = "Read the content of a specific PDF file from an S3 bucket.")
    public String readPdfFileFromS3(String bucketName, String filePath) {
        log.info("USE TOOL AwsS3Tool: readPdfFileFromS3. bucketName: {}, filePath: {}",
                bucketName, filePath);
        // EPIC-07 ships the stub; a follow-up wires Apache PDFBox or similar without
        // touching the catalog or chat plumbing.
        throw new UnsupportedOperationException(
                "PDF extraction is not implemented yet — see EPIC-07 / US-07-003.");
    }

    @Tool(description = "Write content to a specific file in an S3 bucket.")
    public void writeDataInS3(String bucketName, String filePath, String content) {
        log.info("USE TOOL AwsS3Tool: writeDataInS3. bucketName: {}, filePath: {}",
                bucketName, filePath);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(filePath).build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    @Tool(description = "Delete a file from an S3 bucket.")
    public void deleteObjectFromBucket(String bucketName, String filePath) {
        log.info("USE TOOL AwsS3Tool: deleteObjectFromBucket. bucketName: {}, filePath: {}",
                bucketName, filePath);
        s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucketName).key(filePath).build());
    }
}
