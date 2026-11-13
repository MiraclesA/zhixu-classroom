# Zhixu Classroom Portable Windows Package

1. Extract the package to an ASCII-only writable local folder, for example `C:\ZhixuClassroom`.
2. Double-click `Start-Zhixu.bat`.
3. Open `http://127.0.0.1:8097` if the browser does not open automatically.

No Docker, Python, Maven, system JDK, or system MySQL is required. Windows PowerShell 5.1 must be available in its standard Windows system folder. The first start initializes a private local MySQL database in `runtime/state`; it does not use or modify an existing MySQL installation. Do not extract to a path containing Chinese characters, because the bundled Windows MySQL runtime does not reliably support it.

Use `Stop-Zhixu.bat` to stop the application. To reset demo data, delete the `runtime/state` folder only after stopping the application.

The package is for local course demonstration on 64-bit Windows. It binds only to `127.0.0.1`, so it is not a production or LAN deployment.
