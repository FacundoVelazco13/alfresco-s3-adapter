# AGENTS.md

Información para agentes de AI sobre este repositorio.

## Descripción

Conector de Content Store S3 para Alfresco Content Services 26.1 Community. Soporta AWS S3 (incluyendo IAM roles via DefaultCredentialsProvider), MinIO, y cualquier almacenamiento S3-compatible. Genera un módulo AMP (Alfresco Module Package) que reemplaza el almacenamiento de contenido por defecto (filesystem local) por un bucket S3-compatible, con una capa de cache local para rendimiento.

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
│   │   │   ├── S3ContentStore.java         # Backing store S3 (AWS SDK v2)
│   │   │   ├── S3ContentWriter.java         # Escritura via temp file + PutObjectRequest
│   │   │   ├── S3ContentReader.java         # Lectura via headObject/getObject
│   │   │   └── S3WriteStreamListener.java   # Upload al cerrar stream
│   │   └── resources/alfresco/module/s3-open-connector/
│   │       ├── alfresco-global.properties   # Props s3.* + eagerOrphanCleanup
│   │       ├── context/
│   │       │   └── s3-context.xml           # Spring beans: CachingContentStore + S3ContentStore
│   │       ├── log4j2.properties
│   │       ├── module-context.xml           # Import a s3-context.xml
│   │       └── module.properties
│   └── test/java/com/assa/alfresco/s3/
│       ├── S3ContentStoreIT.java
│       └── S3CachingContentStoreIT.java
├── README.md
└── AGENTS.md
```

## Arquitectura interna

### Stack de Content Store

```
fileContentStore (alias) → CachingContentStore → S3ContentStore → S3 Bucket
                                  │
                           ContentCacheImpl (cache local en disco)
                                  │
                           StandardQuotaStrategy (control de cuota)
                                  │
                           CachedContentCleaner (limpieza periódica)
```

### Beans Spring (s3-context.xml)

| Bean ID | Clase | Rol |
|---|---|---|
| `assa.s3CachingContentStore` | `CachingContentStore` | Store principal con cache |
| `assa.s3ContentStore` | `S3ContentStore` | Backing store S3 |
| `assa.s3ContentCache` | `ContentCacheImpl` | Cache local en disco |
| `assa.s3ContentStoreCache` | factory bean | Cache Infinispan |
| `assa.s3QuotaManager` | `StandardQuotaStrategy` | Control de cuota del cache |
| `assa.s3CachedContentCleaner` | `CachedContentCleaner` | Limpieza de cache |
| `contentStoresToClean` | `ArrayList` | Stores donde el EagerContentStoreCleaner borra contenido huérfano |
| `fileContentStore` | alias | Apunta a `assa.s3CachingContentStore` |

### Flujo de datos

**Escritura**: ACS → CachingContentStore → S3ContentWriter (temp file) → S3WriteStreamListener (putObject al cerrar stream) → S3 bucket. El CachingContentStore guarda copia en cache local si `cacheOnInbound=true`.

**Lectura**: ACS → CachingContentStore → si está en cache local → sirve de ahí (cache hit). Si no → S3ContentReader (headObject + getObject) → copia a cache → sirve.

**Eliminación**: ACS elimina nodo → DB borra metadata → EagerContentStoreCleaner.afterCommit() → recorre `contentStoresToClean` → CachingContentStore.delete() (borra del cache) + S3ContentStore.delete() (borra de S3).

### Decisiones de diseño

1. **AWS SDK v2 con url-connection-client**: Se usa el HTTP client más simple (basado en `HttpURLConnection`) para evitar conflictos de classpath con Netty y Apache HttpClient que ya están en el classpath de Alfresco. Esto reduce el AMP de ~46 JARs a ~25 JARs sin conflictos.

2. **PutObjectRequest síncrono**: Se usa `s3Client.putObject(request, file.toPath())` en vez de `S3TransferManager` porque el TransferManager requiere un `S3AsyncClient` (no `S3Client`), lo cual agrega complejidad y dependencias adicionales. El put síncrono funciona correctamente incluso para archivos de 20MB+.

3. **EagerContentStoreCleaner con eagerOrphanCleanup=true**: Borra contenido huérfano de S3 inmediatamente al eliminar un nodo. Esto significa que NO se hace backup al `deletedContentStore` antes de borrar (Alfresco no llama listeners en eager mode por diseño). La "papelera" de Content App es el mecanismo de seguridad para recuperación.

4. **contentStoresToClean incluye solo el CachingContentStore**: La lista incluye únicamente `assa.s3CachingContentStore` porque `CachingContentStore.delete()` ya delega internamente al backing store (`S3ContentStore.delete()`). Incluir ambos stores causaría una doble llamada a `deleteObject` en S3 por cada contenido huérfano eliminado.

5. **Alias en vez de parent bean**: Se usa `<alias name="assa.s3CachingContentStore" alias="fileContentStore"/>` en vez de `<bean id="fileContentStore" parent="..."/>` para garantizar que ambos nombres referencien la misma instancia (el parent crearía una instancia diferente con doble init).

6. **pathStyleAccess=true por defecto**: MinIO requiere path-style access. AWS S3 usa virtual-hosted style por defecto. La propiedad es configurable.

7. **Assert.hasText en vez de Assert.notNull**: La validación de properties usa `hasText` para rechazar strings vacíos (no solo null), ya que las properties del módulo AMP tienen valores vacíos por defecto y se sobreescriben desde el `alfresco-global.properties` del deployment.

8. **Validación de endpoint con esquema**: Si `s3.endpoint` no está vacío, se valida que incluya esquema `http://` o `https://`. Esto previene errores genéricos de `URI.create()` cuando se proporciona un endpoint sin esquema (ej: `s3.amazonaws.com` en vez de `https://s3.amazonaws.com`).

9. **DefaultCredentialsProvider cuando no hay credenciales explícitas**: Si `s3.accessKey` y `s3.secretKey` están vacíos, el conector usa `DefaultCredentialsProvider` de AWS SDK, que soporta IAM roles en EC2/EKS, variables de entorno, AWS profiles, y web identity tokens.

### Propiedades de configuración

Prefijo `s3.` para todas las propiedades del conector. Las propiedades de sistema de Alfresco (`system.content.*`) controlan el comportamiento de cleanup.

Ver `alfresco-global.properties` del módulo para la lista completa con valores por defecto.

### Autenticación S3

El conector soporta dos modos de autenticación:

1. **Credenciales explícitas**: Setear `s3.accessKey` y `s3.secretKey`. Funciona con cualquier proveedor S3-compatible.
2. **DefaultCredentialsProvider**: Dejar `s3.accessKey` y `s3.secretKey` vacíos. AWS SDK usa su cadena de credenciales por defecto: IAM roles en EC2/EKS, env vars (`AWS_ACCESS_KEY_ID`), AWS CLI profiles, web identity tokens.

### Configuración por proveedor

| Proveedor | `s3.endpoint` | `s3.pathStyleAccess` | `s3.regionName` | Credenciales |
|---|---|---|---|---|
| **AWS S3** | vacío | `false` | región real (ej: `eu-west-1`) | explícitas o IAM roles |
| **MinIO** | `http://minio:9000` | `true` | cualquier valor | explícitas |
| **Wasabi** | `https://s3.wasabisys.com` | `true` | región real | explícitas |
| **DigitalOcean Spaces** | `https://<region>.digitaloceanspaces.com` | `false` | región real | explícitas |

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
- **Cualquier almacenamiento S3-compatible** (Wasabi, DigitalOcean Spaces, Ceph, etc.)

### Limitaciones conocidas

1. **Sin retry automático**: Si S3 cae durante una escritura, la operación falla inmediatamente. Alfresco hace rollback de la transacción. Cuando S3 vuelve, las operaciones subsiguientes funcionan normalmente.

2. **Eager cleanup sin backup**: `eagerOrphanCleanup=true` no llama `DeletedContentBackupCleanerListener` (por diseño de Alfresco). El contenido eliminado se borra de S3 sin backup intermedio. La papelera de Content App es el mecanismo de recuperación.

3. **Lecturas cacheadas durante caída de S3**: Si S3 cae, las lecturas de contenido que está en el cache local funcionan normalmente. Las lecturas de contenido no cacheado fallan.

4. **S3Client no es thread-safe para escrituras**: El `S3Client` de SDK v2 es thread-safe, pero el `S3ContentWriter` usa un temp file por operación, lo cual es seguro en el modelo de transacciones de Alfresco (cada thread tiene su propio writer).
