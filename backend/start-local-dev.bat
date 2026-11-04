@echo off
chcp 65001 >nul 2>&1
setlocal

rem F01/F03 shared local-dev launcher.
rem Supply all credentials through environment variables; this script never prints them.

if not defined AUDIT_DB_URL (
    echo Missing required environment variable: AUDIT_DB_URL
    exit /b 1
)
if not defined AUDIT_DB_USERNAME (
    echo Missing required environment variable: AUDIT_DB_USERNAME
    exit /b 1
)
if not defined AUDIT_DB_PASSWORD (
    echo Missing required environment variable: AUDIT_DB_PASSWORD
    exit /b 1
)
if not defined AUDIT_FILE_STORAGE_ROOT (
    echo Missing required environment variable: AUDIT_FILE_STORAGE_ROOT
    exit /b 1
)
if not defined AUDIT_REDIS_HOST set "AUDIT_REDIS_HOST=127.0.0.1"
if not defined AUDIT_REDIS_PORT set "AUDIT_REDIS_PORT=6379"

cd /d "%~dp0"

echo ==========================================
echo   Audit system - local-dev
echo ==========================================
echo Database, Redis and storage settings loaded from the environment.

call mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local-dev"
