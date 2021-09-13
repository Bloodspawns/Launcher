[Setup]
AppName=BlueLite Launcher
AppPublisher=BlueLite
UninstallDisplayName=BlueLite
AppVersion=${project.version}
AppSupportURL=https://runelite.net/
DefaultDirName={localappdata}\BlueLite

; ~30 mb for the repo the launcher downloads
ExtraDiskSpaceRequired=30000000
ArchitecturesAllowed=x64
PrivilegesRequired=lowest

WizardSmallImageFile=${basedir}/innosetup/runelite_small.bmp
SetupIconFile=${basedir}/runelite.ico
UninstallDisplayIcon={app}\BlueLite.exe

Compression=lzma2
SolidCompression=yes

OutputDir=${basedir}
OutputBaseFilename=BlueLiteSetup

[Tasks]
Name: DesktopIcon; Description: "Create a &desktop icon";

[Files]
Source: "${basedir}\native-win64\BlueLite.exe"; DestDir: "{app}"
Source: "${basedir}\native-win64\BlueLite.jar"; DestDir: "{app}"
Source: "${basedir}\native-win64\config.json"; DestDir: "{app}"
Source: "${basedir}\native-win64\jre\*"; DestDir: "{app}\jre"; Flags: recursesubdirs
; dependencies of jvm.dll and javaaccessbridge.dll
Source: "${basedir}\native-win64\jre\bin\vcruntime140.dll"; DestDir: "{app}"
Source: "${basedir}\native-win64\jre\bin\ucrtbase.dll"; DestDir: "{app}"
Source: "${basedir}\native-win64\jre\bin\msvcp140.dll"; DestDir: "{app}"
Source: "${basedir}\native-win64\jre\bin\api-ms-win-*.dll"; DestDir: "{app}"
Source: "${basedir}\native-win64\jre\bin\jawt.dll"; DestDir: "{app}"

[Icons]
; start menu
Name: "{userprograms}\BlueLite"; Filename: "{app}\BlueLite.exe"
Name: "{userdesktop}\BlueLite"; Filename: "{app}\BlueLite.exe"; Tasks: DesktopIcon

[Run]
Filename: "{app}\BlueLite.exe"; Description: "&Open BlueLite"; Flags: postinstall skipifsilent nowait

[InstallDelete]
; Delete the old jvm so it doesn't try to load old stuff with the new vm and crash
Type: filesandordirs; Name: "{app}"

[UninstallDelete]
Type: filesandordirs; Name: "{%USERPROFILE}\.runelite\repository2"
Type: filesandordirs; Name: "{%USERPROFILE}\.runelite\bluerepo"
