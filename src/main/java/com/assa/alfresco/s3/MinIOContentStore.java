package com.assa.alfresco.s3;

import org.alfresco.repo.content.AbstractContentStore;
import org.alfresco.repo.content.ContentStore;
import org.alfresco.repo.content.ContentStoreCreatedEvent;
import org.alfresco.repo.content.UnsupportedContentUrlException;
import org.alfresco.repo.content.filestore.FileContentStore;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.util.GUID;
import org.alfresco.util.Pair;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.Assert;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.io.Serializable;
import java.net.URI;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.Map;

public class MinIOContentStore extends AbstractContentStore
        implements ApplicationContextAware, ApplicationListener<ApplicationEvent>, InitializingBean, DisposableBean {

    private static final Log LOG = LogFactory.getLog(MinIOContentStore.class);

    private ApplicationContext applicationContext;

    private S3Client s3Client;

    private String accessKey;
    private String secretKey;
    private String bucketName;
    private String regionName;
    private String rootDirectory;
    private String endpoint;
    private boolean pathStyleAccess = true;
    private int connectionTimeout = 50000;
    private int maxErrorRetry = 5;
    private long connectionTTL = 60000L;

    public void setConnectionTTL(long connectionTTL) {
        this.connectionTTL = connectionTTL;
    }

    public void setMaxErrorRetry(int maxErrorRetry) {
        this.maxErrorRetry = maxErrorRetry;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    @Override
    public boolean isWriteSupported() {
        return true;
    }

    @Override
    public ContentReader getReader(String contentUrl) {
        String key = makeS3Key(contentUrl);
        return new MinIOContentReader(key, contentUrl, s3Client, bucketName);
    }

    public S3Client getS3Client() {
        return s3Client;
    }

    public void init() {
        AwsCredentialsProvider credentialsProvider;

        if (StringUtils.isNotBlank(this.accessKey) && StringUtils.isNotBlank(this.secretKey)) {
            LOG.debug("Found credentials in properties file");
            credentialsProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(this.accessKey, this.secretKey));
        } else {
            LOG.debug("AWS Credentials not specified in properties, will fallback to default credentials provider");
            credentialsProvider = DefaultCredentialsProvider.builder().build();
        }

        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyleAccess)
                .chunkedEncodingEnabled(false)
                .build();

        var builder = S3Client.builder()
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(s3Config)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(java.time.Duration.ofMillis(connectionTimeout))
                        .socketTimeout(java.time.Duration.ofMillis(connectionTTL)));

        if (StringUtils.isNotBlank(endpoint)) {
            LOG.debug("Using custom endpoint: " + endpoint);
            builder.endpointOverride(URI.create(endpoint));
            builder.region(Region.of(regionName));
        } else {
            LOG.debug("Using default Amazon S3 endpoint with region " + regionName);
            builder.region(Region.of(regionName));
        }

        s3Client = builder.build();

        LOG.info("MinIO/S3 Content Store initialized. Bucket: " + bucketName + ", Endpoint: "
                + (StringUtils.isNotBlank(endpoint) ? endpoint : "default AWS"));
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public void setRegionName(String regionName) {
        this.regionName = regionName;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public void setRootDirectory(String rootDirectory) {
        String dir = rootDirectory;
        if (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        this.rootDirectory = dir;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    protected ContentWriter getWriterInternal(ContentReader existingContentReader, String newContentUrl) {
        String contentUrl = newContentUrl;
        if (StringUtils.isBlank(contentUrl)) {
            contentUrl = createNewUrl();
        }
        String key = makeS3Key(contentUrl);
        return new MinIOContentWriter(bucketName, key, contentUrl, existingContentReader, s3Client);
    }

    public static String createNewUrl() {
        Calendar calendar = new GregorianCalendar();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1;
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        StringBuilder sb = new StringBuilder(20);
        sb.append(FileContentStore.STORE_PROTOCOL)
                .append(ContentStore.PROTOCOL_DELIMITER)
                .append(year).append('/')
                .append(month).append('/')
                .append(day).append('/')
                .append(hour).append('/')
                .append(minute).append('/')
                .append(GUID.generate()).append(".bin");
        return sb.toString();
    }

    private String makeS3Key(String contentUrl) {
        Pair<String, String> urlParts = super.getContentUrlParts(contentUrl);
        String protocol = urlParts.getFirst();
        String relativePath = urlParts.getSecond();
        if (!protocol.equals(FileContentStore.STORE_PROTOCOL)) {
            throw new UnsupportedContentUrlException(this, protocol + PROTOCOL_DELIMITER + relativePath);
        }
        return rootDirectory + "/" + relativePath;
    }

    @Override
    public boolean delete(String contentUrl) {
        try {
            String key = makeS3Key(contentUrl);
            if (LOG.isTraceEnabled()) {
                LOG.trace("Deleting object from S3 with url: " + contentUrl + ", key: " + key);
            }
            s3Client.deleteObject(builder -> builder.bucket(bucketName).key(key));
            return true;
        } catch (Exception e) {
            LOG.trace("Error deleting S3 Object", e);
        }
        return false;
    }

    private void publishEvent(ApplicationContext context, Map<String, Serializable> extendedEventParams) {
        context.publishEvent(new ContentStoreCreatedEvent(this, extendedEventParams));
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ContextRefreshedEvent && event.getSource() == this.applicationContext) {
            publishEvent(((ContextRefreshedEvent) event).getApplicationContext(), Collections.<String, Serializable>emptyMap());
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Assert.hasText(bucketName, "s3.bucketName is required");
        Assert.hasText(regionName, "s3.regionName is required");
        Assert.hasText(rootDirectory, "s3.rootDirectory is required");
        Assert.isTrue(maxErrorRetry >= 0, "s3.client.maxErrorRetry must be >= 0");
        Assert.isTrue(connectionTTL >= 0, "s3.client.connectionTTL must be >= 0");
        Assert.isTrue(connectionTimeout >= 0, "s3.client.connectionTimeout must be >= 0");
    }

    @Override
    public void destroy() throws Exception {
        if (s3Client != null) {
            s3Client.close();
            LOG.info("S3 Client closed");
        }
    }
}
