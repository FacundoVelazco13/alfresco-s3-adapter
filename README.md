# S3 Open Connector

S3 Content Store connector for Alfresco Content Services 26.1 Community. Supports AWS S3 (including IAM roles via DefaultCredentialsProvider), MinIO, and any S3-compatible storage. Replaces the default content storage (local filesystem) with an S3-compatible bucket, with a local cache layer for performance.

## Architecture

```
ACS Platform
    │
    ▼
fileContentStore (alias)
    │
    ▼
CachingContentStore
    │           │
    │    Cache local (/tmp/cachedcontent)
    │           │
    ▼
S3ContentStore (backing store)
    │
    ▼
S3 Bucket (AWS / MinIO / any S3-compatible)
    └── alfresco/contentstore/<year>/<month>/<day>/<hour>/<minute>/<guid>.bin
```

### Connector layers

| Layer | Class | Responsibility |
|---|---|---|
| **CachingContentStore** | `org.alfresco.repo.content.caching.CachingContentStore` | Local disk cache. First read/write layer. Delegates to backing store when content is not cached. |
| **ContentCacheImpl** | `org.alfresco.repo.content.caching.ContentCacheImpl` | Cache implementation: memory (Infinispan) + disk. Manages lifecycle of cached files. |
| **StandardQuotaStrategy** | `org.alfresco.repo.content.caching.quota.StandardQuotaStrategy` | Cache quota control. When `maxUsageMB` is exceeded, cleans old files. |
| **CachedContentCleaner** | `org.alfresco.repo.content.caching.cleanup.CachedContentCleaner` | Job that cleans cached files already uploaded to the backing store and older than `minFileAgeMillis`. |
| **S3ContentStore** | `com.assa.alfresco.s3.S3ContentStore` | S3 backing store. Reads, writes, and deletes objects in the bucket. Uses AWS SDK v2. |
| **S3ContentWriter** | `com.assa.alfresco.s3.S3ContentWriter` | Writes content: first to a local temp file, then uploads to S3 when the stream closes. |
| **S3ContentReader** | `com.assa.alfresco.s3.S3ContentReader` | Reads content: gets metadata via `headObject()` and content via `getObject()`. |
| **S3WriteStreamListener** | `com.assa.alfresco.s3.S3WriteStreamListener` | Executes when the write stream closes. Uploads the temp file to S3 via `PutObjectRequest`. |

### Write flow

```
1. Alfresco requests a ContentWriter from CachingContentStore
2. CachingContentStore delegates to S3ContentStore.getWriterInternal()
3. S3ContentWriter creates a local temp file and returns a WritableByteChannel
4. Alfresco writes content to the channel
5. On stream close → S3WriteStreamListener.contentStreamClosed()
6. putObject() uploads the temp file to S3
7. The temp file is deleted
8. CachingContentStore saves a copy to the local cache (cacheOnInbound=true)
```

### Read flow

```
1. Alfresco requests a ContentReader from CachingContentStore
2. If content is in local cache → serves from there (cache hit)
3. If not in cache → CachingContentStore requests reader from S3ContentStore
4. S3ContentReader gets metadata (headObject) and content (getObject)
5. Content is copied to local cache for future reads
```

### Delete flow

```
1. User permanently deletes a node (trash → delete)
2. Alfresco deletes metadata from DB
3. EagerContentStoreCleaner (post-commit) executes delete on contentStoresToClean
4. CachingContentStore.delete() → removes from local cache
5. S3ContentStore.delete() → removes the object from the S3 bucket
```

## Configuration

### Required properties

| Property | Description | Example |
|---|---|---|
| `s3.accessKey` | S3 access key (leave empty for IAM roles) | `minioadmin` |
| `s3.secretKey` | S3 secret key (leave empty for IAM roles) | `minioadmin` |
| `s3.bucketName` | S3 bucket name | `alfresco` |
| `s3.regionName` | AWS region or identifier | `us-east-1` |
| `s3.endpoint` | S3 endpoint URL (empty = AWS). Must include http:// or https:// | `http://minio:9000` |
| `s3.rootDirectory` | Prefix within the bucket | `/alfresco/contentstore` |

### Authentication

The connector supports two authentication modes:

1. **Explicit credentials**: Set `s3.accessKey` and `s3.secretKey`. Works with all S3-compatible providers (MinIO, Wasabi, DigitalOcean Spaces, etc.)
2. **DefaultCredentialsProvider**: Leave `s3.accessKey` and `s3.secretKey` empty. The AWS SDK will use its default credential chain, which supports:
   - IAM roles on EC2/EKS instances
   - Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
   - AWS CLI profiles (`~/.aws/credentials`)
   - Web identity tokens (IRSA on EKS)

### Connection properties

| Property | Default | Description |
|---|---|---|
| `s3.pathStyleAccess` | `true` | Path-style access (true for MinIO and most S3-compatible, false for AWS S3) |
| `s3.client.connectionTimeout` | `50000` | Connection timeout in ms |
| `s3.client.connectionTTL` | `60000` | Connection TTL in ms |
| `s3.client.maxErrorRetry` | `5` | Max retries on errors |

### Provider-specific configuration

| Provider | `s3.endpoint` | `s3.pathStyleAccess` | `s3.regionName` | Credentials |
|---|---|---|---|---|
| **AWS S3** | empty | `false` | real region (e.g. `eu-west-1`) | explicit or IAM roles |
| **MinIO** | `http://minio:9000` | `true` | any value (e.g. `us-east-1`) | explicit |
| **Wasabi** | `https://s3.wasabisys.com` | `true` | real region | explicit |
| **DigitalOcean Spaces** | `https://<region>.digitaloceanspaces.com` | `false` | real region | explicit |

### Cache properties

| Property | Default | Description |
|---|---|---|
| `s3.content.caching.cacheOnInbound` | `true` | Cache content on write (avoids cache miss on first read) |
| `s3.content.caching.minFileAgeMillis` | `60000` | Min age of cached file before it can be cleaned |
| `s3.content.caching.maxDeleteWatchCount` | `1` | Times a cached file deletion is attempted |
| `s3.content.caching.contentCleanup.cronExpression` | `0 0 3 * * ?` | Cache cleanup job cron (default: daily 3AM) |
| `s3.quota.maxUsageMB` | `4096` | Max local cache usage in MB |
| `s3.quota.maxFileSizeMB` | `0` | Max file size in cache (0 = no limit) |
| `s3.content.cache.cachedcontent` | `/tmp/cachedcontent` | Local cache directory |

### Cleanup properties

| Property | Default | Description |
|---|---|---|
| `system.content.eagerOrphanCleanup` | `true` | Delete orphan content immediately on transaction commit |
| `system.content.orphanProtectDays` | `1` | Days of protection before deleting orphan content |

## Integration into an ACS project

### AMP installation

1. Build the connector:
   ```bash
   mvn clean package -DskipTests
   ```
   Generates `target/s3-open-connector-1.0-SNAPSHOT.amp`

2. Install the AMP into the Alfresco WAR:
   ```bash
   java -jar alfresco-mmt.jar install s3-open-connector-1.0-SNAPSHOT.amp alfresco.war -force
   ```

3. Configure `s3.*` properties in `alfresco-global.properties`

### Docker

The connector includes a Dockerfile that builds an ACS image with the AMP installed. Use `./run.sh build_start` to start the development environment with MinIO.

## Dependencies included in the AMP

The AMP packages the following libraries in `/lib`:

- AWS SDK v2 S3 (`software.amazon.awssdk:s3:2.42.30`)
- AWS SDK v2 URL Connection Client (synchronous HTTP client, no Netty or Apache HttpClient)
- AWS SDK v2 transitive dependencies

**Excluded** (already provided by Alfresco, avoids classpath conflicts):
- `slf4j-api`, `commons-logging`, `commons-codec`
- `io.netty:*`, `org.apache.httpcomponents:*`

## Technologies

- **Alfresco Content Services 26.1.0 Community**
- **Alfresco SDK 4.15.0**
- **AWS SDK v2** (2.42.30) with `url-connection-client`
- **Java 21**
- **Spring Framework** (provided by Alfresco)
- **MinIO** (S3-compatible, for development)

## Commands

| Command | Description |
|---|---|
| `./run.sh build_start` | Build + create Docker image + start environment |
| `./run.sh stop` | Stop containers |
| `./run.sh purge` | Stop + remove volumes |
| `./run.sh tail` | View container logs |
| `./run.sh reload_acs` | Rebuild module + restart ACS |
| `mvn clean package` | Compile and generate AMP |
| `mvn verify` | Run integration tests |
