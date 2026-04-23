package com.assa.alfresco.s3;

import org.alfresco.rad.test.AbstractAlfrescoIT;
import org.alfresco.repo.content.ContentContext;
import org.alfresco.repo.content.ContentStore;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class S3ContentStoreIT extends AbstractAlfrescoIT {

    @Test
    public void testWriteAndReadContent() {
        ApplicationContext ctx = getApplicationContext();
        ContentStore s3ContentStore = (ContentStore) ctx.getBean("assa.s3ContentStore");

        final String TEST_TEXT_CONTENT = "Hello S3 from Alfresco!";
        ContentContext context = new ContentContext(null, null);
        ContentWriter writer = s3ContentStore.getWriter(context);
        writer.putContent(TEST_TEXT_CONTENT);
        String contentUrl = writer.getContentUrl();

        try {
            ContentReader reader = s3ContentStore.getReader(contentUrl);
            String contentString = reader.getContentString();
            assertEquals(TEST_TEXT_CONTENT, contentString);
        } finally {
            boolean deleted = s3ContentStore.delete(contentUrl);
            assertTrue(deleted);
        }
    }

    @Test
    public void testWriteSupported() {
        ApplicationContext ctx = getApplicationContext();
        ContentStore s3ContentStore = (ContentStore) ctx.getBean("assa.s3ContentStore");
        assertTrue(s3ContentStore.isWriteSupported());
    }
}
