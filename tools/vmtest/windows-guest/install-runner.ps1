# Registers the scheduled task that starts runner.ps1 in the tester account's desktop session at
# every logon, hidden, and starts it now. Run once in the VM as an administrator, for instance as
# SYSTEM through the QEMU guest agent: vmctl.py ga powershell -ExecutionPolicy Bypass -File <this>.
# The task waits for the share's Z: drive and runs runner.ps1 straight from it, and starts it again
# if it ever stops, so one failure cannot leave the VM without a runner.
$cmd = 'while ($true) { try { while (-not (Test-Path Z:\runner\runner.ps1)) { Start-Sleep 1 }; & Z:\runner\runner.ps1 } catch { }; Start-Sleep 2 }'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -Command `"$cmd`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User 'tester'
$principal = New-ScheduledTaskPrincipal -UserId 'tester' -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName 'invmgr-runner' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName 'invmgr-runner'
Start-Sleep 3
(Get-ScheduledTask -TaskName 'invmgr-runner').State
