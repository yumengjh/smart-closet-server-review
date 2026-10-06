package com.smartcloset.processing;
import com.smartcloset.ClosetProperties;
import java.nio.file.*;
import java.time.*;
import org.flywaydb.core.api.callback.*;
import org.springframework.stereotype.Component;
/** Runs before migrations and before either scheduler can own the database. */
@Component
public class ProcessingMigrationBackup extends BaseCallback {
 private final Path storage;
 @org.springframework.beans.factory.annotation.Autowired public ProcessingMigrationBackup(ClosetProperties properties){this(properties.storageDir());}
 public ProcessingMigrationBackup(Path storage){this.storage=storage;}
 @Override public boolean supports(Event event,Context context){return event==Event.BEFORE_MIGRATE;}
 @Override public void handle(Event event,Context context){try{
  var connection=context.getConnection();boolean hasHistory=false;
  try(var rs=connection.getMetaData().getTables(null,null,"flyway_schema_history",null)){hasHistory=rs.next();}
  if(!hasHistory)return;
  try(var query=connection.createStatement();var rs=query.executeQuery("SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\"='11' AND \"success\"=TRUE")){rs.next();if(rs.getInt(1)>0)return;}
  var root=storage.toAbsolutePath().normalize();var backup=root.resolve("backups").resolve("before-v11-"+Instant.now().toString().replace(':','-'));Files.createDirectories(backup);
  try(var stmt=connection.createStatement()){stmt.execute("BACKUP TO '"+backup.resolve("database.zip").toString().replace("'","''")+"'");}
  try(var paths=Files.walk(root)){for(var path:paths.filter(Files::isRegularFile).toList()){var rel=root.relativize(path);if(rel.startsWith("backups")||rel.startsWith("db")||path.getFileName().toString().startsWith(".pending")||path.getFileName().toString().endsWith(".lock"))continue;var target=backup.resolve("assets").resolve(rel);Files.createDirectories(target.getParent());Files.copy(path,target,StandardCopyOption.COPY_ATTRIBUTES);}}
  Files.writeString(backup.resolve("README.txt"),"V11 migration snapshot. database.zip is an H2 consistent backup; assets retain original relative paths. Stop server before restore.\n");
 }catch(Exception e){throw new IllegalStateException("V11迁移前备份失败；未继续迁移",e);}}
}
