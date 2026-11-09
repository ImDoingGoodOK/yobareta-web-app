@echo off
chcp 65001 > nul
title Yobareta Local PWA Server
echo Starting Yobareta Local LAN Server on port 8080...
if exist "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe" (
    "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe" -Dfile.encoding=UTF-8 backend\LocalTestServer.java 8080
) else if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" -Dfile.encoding=UTF-8 backend\LocalTestServer.java 8080
) else (
    java -Dfile.encoding=UTF-8 backend\LocalTestServer.java 8080
)
pause
