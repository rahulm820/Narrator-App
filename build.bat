@echo off
echo ===================================
echo Killing adb and Gradle processes...
echo ===================================
taskkill /F /IM adb.exe >nul 2>&1
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM gradlew.bat >nul 2>&1

echo.
echo ===================================
echo Cleaning Gradle caches...
echo ===================================
rmdir /s /q ".gradle" >nul 2>&1
rmdir /s /q "app\build" >nul 2>&1
rmdir /s /q "build" >nul 2>&1

echo.
echo ===================================
echo Forcing Gradle daemon stop...
echo ===================================
call gradlew --stop

echo.
echo ===================================
echo Building fresh APK...
echo ===================================
call gradlew clean assembleDebug --no-daemon --warning-mode all

echo.
echo ===================================
echo If build succeeded, install APK on device...
echo ===================================
call gradlew installDebug --no-daemon

pause
