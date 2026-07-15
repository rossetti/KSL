@echo off
rem ksl - Windows shim: run ksl.ps1 next to this file, bypassing execution policy,
rem so plain `ksl <cmd>` works. Forwards all arguments. See ksl.ps1 for the logic.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ksl.ps1" %*
