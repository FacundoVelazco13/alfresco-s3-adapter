# AGENTS.md

Información para agentes de AI sobre este repositorio.

## Descripción

Conector de Content Store S3/MinIO para Alfresco Content Services 26.1 Community. Genera un módulo AMP (Alfresco Module Package) que reemplaza el almacenamiento de contenido por defecto (filesystem local) por un bucket S3-compatible, con una capa de cache local para rendimiento.

## Contexto

Este proyecto nació como refactorización de un proyecto open source obsoleto (`alfresco-s3-adapter` de Redpill-Linpro) que usaba AWS SDK v1, Spring DTD, Log4j 1.x, y estaba atado a ACS 6.x. Se decidió crear un proyecto nuevo desde el archetype de Alfresco SDK 4.15 platform en vez de intentar migrar el existente, dado que el proyecto original tenía 6 submódulos innecesarios, parent POM inaccesible, y dependencias obsoletas.

## Estructura del proyecto

```
s3-open-connector/
├── pom.xml                    # Standalone (sin parent), SDK 4.15, ACS 26.1.0 BOM, AWS SDK v2 BOM
├── docker/
│   └── docker-compose.yml     # MinIO + PostgreSQL + ACS + Solr + ActiveMQ
├── run.sh                     # Scripts de Docker (build_start, stop, etc.)
├── src/
│   ├── main/
│   │   ├── assembly/
│   │   │   ├── amp.xml        # Assembly descriptor del AMP, con excludes de classpath
│   │   │   └── file-mapping.properties
│   │   ├── docker/
│   │   │   ├── Dockerfile     # Basado en alfresco-content-repository-community:26.1
│   │   │   ├── alfresco-global.properties
│   │   │   ├── dev-log4j2.properties
│   │   │   └── disable-webscript-caching-context.xml
│   │   ├── java/com/assa/alfresco/s3/
│   │   │   ├── MinIOContentStore.java      # Backing store S3 (AWS SDK v2)
│   │   │   ├── MinIOContentWriter.java      # Escritura via temp file + PutObjectRequest
│   │   │   ├── MinIOContentReader.java      # Lectura via headObject/getObject
│   │   │   └── MinIOWriteStreamListener.java # Upload al cerrar stream
│   │   └── resources/alfresco/module/s3-open-connector/
│   │       ├── alfresco-global.properties   # Props s3.* + eagerOrphanCleanup
│   │       ├── context/
│   │       │   └── minio-context.xml        # Spring beans: CachingContentStore + MinIOContentStore
│   │       ├── log4j2.properties
│   │       ├── module-context.xml           # Import a minio-context.xml
│   │       └── module.properties
│   └── test/java/com/assa/alfresco/s3/
│       ├── MinIOContentStoreIT.java
│       └── MinIOCachingContentStoreIT.java
├── README.md
└── AGENTS.md
```

## Arquitectura interna

### Stack de Content Store

```
fileContentStore (alias) → CachingContentStore → MinIOContentStore → MinIO/S3
                                  │
                           ContentCacheImpl (cache local en disco)
                                  │
                           StandardQuotaStrategy (control de cuota)
                                  │
                           CachedContentCleaner (limpieza periódica)
```

### Beans Spring (minio-context.xml)

| Bean ID | Clase | Rol |
|---|---|---|
| `assa.minioCachingContentStore` | `CachingContentStore` | Store principal con cache |
| `assa.minioContentStore` | `MinIOContentStore` | Backing store S3 |
| `assa.minioContentCache` | `ContentCacheImpl` | Cache local en disco |
| `assa.minioContentStoreCache` | factory bean | Cache Infinispan |
| `assa.minioQuotaManager` | `StandardQuotaStrategy` | Control de cuota del cache |
| `assa.minioCachedContentCleaner` | `CachedContentCleaner` | Limpieza de cache |
| `contentStoresToClean` | `ArrayList` | Stores donde el EagerContentStoreCleaner borra contenido huérfano |
| `fileContentStore` | alias | Apunta a `assa.minioCachingContentStore` |

### Flujo de datos

**Escritura**: ACS → CachingContentStore → MinIOContentWriter (temp file) → MinIOWriteStreamListener (putObject al cerrar stream) → S3 bucket. El CachingContentStore guarda copia en cache local si `cacheOnInbound=true`.

**Lectura**: ACS → CachingContentStore → si está en cache local → sirve de ahí (cache hit). Si no → MinIOContentReader (headObject + getObject) → copia a cache → sirve.

**Eliminación**: ACS elimina nodo → DB borra metadata → EagerContentStoreCleaner.afterCommit() → recorre `contentStoresToClean` → CachingContentStore.delete() (borra del cache) + MinIOContentStore.delete() (borra de S3).

### Decisiones de diseño

1. **AWS SDK v2 con url-connection-client**: Se usa el HTTP client más simple (basado en `HttpURLConnection`) para evitar conflictos de classpath con Netty y Apache HttpClient que ya están en el classpath de Alfresco. Esto reduce el AMP de ~46 JARs a ~25 JARs sin conflictos.

2. **PutObjectRequest síncrono**: Se usa `s3Client.putObject(request, file.toPath())` en vez de `S3TransferManager` porque el TransferManager requiere un `S3AsyncClient` (no `S3Client`), lo cual agrega complejidad y dependencias adicionales. El put síncrono funciona correctamente incluso para archivos de 20MB+.

3. **EagerContentStoreCleaner con eagerOrphanCleanup=true**: Borra contenido huérfano de MinIO inmediatamente al eliminar un nodo. Esto significa que NO se hace backup al `deletedContentStore` antes de borrar (Alfresco no llama listeners en eager mode por diseño). La "papelera" de Content App es el mecanismo de seguridad para recuperación.

4. **contentStoresToClean incluye ambos stores**: La lista incluye `assa.minioCachingContentStore` y `assa.minioContentStore` para que el EagerContentStoreCleaner elimine tanto del cache local como del bucket S3.

5. **Alias en vez de parent bean**: Se usa `<alias name="assa.minioCachingContentStore" alias="fileContentStore"/>` en vez de `<bean id="fileContentStore" parent="..."/>` para garantizar que ambos nombres referencien la misma instancia (el parent crearía una instancia diferente con doble init).

6. **pathStyleAccess=true por defecto**: MinIO requiere path-style access. AWS S3 usa virtual-hosted style por defecto. La propiedad es configurable.

7. **Assert.hasText en vez de Assert.notNull**: La validación de properties usa `hasText` para rechazar strings vacíos (no solo null), ya que las properties del módulo AMP tienen valores vacíos por defecto y se sobreescriben desde el `alfresco-global.properties` del deployment.

### Propiedades de configuración

Prefijo `s3.` para todas las propiedades del conector. Las propiedades de sistema de Alfresco (`system.content.*`) controlan el comportamiento de cleanup.

Ver `alfresco-global.properties` del módulo para la lista completa con valores por defecto.

### Excludes del AMP assembly

El `amp.xml` excluye del `/lib` del AMP las siguientes librerías para evitar conflictos de classpath con Alfresco:
- `org.slf4j:slf4j-api`
- `commons-logging:commons-logging`
- `commons-codec:commons-codec`
- `io.netty:*`
- `org.apache.httpcomponents:*`

### Build y deployment

```bash
mvn clean package -DskipTests   # Genera .amp en target/
./run.sh build_start             # Levanta entorno Docker con MinIO
```

El AMP se instala en el WAR de Alfresco via el Dockerfile (MMT install). En desarrollo, el JAR se copia a `WEB-INF/lib/` y Alfresco detecta automáticamente el `module-context.xml` dentro del JAR.

### Testing

Tests de integración (`*IT.java`) requieren ACS corriendo con MinIO. Se ejecutan con `mvn verify` contra el contenedor Docker.

### Compatibilidad

- **ACS 26.1.0 Community** (probado)
- **Java 21**
- **MinIO** (probado con `minio/minio:latest`)
- **AWS S3** (compatible, set `s3.pathStyleAccess=false` y eliminar `s3.endpoint`)

### Limitaciones conocidas

1. **Sin retry automático**: Si MinIO cae durante una escritura, la operación falla inmediatamente. Alfresco hace rollback de la transacción. Cuando MinIO vuelve, las operaciones subsiguientes funcionan normalmente.

2. **Eager cleanup sin backup**: `eagerOrphanCleanup=true` no llama `DeletedContentBackupCleanerListener` (por diseño de Alfresco). El contenido eliminado se borra de MinIO sin backup intermedio. La papelera de Content App es el mecanismo de recuperación.

3. **Lecturas cacheadas durante caída de MinIO**: Si MinIO cae, las lecturas de contenido que está en el cache local funcionan normalmente. Las lecturas de contenido no cacheado fallan.

4. **S3Client no es thread-safe para escrituras**: El `S3Client` de SDK v2 es thread-safe, pero el `MinIOContentWriter` usa un temp file por operación, lo cual es seguro en el modelo de transacciones de Alfresco (cada thread tiene su propio writer).
