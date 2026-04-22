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

public class MinIOCachingContentStoreIT extends AbstractAlfrescoIT {

    @Test
    public void testWriteAndReadThroughCache() {
        ApplicationContext ctx = getApplicationContext();
        ContentStore cachingContentStore = (ContentStore) ctx.getBean("assa.minioCachingContentStore");

        final String TEST_TEXT_CONTENT = "Cached content test";
        ContentContext context = new ContentContext(null, null);
        ContentWriter writer = cachingContentStore.getWriter(context);
        writer.putContent(TEST_TEXT_CONTENT);
        String contentUrl = writer.getContentUrl();

        try {
            ContentReader reader = cachingContentStore.getReader(contentUrl);
            String contentString = reader.getContentString();
            assertEquals(TEST_TEXT_CONTENT, contentString);
        } finally {
            boolean deleted = cachingContentStore.delete(contentUrl);
            assertTrue(deleted);
        }
    }

    @Test
    public void testCacheWriteSupported() {
        ApplicationContext ctx = getApplicationContext();
        ContentStore cachingContentStore = (ContentStore) ctx.getBean("assa.minioCachingContentStore");
        assertTrue(cachingContentStore.isWriteSupported());
    }
}
