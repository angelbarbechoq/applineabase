@echo off
REM Abre la consola de H2 para inspeccionar/editar la base de datos manualmente.
REM Si la aplicacion esta corriendo, deja la app abierta y agrega ;AUTO_SERVER=TRUE
REM al final del campo "JDBC URL" en la ventana que se abre.
REM
REM En la ventana de conexion completar:
REM   JDBC URL:  jdbc:h2:file:C:/LineaBaseX/data/lineabase
REM   Usuario:   (dejar vacio, la app no define spring.datasource.username)
REM   Password:  (dejar vacio, la app no define spring.datasource.password)
REM
REM Confirmado leyendo conn.getMetaData().getUserName() desde un bean real de la
REM app: devuelve cadena vacia. Spring Boot arma el DataSource con esos defaults
REM porque application.properties nunca configura username/password explicitos.
REM NO forzar spring.datasource.username=sa en application.properties -- eso ya
REM se probo y rompe la conexion real de la app (el usuario real no es "sa").

java -cp "%USERPROFILE%\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar" org.h2.tools.Console
