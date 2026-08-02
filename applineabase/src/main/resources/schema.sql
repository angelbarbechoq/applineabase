-- Parches idempotentes de schema que Hibernate (ddl-auto=update) no puede aplicar solo.
-- Corre despues de que Hibernate crea/actualiza las tablas (ver
-- spring.jpa.defer-datasource-initialization=true en application.properties), asi que las
-- tablas ya existen para cualquier instalacion, nueva o vieja.

-- tipo_alarma quedo como ENUM nativo de H2 en instalaciones creadas antes de agregar
-- DISPOSITIVO_NO_DISPONIBLE a TipoAlarma; ddl-auto=update no ensancha un ENUM existente.
-- Convertir a VARCHAR es un no-op si ya es VARCHAR (instalaciones nuevas, ver
-- columnDefinition en AlarmaConfig/AlarmaEvento).
ALTER TABLE alarma_config ALTER COLUMN tipo_alarma VARCHAR(40);
ALTER TABLE alarma_evento ALTER COLUMN tipo_alarma VARCHAR(40);

-- Columna agregada junto con DISPOSITIVO_NO_DISPONIBLE; en instalaciones nuevas ya la crea
-- Hibernate, IF NOT EXISTS la deja sin efecto ahi.
ALTER TABLE alarma_evento ADD COLUMN IF NOT EXISTS urgente BOOLEAN NOT NULL DEFAULT FALSE;
