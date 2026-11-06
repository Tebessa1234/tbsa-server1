@echo off
setlocal
cd /d "%~dp0"
title Installer TBSA sur le stick TV
echo.
echo ==== Installation de TBSA sur un stick TV (via le reseau, ADB) ====
echo.
echo Avant de continuer, sur le stick :
echo  - activez les Options pour les developpeurs (Fire TV : Parametres, Ma Fire TV, A propos,
echo    cliquez 7 fois sur le nom de l'appareil ; Android TV : Parametres, A propos, 7 fois sur "Build")
echo  - activez "Debogage ADB" (Fire TV : aussi "Applications de sources inconnues")
echo  - notez l'adresse IP du stick (Parametres, Reseau)
echo.
if not exist TBSA-Serveur.apk goto noapk
set ADB=adb
where adb >nul 2>nul
if not errorlevel 1 goto haveadb
if exist platform-tools\adb.exe set ADB=platform-tools\adb.exe
if exist platform-tools\adb.exe goto haveadb
goto noadb

:haveadb
set /p IP=Adresse IP du stick (ex. 192.168.1.50) : 
"%ADB%" connect %IP%:5555
echo.
echo Si le stick demande "Autoriser le debogage", acceptez (cochez "Toujours").
pause
"%ADB%" install -r TBSA-Serveur.apk
if errorlevel 1 goto failed
echo.
echo ==============================================================
echo  TERMINE : l'application "TBSA" est installee sur le stick.
echo  Lancez-la depuis la liste de vos applications.
echo ==============================================================
pause
exit /b 0

:failed
echo.
echo ECHEC de l'installation. Verifiez l'adresse IP, le debogage ADB et l'autorisation affichee sur la TV.
pause
exit /b 1

:noapk
echo ERREUR : TBSA-Serveur.apk est introuvable dans ce dossier.
echo Obtenez-le avec LISEZMOI-STICK.txt puis placez-le ici, a cote de ce fichier.
pause
exit /b 1

:noadb
echo ERREUR : ADB est introuvable.
echo Telechargez "SDK Platform-Tools" pour Windows sur developer.android.com/tools/releases/platform-tools,
echo dezippez-le ici pour obtenir le dossier "platform-tools", puis relancez ce fichier.
echo (Sinon, utilisez la methode "Downloader" decrite dans LISEZMOI-STICK.txt : plus simple.)
pause
exit /b 1
