$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot
python -m pip install -r requirements.txt
python -m PyInstaller --noconfirm --onefile --windowed --name K16CameraDesktop k16_camera_desktop.py

Write-Host ""
Write-Host "Build complete: $PSScriptRoot\dist\K16CameraDesktop.exe"
