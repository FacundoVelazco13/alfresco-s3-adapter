package com.assa.alfresco.s3;

import org.alfresco.repo.content.AbstractContentReader;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentStreamListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

public class MinIOContentReader extends AbstractContentReader {

    private static final Log LOG = LogFactory.getLog(MinIOContentReader.class);

    private final String key;
    private final S3Client s3Client;
    private final String bucket;
    private ResponseInputStream<GetObjectResponse> s3ResponseStream;
    private HeadObjectResponse headObjectResponse;

    protected MinIOContentReader(String key, String contentUrl, S3Client s3Client, String bucket) {
        super(contentUrl);
        this.key = key;
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    protected void closeFileObject() throws IOException {
        if (s3ResponseStream != null) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Closing s3 response stream for reader " + key);
            }
            s3ResponseStream.close();
            s3ResponseStream = null;
        }
    }

    protected void lazyInitFileObject() {
        if (s3ResponseStream == null) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Lazy init for file object for " + bucket + " - " + key);
            }
            this.s3ResponseStream = getObject();
        }
    }

    protected void lazyInitFileMetadata() {
        if (headObjectResponse == null) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Lazy init for file metadata for " + bucket + " - " + key);
            }
            headObjectResponse = getHeadObject();
        }
    }

    @Override
    protected ContentReader createReader() throws ContentIOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Called createReader for contentUrl -> " + getContentUrl() + ", Key: " + key);
        }
        return new MinIOContentReader(key, getContentUrl(), s3Client, bucket);
    }

    @Override
    protected ReadableByteChannel getDirectReadableChannel() throws ContentIOException {
        try {
            lazyInitFileObject();
        } catch (Exception e) {
            throw new ContentIOException("Content object does not exist on S3", e);
        }
        if (!exists()) {
            throw new ContentIOException("Content object does not exist on S3");
        }

        try {
            ContentStreamListener s3StreamListener = new ContentStreamListener() {
                @Override
                public void contentStreamClosed() throws ContentIOException {
                    try {
                        LOG.trace("Closing s3 object stream on content stream closed.");
                        closeFileObject();
                    } catch (IOException e) {
                        throw new ContentIOException("Failed to close underlying s3 object", e);
                    }
                }
            };
            this.addListener(s3StreamListener);
            return Channels.newChannel(s3ResponseStream);
        } catch (Exception e) {
            throw new ContentIOException("Unable to retrieve content object from S3", e);
        }
    }

    @Override
    public boolean exists() {
        try {
            lazyInitFileMetadata();
        } catch (Exception e) {
            LOG.trace("Could not fetch metadata of object. It is probably removed.", e);
            return false;
        }
        return headObjectResponse != null;
    }

    @Override
    public long getLastModified() {
        try {
            lazyInitFileMetadata();
        } catch (Exception e) {
            LOG.trace("Could not fetch metadata of object. It is probably removed.", e);
            return 0L;
        }
        if (!exists()) {
            return 0L;
        }
        return headObjectResponse.lastModified().toEpochMilli();
    }

    @Override
    public long getSize() {
        try {
            lazyInitFileMetadata();
        } catch (Exception e) {
            LOG.trace("Could not fetch metadata of object. It is probably removed.", e);
            return 0L;
        }
        if (!exists()) {
            return 0L;
        }
        return headObjectResponse.contentLength();
    }

    private ResponseInputStream<GetObjectResponse> getObject() {
        try {
            if (LOG.isTraceEnabled()) {
                LOG.trace("GETTING OBJECT - BUCKET: " + bucket + " KEY: " + key);
            }
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return s3Client.getObject(request);
        } catch (Exception e) {
            LOG.error("Unable to fetch S3 Object", e);
            return null;
        }
    }

    private HeadObjectResponse getHeadObject() {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("GETTING OBJECT METADATA - BUCKET: " + bucket + " KEY: " + key);
            }
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return s3Client.headObject(request);
        } catch (NoSuchKeyException e) {
            LOG.trace("Object does not exist: " + bucket + "/" + key);
            return null;
        } catch (Exception e) {
            LOG.trace("Could not fetch metadata of object", e);
            return null;
        }
    }
}
