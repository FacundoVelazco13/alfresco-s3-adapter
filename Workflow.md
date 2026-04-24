Perfecto, vamos archivo por archivo entendiendo qué hace cada uno y por qué.
---
1. S3ContentStore.java — El corazón del conector
Es la clase principal. Extiende AbstractContentStore de Alfresco, que es la base para cualquier content store custom. Alfresco llama a esta clase para leer, escribir y eliminar contenido.

Interfaces que implementa : 

| Interface | Para qué |
|---|---|
| ApplicationContextAware | Recibe el contexto Spring (necesario para publicar el evento ContentStoreCreatedEvent) |
| ApplicationListener<ApplicationEvent> | Escucha ContextRefreshedEvent para publicar ese evento cuando arranca |
| InitializingBean | afterPropertiesSet() — valida que las props obligatorias estén antes de crear el S3Client |
| DisposableBean | destroy() — cierra el S3Client cuando el contexto Spring se apaga |

Propiedades (inyectadas desde s3-context.xml): 

Todas las s3.* del alfresco-global.properties se mapean a setters de esta clase:
- accessKey, secretKey → credenciales
- bucketName → nombre del bucket
- regionName → región
- endpoint → URL custom (MinIO, Wasabi, etc.). Vacío = AWS
- rootDirectory → prefijo dentro del bucket (se le saca el / inicial porque S3 no usa / absoluto en keys)
- pathStyleAccess → true para MinIO, false para AWS
- connectionTimeout, connectionTTL, maxErrorRetry → configuración del HTTP client

Ciclo de vida: 

Spring inyecta props → afterPropertiesSet() valida → init() crea el S3Client → el store funciona
                                                                                        ↓
                                                                     destroy() cierra el S3Client al apagar

afterPropertiesSet() (línea 228): Se ejecuta antes de init(). Valida:
- bucketName no vacío (Assert.hasText, no Assert.notNull porque las props del AMP vienen vacías por defecto)
- regionName no vacío
- rootDirectory no vacío
- Valores numéricos >= 0
- Si endpoint no está vacío, valida que tenga esquema http:// o https://.

init() (línea 91): Se ejecuta después. Construye el S3Client:
1. Si hay accessKey/secretKey → usa StaticCredentialsProvider (credenciales explícitas)
2. Si no → usa DefaultCredentialsProvider (IAM roles, env vars, AWS profiles)
3. Configura pathStyleAccess y chunkedEncodingEnabled=false (este último es para compatibilidad con MinIO)
4. Usa UrlConnectionHttpClient (el HTTP client más simple, basado en HttpURLConnection de Java, para evitar conflictos de classpath con Netty/Apache HttpClient que ya están en Alfresco)
5. Si hay endpoint → lo usa como endpointOverride (para MinIO, Wasabi, etc.). Si no → va al endpoint default de AWS
6. Loguea info al final
Métodos clave
getReader(contentUrl) (línea 82): Alfresco llama esto cuando necesita leer contenido. Recibe un contentUrl como store://2026/4/23/10/30/uuid.bin, lo convierte a S3 key (alfresco/contentstore/2026/4/23/10/30/uuid.bin), y devuelve un S3ContentReader.
getWriterInternal(existingContentReader, newContentUrl) (línea 164): Alfresco llama esto para escribir. Si no viene un contentUrl, genera uno nuevo con createNewUrl(). Convierte a S3 key y devuelve un S3ContentWriter.
createNewUrl() (línea 173): Genera URLs tipo store://2026/4/23/10/30/uuid.bin. Este es el formato que usa FileContentStore nativo de Alfresco — año/mes/día/hora/minuto/guid. Lo usamos igual para que el content URL sea compatible con el protocolo store:// que Alfresco espera.
makeS3Key(contentUrl) (línea 192): Convierte un content URL en S3 key. Parsea el URL en protocolo + path relativo, valida que el protocolo sea store, y le prependa rootDirectory. Ejemplo:
- Input: store://2026/4/23/10/30/abc.bin
- Output: alfresco/contentstore/2026/4/23/10/30/abc.bin
delete(contentUrl) (línea 202): Convierte el content URL a S3 key y llama s3Client.deleteObject(). Retorna true si anduvo, false si falló (sin tirar excepción, porque Alfresco no quiere que la eliminación de contenido huérfano tire la app). Log a debug.
onApplicationEvent() + publishEvent() (líneas 215-224): Cuando el contexto Spring arranca, publica un ContentStoreCreatedEvent. Esto es necesario para que Alfresco registre el store internamente.
---
2. S3ContentWriter.java — Escritura de contenido
Extiende AbstractContentWriter de Alfresco. La estrategia de escritura es temp file + upload al cerrar.
Cómo funciona la escritura
1. Alfresco pide un writer → S3ContentStore.getWriterInternal() crea un S3ContentWriter
2. El constructor (línea 28) registra un listener: addListener(new S3WriteStreamListener(this))
3. Alfresco escribe contenido → getDirectWritableChannel() (línea 43) crea un temp file y devuelve un channel hacia ese archivo
4. Cuando Alfresco termina de escribir y cierra el stream → el listener se dispara → sube a S3
Por qué temp file y no stream directo
No se puede hacer streaming directo a S3 porque:
- AWS SDK v2 necesita saber el contentLength antes de subir
- Alfresco escribe contenido de tamaño variable
- El temp file da el tamaño exacto antes del upload
getDirectWritableChannel() (línea 43)
1. Genera un UUID
2. Crea un temp file con TempFileProvider.createTempFile() (usa el directorio temporal de Alfresco)
3. Abre un FileOutputStream y lo wrappea en un WritableByteChannel con Channels.newChannel()
4. Retorna ese channel — Alfresco escribirá ahí
createReader() (línea 38)
Alfresco llama esto para obtener un reader del mismo contenido que se acaba de escribir. Retorna un S3ContentReader con el mismo key/bucket/client.
Getters
getClient(), getBucketName(), getKey(), getTempFile() — los usa S3WriteStreamListener para hacer el upload.
---
3. S3ContentReader.java — Lectura de contenido
Extiende AbstractContentReader. Usa lazy initialization para no hacer llamadas S3 hasta que sea necesario.
Lazy init
Tiene dos estados lazy:
- s3ResponseStream (el stream del objeto S3) → solo se inicializa cuando alguien pide leer el contenido
- headObjectResponse (metadata del objeto) → solo se inicializa cuando alguien pregunta exists(), getSize(), o getLastModified()
Esto es importante porque Alfresco llama exists() mucho antes de leer, y no queremos descargar el objeto entero solo para saber si existe.
exists() (línea 105)
Hace un headObject() (HTTP HEAD, sin body). Si retorna metadata → existe. Si tira NoSuchKeyException → no existe. Retorna headObjectResponse != null.
getSize() (línea 130) y getLastModified() (línea 116)
Usan la metadata del headObject(). Si no existe o falla, retornan 0L.
getDirectReadableChannel() (línea 75)
1. Llama lazyInitFileObject() → hace getObject() (HTTP GET, descarga el objeto)
2. Verifica exists() (ya debería tener la metadata del paso anterior si se llamó antes)
3. Agrega un listener que cierra el stream S3 cuando Alfresco termina de leer
4. Retorna Channels.newChannel(s3ResponseStream) — un channel de lectura sobre el stream de S3
getObject() (línea 143)
Hace s3Client.getObject(request) → retorna un ResponseInputStream<<GetObjectResponse>. Si falla, loguea error y retorna null.
getHeadObject() (línea 159)
Hace s3Client.headObject(request) → retorna HeadObjectResponse. Atrapa NoSuchKeyException (el objeto no existe) y retorna null.
---
4. S3WriteStreamListener.java — El upload real
Implementa ContentStreamListener de Alfresco. Se registra en el S3ContentWriter y se ejecuta cuando Alfresco cierra el stream de escritura.
contentStreamClosed() (línea 22)
1. Obtiene el temp file del writer
2. Si no existe → loguea warn y sale (no debería pasar pero es safety)
3. Obtiene el tamaño del archivo y se lo setea al writer
4. Construye un PutObjectRequest con bucket, key, y contentLength
5. Llama writer.getClient().putObject(putRequest, file.toPath()) → sube el archivo a S3
6. En el finally → borra el temp file (siempre, haya fallado o no)
Si el upload falla → tira ContentIOException. Esto hace que la transacción de Alfresco haga rollback.
Por qué PutObjectRequest síncrono
No usamos S3TransferManager porque:
- TransferManager requiere un S3AsyncClient (no S3Client)
- Agrega dependencias adicionales
- Para los tamaños típicos de documentos en Alfresco, el put síncrono funciona bien
---
5. s3-context.xml — Configuración Spring
Define todos los beans y sus relaciones. Es el archivo que "arma" la arquitectura del conector.
Bean por bean
assa.s3CachingContentStore (línea 7): El store principal. Es una instancia de CachingContentStore de Alfresco (clase nativa, no nuestra). Tiene:
- backingStore → apunta a nuestro assa.s3ContentStore
- cache → el cache local en disco
- cacheOnInbound=true → cuando se escribe, guarda copia en cache (evita cache miss en la primera lectura)
- quota → control de cuota del cache
assa.s3ContentCache (línea 16): Cache local en disco. Usa:
- memoryStore → Infinispan (cache en memoria para saber qué archivos están en disco)
- cacheRoot → directorio donde se guardan los archivos cacheados (/tmp/cachedcontent)
assa.s3ContentStoreCache (línea 21): Factory bean que crea el cache Infinispan usando el cacheFactory que ya existe en Alfresco.
assa.s3QuotaManager (línea 25): Control de cuota. Cuando el cache supera maxUsageMB, dispara la limpieza. Tiene init-method="init" y destroy-method="shutdown".
assa.s3CleanerJobDetail (línea 35): Job de Quartz que ejecuta la limpieza del cache. Apunta al CachedContentCleanupJob de Alfresco (clase nativa).
assa.s3CachedContentCleaner (línea 46): El cleaner que realmente borra archivos del cache. Borra los que:
- Ya fueron subidos al backing store
- Tienen más de minFileAgeMillis (60 segundos por defecto)
- Fueron intentados eliminar más de maxDeleteWatchCount veces
assa.s3CleanerTrigger (línea 55): Trigger de Quartz con cron expression. Por defecto corre a las 3AM todos los días.
assa.s3ContentStore (línea 68): Nuestro S3ContentStore. Recibe todas las props del alfresco-global.properties. Tiene init-method="init" (crea el S3Client) y destroy-method="destroy" (lo cierra).
contentStoresToClean (línea 84): Lista de stores donde el EagerContentStoreCleaner de Alfresco elimina contenido huérfano. Solo incluye assa.s3CachingContentStore porque CachingContentStore.delete() ya delega al backing store internamente.
alias (línea 92): <alias name="assa.s3CachingContentStore" alias="fileContentStore"/> — esto hace que cuando Alfresco busca el bean fileContentStore (que es el nombre que usa por defecto), lo redirige a nuestro CachingContentStore. Es como "interceptar" el store por defecto y reemplazarlo por el nuestro.
---
6. module-context.xml — Punto de entrada del AMP
Archivo mínimo que importa s3-context.xml. Alfresco busca automáticamente module-context.xml dentro de alfresco/module/${artifactId}/ al instalar un AMP. Es el "bootstrap" del módulo.
---
7. alfresco-global.properties (del módulo) — Valores por defecto
Define todas las props del conector con valores vacíos o por defecto. Estas se sobreescriben desde el alfresco-global.properties del deployment. Las vacías (s3.accessKey=, s3.bucketName=) obligan al admin a configurarlas, porque afterPropertiesSet() las valida.
---
Flujo completo de un documento
Upload de un documento
1. Usuario sube un PDF vía Content App
2. ACS crea un nodo en la DB
3. ACS pide un ContentWriter al bean "fileContentStore"
   → el alias redirige a "assa.s3CachingContentStore"
4. CachingContentStore delega a S3ContentStore.getWriterInternal()
5. S3ContentStore genera URL: store://2026/4/23/10/30/uuid.bin
6. S3ContentWriter crea temp file local + registra S3WriteStreamListener
7. ACS escribe el PDF en el WritableByteChannel del temp file
8. ACS cierra el stream → S3WriteStreamListener.contentStreamClosed()
9. putObject() sube el temp file a S3 (key: alfresco/contentstore/2026/4/23/10/30/uuid.bin)
10. Se borra el temp file
11. CachingContentStore guarda copia en cache local (cacheOnInbound=true)
12. ACS guarda el content URL en la DB del nodo
Descarga de un documento (primera vez, cache miss)
1. Usuario descarga el PDF
2. ACS pide un ContentReader al bean "fileContentStore"
   → CachingContentStore busca en cache local → NO está
3. CachingContentStore delega a S3ContentStore.getReader()
4. S3ContentReader: headObject() → metadata (tamaño, fecha)
5. S3ContentReader: getObject() → stream del objeto S3
6. CachingContentStore copia el contenido al cache local
7. ACS sirve el PDF al usuario
Descarga de un documento (segunda vez, cache hit)
1. Usuario descarga el PDF
2. ACS pide un ContentReader al bean "fileContentStore"
   → CachingContentStore busca en cache local → SÍ está
3. CachingContentStore sirve directamente del disco local
4. NO hay llamada a S3 (ni headObject ni getObject)
Eliminación de un documento
1. Usuario elimina nodo de la papelera (eliminación definitiva)
2. ACS borra metadata de la DB
3. Post-commit → EagerContentStoreCleaner.afterCommit()
4. Itera contentStoresToClean → solo assa.s3CachingContentStore
5. CachingContentStore.delete(url):
   a. Busca en cache → si está, lo borra del disco local
   b. Llama backingStore.delete() → S3ContentStore.delete()
   c. S3ContentStore: deleteObject() en S3
6. El objeto se borra del bucket
---