# S3 Open Connector

Conector de Content Store S3/MinIO para Alfresco Content Services 26.1 Community. Reemplaza el almacenamiento de contenido por defecto (filesystem local) por un bucket S3-compatible, con una capa de cache local para mantener el rendimiento.

## Arquitectura

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
MinIOContentStore (backing store)
    │
    ▼
MinIO / S3 Bucket
    └── alfresco/contentstore/<año>/<mes>/<día>/<hora>/<min>/<guid>.bin
```

### Capas del conector

| Capa | Clase | Responsabilidad |
|---|---|---|
| **CachingContentStore** | `org.alfresco.repo.content.caching.CachingContentStore` | Cache local en disco. Primera capa de lectura/escritura. Delega al backing store cuando el contenido no está en cache. |
| **ContentCacheImpl** | `org.alfresco.repo.content.caching.ContentCacheImpl` | Implementación del cache: memoria (Infinispan) + disco. Gestiona el ciclo de vida de los archivos cacheados. |
| **StandardQuotaStrategy** | `org.alfresco.repo.content.caching.quota.StandardQuotaStrategy` | Control de cuota del cache. Cuando se supera `maxUsageMB`, limpia archivos viejos. |
| **CachedContentCleaner** | `org.alfresco.repo.content.caching.cleanup.CachedContentCleaner` | Job que limpia archivos del cache local que ya fueron subidos al backing store y superan `minFileAgeMillis`. |
| **MinIOContentStore** | `com.assa.alfresco.s3.MinIOContentStore` | Backing store S3. Lee, escribe y elimina objetos en el bucket. Usa AWS SDK v2. |
| **MinIOContentWriter** | `com.assa.alfresco.s3.MinIOContentWriter` | Escribe contenido: primero a un temp file local, luego sube a S3 al cerrar el stream. |
| **MinIOContentReader** | `com.assa.alfresco.s3.MinIOContentReader` | Lee contenido: obtiene metadata via `headObject()` y contenido via `getObject()`. |
| **MinIOWriteStreamListener** | `com.assa.alfresco.s3.MinIOWriteStreamListener` | Se ejecuta al cerrar el stream de escritura. Sube el temp file a S3 via `PutObjectRequest`. |

### Flujo de escritura

```
1. Alfresco solicita un ContentWriter al CachingContentStore
2. CachingContentStore delega a MinIOContentStore.getWriterInternal()
3. MinIOContentWriter crea un temp file local y retorna un WritableByteChannel
4. Alfresco escribe el contenido en el channel
5. Al cerrar el stream → MinIOWriteStreamListener.contentStreamClosed()
6. putObject() sube el temp file a S3
7. El temp file se elimina
8. CachingContentStore guarda una copia en el cache local (cacheOnInbound=true)
```

### Flujo de lectura

```
1. Alfresco solicita un ContentReader al CachingContentStore
2. Si el contenido está en cache local → sirve desde ahí (cache hit)
3. Si no está en cache → CachingContentStore solicita reader al MinIOContentStore
4. MinIOContentReader obtiene metadata (headObject) y contenido (getObject)
5. El contenido se copia al cache local para futuras lecturas
```

### Flujo de eliminación

```
1. Usuario elimina un nodo definitivamente (papelera → eliminar)
2. Alfresco borra el metadata de la DB
3. EagerContentStoreCleaner (post-commit) ejecuta delete en contentStoresToClean
4. CachingContentStore.delete() → elimina del cache local
5. MinIOContentStore.delete() → elimina el objeto del bucket S3
```

## Configuración

### Propiedades obligatorias

| Propiedad | Descripción | Ejemplo |
|---|---|---|
| `s3.accessKey` | Access key de MinIO/S3 | `minioadmin` |
| `s3.secretKey` | Secret key de MinIO/S3 | `minioadmin` |
| `s3.bucketName` | Nombre del bucket S3 | `alfresco` |
| `s3.regionName` | Región AWS o identificador | `us-east-1` |
| `s3.endpoint` | URL del endpoint S3 (vacío = AWS) | `http://minio:9000` |
| `s3.rootDirectory` | Prefijo dentro del bucket | `/alfresco/contentstore` |

### Propiedades de conexión

| Propiedad | Default | Descripción |
|---|---|---|
| `s3.pathStyleAccess` | `true` | Path-style access (requerido para MinIO). `false` para AWS S3 virtual-hosted style |
| `s3.client.connectionTimeout` | `50000` | Timeout de conexión en ms |
| `s3.client.connectionTTL` | `60000` | TTL de conexión en ms |
| `s3.client.maxErrorRetry` | `5` | Reintentos máximos ante errores |

### Propiedades de cache

| Propiedad | Default | Descripción |
|---|---|---|
| `s3.content.caching.cacheOnInbound` | `true` | Cachear contenido al escribir (evita cache miss en primera lectura) |
| `s3.content.caching.minFileAgeMillis` | `60000` | Edad mínima de archivo en cache antes de poder limpiarlo |
| `s3.content.caching.maxDeleteWatchCount` | `1` | Veces que se intenta eliminar un archivo cacheado |
| `s3.content.caching.contentCleanup.cronExpression` | `0 0 3 * * ?` | Cron del job de limpieza de cache (default: 3AM diario) |
| `s3.quota.maxUsageMB` | `4096` | Uso máximo del cache local en MB |
| `s3.quota.maxFileSizeMB` | `0` | Tamaño máximo de archivo en cache (0 = sin límite) |
| `s3.content.cache.cachedcontent` | `/tmp/cachedcontent` | Directorio del cache local |

### Propiedades de cleanup

| Propiedad | Default | Descripción |
|---|---|---|
| `system.content.eagerOrphanCleanup` | `true` | Eliminar contenido huérfano inmediatamente al hacer commit de la transacción |
| `system.content.orphanProtectDays` | `1` | Días de protección antes de eliminar contenido huérfano |

## Integración en un proyecto ACS

### Instalación del AMP

1. Compilar el conector:
   ```bash
   mvn clean package -DskipTests
   ```
   Genera `target/s3-open-connector-1.0-SNAPSHOT.amp`

2. Instalar el AMP en el WAR de Alfresco:
   ```bash
   java -jar alfresco-mmt.jar install s3-open-connector-1.0-SNAPSHOT.amp alfresco.war -force
   ```

3. Configurar las propiedades `s3.*` en `alfresco-global.properties`

### Docker

El conector incluye un Dockerfile que construye una imagen ACS con el AMP instalado. Usar `./run.sh build_start` para levantar el entorno de desarrollo con MinIO.

## Dependencias incluidas en el AMP

El AMP empaqueta las siguientes librerías en `/lib`:

- AWS SDK v2 S3 (`software.amazon.awssdk:s3:2.42.30`)
- AWS SDK v2 URL Connection Client (HTTP client síncrono, sin Netty ni Apache HttpClient)
- Dependencias transitivas del SDK v2

**Excluidas** (ya provistas por Alfresco, evitar conflictos de classpath):
- `slf4j-api`, `commons-logging`, `commons-codec`
- `io.netty:*`, `org.apache.httpcomponents:*`

## Tecnologías

- **Alfresco Content Services 26.1.0 Community**
- **Alfresco SDK 4.15.0**
- **AWS SDK v2** (2.42.30) con `url-connection-client`
- **Java 21**
- **Spring Framework** (provided by Alfresco)
- **MinIO** (S3-compatible)

## Comandos

| Comando | Descripción |
|---|---|
| `./run.sh build_start` | Build + crear imagen Docker + levantar entorno |
| `./run.sh stop` | Detener contenedores |
| `./run.sh purge` | Detener + eliminar volúmenes |
| `./run.sh tail` | Ver logs de contenedores |
| `./run.sh reload_acs` | Reconstruir módulo + reiniciar ACS |
| `mvn clean package` | Compilar y generar AMP |
| `mvn verify` | Ejecutar tests de integración |
