
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;
import java.util.Optional;

public class AwsS3Tool {

    private final static Logger LOGGER = LoggerFactory.getLogger(AwsS3Tool.class);
    private final static String REGION = Optional.ofNullable(System.getenv("REGION")).orElse("eu-west-3");

    private static S3Client s3Client;

    static {
        s3Client = getS3Client();
    }

    public static String getDescription() {
        return """
                Perform actions on AWS S3 buckets
                readDataFromS3, readPdfFileFromS3, writeDataInS3, deleteObjectFromBucket
                """;
    }

    /**
     * List the content of an S3 bucket
     *
     * @param bucketName the name of the bucket we want to get the content
     * @return a list of file as string representing the content of the bucket
     */
    @Tool(description = "Read the content of an S3 bucket")
    public List<String> readBucketContent(String bucketName) {

        LOGGER.info("USE TOOL AwsTool: readBucketContent. bucketName: {}", bucketName);

        List<S3Object> s3Objects = S3.listAllObjectsFromBucket(bucketName);
        return s3Objects.stream()
                .filter(obj -> obj.size() != 0)
                .map(S3Object::key)
                .toList();
    }

    /**
     * List the content of a folder of an S3 bucket
     *
     * @param bucketName the name of the bucket we want to get the content
     * @param folderName the pathname of the folder
     * @return a list of file as string representing the content of the folder
     */
    @Tool(description = "Read the content of a folder of an S3 bucket")
    public List<String> readFolderContent(String bucketName, String folderName) {

        LOGGER.info("USE TOOL AwsTool: readFolderContent. bucketName: {}, folderName: {}", bucketName, folderName);

        List<S3Object> s3Objects = S3.listAllObjectsFromBucketFolder(bucketName, folderName);
        return s3Objects.stream()
                .filter(obj -> obj.size() != 0)
                .map(S3Object::key)
                .map(file -> file.split(folderName + "/")[1])
                .toList();
    }

    /**
     * Read the content of a specific file from  S3 bucket
     *
     * @param bucketName the name of the bucket we want to get the content
     * @param filePath the pathname of the file
     * @return a string representing the content of the file
     */
    @Tool(description = "Read the content of a specific file from  S3 bucket")
    public String readDataFromS3(String bucketName, String filePath) {

        LOGGER.info("USE TOOL AwsTool: readDataFromS3. bucketName: {}, filePath: {}", bucketName, filePath);

        return S3.readDataFromS3(bucketName, filePath);
    }

    /**
     * Read the content of a PDF file from S3 bucket
     *
     * @param bucketName the name of the bucket we want to get the content
     * @param filePath the pathname of the file
     * @return a string representing the content of the file
     */
    @Tool(description = "Read the content of a specific file from  S3 bucket")
    public String readPdfFileFromS3(String bucketName, String filePath) {

        LOGGER.info("USE TOOL AwsTool: readPdfFileFromS3. bucketName: {}, filePath: {}", bucketName, filePath);

        return S3.readPdfFileFromS3(bucketName, filePath);
    }

    /**
     * Write content into a file in a S3 bucket
     *
     * @param bucketName the name of the bucket we want to write the file
     * @param filePath the pathname of the file
     * @param content the content to be written as a String
     */
    @Tool(description = "Write the content to a specific file from  S3 bucket")
    public static void writeDataInS3(String bucketName, String filePath, String content) {

        LOGGER.info("USE TOOL AwsTool: writeDataInS3. bucketName: {}, filePath: {}", bucketName, filePath);

        S3.writeDataInS3(bucketName, filePath, content.getBytes());
    }

    /**
     * Delete a file from  S3 bucket
     *
     * @param bucketName the name of the bucket we want to get the content
     * @param filePath the pathname of the file
     */
    @Tool(description = "Delete a file from  S3 bucket")
    public void deleteObjectFromBucket(String bucketName, String filePath) {

        LOGGER.info("USE TOOL AwsTool: deleteObjectFromBucket. bucketName: {}, filePath: {}", bucketName, filePath);

        S3.deleteObjectFromBucket(bucketName, filePath);

    }

    private static S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.builder()
                    .region(Region.of(REGION))
                    .build();
        }
        return s3Client;
    }

}
