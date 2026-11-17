-- In-app updates (approved 2026-11-17). CI publishes version.json + APK; administration
-- imports a build as DRAFT, chooses its policy and publishes it. Apps ask the server.
CREATE TABLE app_releases (
    id char(36) PRIMARY KEY,
    app varchar(16) NOT NULL,
    platform varchar(16) NOT NULL,
    version_name varchar(40) NOT NULL,
    build_number integer NOT NULL,
    download_url varchar(500) NOT NULL,
    sha256 char(64) NOT NULL,
    size_bytes bigint NOT NULL,
    notes_ar varchar(2000),
    source_commit varchar(64),
    status varchar(16) NOT NULL,
    update_mode varchar(16) NOT NULL,
    required_after timestamp(6),
    created_at timestamp(6) NOT NULL,
    created_by char(36) NOT NULL REFERENCES users(id),
    published_at timestamp(6),
    CONSTRAINT ck_app_releases_app CHECK (app IN ('ADMIN','MEMBER')),
    CONSTRAINT ck_app_releases_platform CHECK (platform IN ('ANDROID')),
    CONSTRAINT ck_app_releases_build CHECK (build_number > 0),
    CONSTRAINT ck_app_releases_sha CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_app_releases_size CHECK (size_bytes > 0),
    CONSTRAINT ck_app_releases_status CHECK (status IN ('DRAFT','PUBLISHED','WITHDRAWN')),
    CONSTRAINT ck_app_releases_mode CHECK (update_mode IN ('OPTIONAL','REQUIRED')),
    CONSTRAINT uq_app_releases_build UNIQUE (app, platform, build_number)
);

CREATE TABLE app_update_settings (
    app varchar(16) NOT NULL,
    platform varchar(16) NOT NULL,
    min_supported_build integer NOT NULL DEFAULT 0,
    updated_at timestamp(6) NOT NULL,
    updated_by char(36) REFERENCES users(id),
    PRIMARY KEY (app, platform),
    CONSTRAINT ck_app_update_settings_min CHECK (min_supported_build >= 0)
);

CREATE TABLE app_release_events (
    id char(36) PRIMARY KEY,
    release_id char(36) REFERENCES app_releases(id),
    app varchar(16) NOT NULL,
    actor_id char(36) NOT NULL REFERENCES users(id),
    action varchar(40) NOT NULL,
    created_at timestamp(6) NOT NULL
);

INSERT INTO app_update_settings(app, platform, min_supported_build, updated_at)
VALUES ('ADMIN','ANDROID',0,CURRENT_TIMESTAMP), ('MEMBER','ANDROID',0,CURRENT_TIMESTAMP);

-- Administration-only, never delegable; SUPER_ADMIN is always allowed.
INSERT INTO permissions(id,"key",name_ar,description_ar,level,delegable_to_employee) VALUES
 (4,'app.release.manage','إدارة إصدارات التطبيقات',
  'استيراد إصدارات التطبيقات ونشرها وتحديد إن كان التحديث إجباريًا أو اختياريًا.','ADMIN',FALSE);
