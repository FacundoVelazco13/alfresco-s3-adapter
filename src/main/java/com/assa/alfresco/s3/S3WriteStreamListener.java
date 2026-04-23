package com.assa.alfresco.s3;

import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentStreamListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;

public class S3WriteStreamListener implements ContentStreamListener {

    private static final Log LOG = LogFactory.getLog(S3WriteStreamListener.class);

    private final S3ContentWriter writer;

    public S3WriteStreamListener(S3ContentWriter writer) {
        this.writer = writer;
    }

    @Override
    public void contentStreamClosed() throws ContentIOException {
        File file = writer.getTempFile();
        if (file == null || !file.exists()) {
            LOG.warn("Temp file does not exist, skipping upload");
            return;
        }

        long size = file.length();
        writer.setSize(size);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Writing to s3://" + writer.getBucketName() + "/" + writer.getKey());
        }

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(writer.getBucketName())
                    .key(writer.getKey())
                    .contentLength(size)
                    .build();

            writer.getClient().putObject(putRequest, file.toPath());

            if (LOG.isTraceEnabled()) {
                LOG.trace("Upload completed for bucket " + writer.getBucketName() + " with key " + writer.getKey());
            }
        } catch (Exception e) {
            throw new ContentIOException("S3WriteStreamListener Failed to Upload File for bucket "
                    + writer.getBucketName() + " with key " + writer.getKey(), e);
        } finally {
            file.delete();
        }
    }
}
