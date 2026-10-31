; Added to the installer Tauri makes (tauri.conf.json, bundle.windows.nsis.installerHooks).

; Tauri's installer closes lumideck-tray.exe, but not a desktop window it opened, which
; would keep lumideck.exe from being replaced.
!macro NSIS_HOOK_PREINSTALL
  nsExec::Exec 'taskkill /IM lumideck.exe /F'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  nsExec::Exec 'taskkill /IM lumideck.exe /F'
!macroend

; "Start with Windows" (src/main.rs) would otherwise point at a program that's gone.
!macro NSIS_HOOK_POSTUNINSTALL
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "LumiDeck"
!macroend
