@echo off
setlocal
java scripts\Verify.java %*
exit /b %ERRORLEVEL%
