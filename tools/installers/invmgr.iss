; InvMgr's Windows installer, compiled by Inno Setup 7 (https://jrsoftware.org/isinfo.php) from the
; app folder jpackage makes. tools\installers\build-windows.cmd runs it, passing:
;   /DAppVersion=<version>  /DAppImage=<the app folder>  /DOutputDir=<where the setup goes>
;
; It installs for the current user by default, without asking for admin rights, and offers to
; install for everyone instead. Every file is stored without its date (notimestamp), so the same
; app folder always compiles to the same setup, byte for byte, which is how the license check
; proves what the setup holds.

#ifndef AppVersion
  #error Pass /DAppVersion=<version>
#endif
#ifndef AppImage
  #error Pass /DAppImage=<the app folder jpackage made>
#endif
#ifndef OutputDir
  #error Pass /DOutputDir=<where the setup goes>
#endif

[Setup]
; Windows knows an installed app by this ID, so it must never change: a newer setup with the same
; ID upgrades the copy already there.
AppId={{5239BBF2-E4C2-4B96-A8AD-D71E6BBC195D}
AppName=InvMgr
AppVersion={#AppVersion}
AppPublisher=Asterkazika LLC
AppPublisherURL=https://github.com/ModCritic/InvMgrApp
AppSupportURL=https://github.com/ModCritic/InvMgrApp/issues
AppUpdatesURL=https://github.com/ModCritic/InvMgrApp/releases
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\InvMgr
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; The app carries no copy of Microsoft's Universal C Runtime, which Windows 10 and later have built in.
MinVersion=10.0
SetupIconFile={#SourcePath}\..\..\assets\icons\InvMgr.ico
UninstallDisplayIcon={app}\InvMgr.exe
UninstallDisplayName=InvMgr
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
OutputDir={#OutputDir}
OutputBaseFilename=InvMgr-{#AppVersion}-windows-x64-setup

[Messages]
; Pascal Script, the setup program's script engine, asks for a visible line in the About box or the
; documentation; Inno Setup allows lines added at the end of its About box this way.
AboutSetupNote=This setup program uses RemObjects Pascal Script, https://github.com/remobjects/pascalscript

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs notimestamp

[Icons]
Name: "{autoprograms}\InvMgr"; Filename: "{app}\InvMgr.exe"
Name: "{autodesktop}\InvMgr"; Filename: "{app}\InvMgr.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\InvMgr.exe"; Description: "{cm:LaunchProgram,InvMgr}"; Flags: nowait postinstall skipifsilent
